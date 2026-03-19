package com.mathieu.gpssender

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

class PositionService : Service() {

    private val logTag = "GPS_SENDER_LOG"
    private lateinit var locationManager: LocationManager
    private lateinit var locationListener: LocationListener

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val notificationId = 1
    private val channelId = "PositionServiceChannel"

    private var serialFileStream: FileInputStream? = null
    private var inputStream: InputStream? = null
    private var readThread: Thread? = null
    private var reconnectThread: Thread? = null
    private var senderThread: Thread? = null

    @Volatile private var isRunning = false
    @Volatile private var lastRtkTimestamp = 0L
    @Volatile private var rtkActiveStartTimestamp = 0L  // quand le RTK est devenu actif
    @Volatile private var internalGpsActive = true       // GPS interne allumé ou non

    private val lastSentTimestamp = AtomicLong(0L)
    private val RTK_PRIORITY_TIMEOUT_MS = 10_000L
    private val RTK_GPS_CUTOFF_MS = 30_000L  // couper GPS interne après 30s de RTK stable
    private val MIN_SEND_INTERVAL_MS = 2000L
    private val MAX_SPEED_KMH = 200.0        // filtre positions aberrantes

    // ─────────────────────────────────────────────
    // File d'attente
    // ─────────────────────────────────────────────

    data class PendingLocation(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val accuracy: Float,
        val timestamp: Long,
        val source: String,
        val speed: Float = 0f,    // m/s depuis RMC
        val heading: Float = 0f   // degrés depuis RMC
    )

    private val sendQueue = LinkedBlockingQueue<PendingLocation>(500)

    // ─────────────────────────────────────────────
    // Moyenne glissante sur 3 positions RTK
    // ─────────────────────────────────────────────

    private val positionBuffer = ArrayDeque<PendingLocation>(3)
    private val SMOOTHING_COUNT = 3

    // ─────────────────────────────────────────────
    // Filtre vitesse aberrante
    // ─────────────────────────────────────────────

    @Volatile private var lastValidLocation: PendingLocation? = null

    // ─────────────────────────────────────────────
    // Données RMC courantes (speed + heading)
    // ─────────────────────────────────────────────

    @Volatile private var currentSpeedMs = 0f
    @Volatile private var currentHeading = 0f

    private val SERIAL_PATHS = listOf(
        "/dev/ttyS4",
        "/dev/ttyS3",
        "/dev/tty3",
        "/dev/tty4",
        "/dev/ttyUSB0",
        "/dev/ttyUSB1"
    )
    private val BAUD_RATE = 460800

    // ─────────────────────────────────────────────
    // Cycle de vie
    // ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag, "PositionService onCreate()")
        createNotificationChannel()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationListener = LocationListener { location ->
            val now = System.currentTimeMillis()
            val rtkIsActive = (now - lastRtkTimestamp) < RTK_PRIORITY_TIMEOUT_MS
            if (!rtkIsActive) {
                Log.d(logTag, "GPS interne utilisé (RTK inactif)")
                processLocation(
                    PendingLocation(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitude = location.altitude,
                        accuracy = location.accuracy,
                        timestamp = now,
                        source = "GPS Interne"
                    )
                )
            } else {
                Log.d(logTag, "GPS interne ignoré (RTK actif)")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(logTag, "PositionService onStartCommand()")
        isRunning = true

        val notification = createNotification("Démarrage…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(notificationId, notification)
        }

        startAutoDetectAndRead()
        startInternalGps()
        startSenderThread()

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        reconnectThread?.interrupt()
        readThread?.interrupt()
        senderThread?.interrupt()
        try { serialFileStream?.close() } catch (e: Exception) { Log.e(logTag, "Erreur fermeture port série", e) }
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        super.onDestroy()
        Log.d(logTag, "PositionService arrêté")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────
    // Gestion GPS interne (allumer/éteindre selon RTK)
    // ─────────────────────────────────────────────

    private fun startInternalGps() {
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 5000L, 0f, locationListener
                )
                internalGpsActive = true
                Log.d(logTag, "GPS interne (fallback) activé")
            }
        } catch (e: Exception) {
            Log.e(logTag, "Erreur démarrage GPS interne", e)
        }
    }

    private fun stopInternalGps() {
        try {
            locationManager.removeUpdates(locationListener)
            internalGpsActive = false
            Log.i(logTag, "GPS interne coupé (RTK stable depuis ${RTK_GPS_CUTOFF_MS / 1000}s)")
        } catch (e: Exception) {
            Log.e(logTag, "Erreur arrêt GPS interne", e)
        }
    }

    private fun restoreInternalGps() {
        if (!internalGpsActive) {
            startInternalGps()
            Log.i(logTag, "GPS interne restauré (RTK perdu)")
        }
    }

    /**
     * Appelé à chaque position RTK reçue pour gérer le cycle GPS interne.
     */
    private fun updateRtkStatus() {
        val now = System.currentTimeMillis()
        lastRtkTimestamp = now

        if (rtkActiveStartTimestamp == 0L) {
            rtkActiveStartTimestamp = now
            Log.d(logTag, "RTK actif — démarrage du compteur GPS cutoff")
        }

        val rtkDuration = now - rtkActiveStartTimestamp
        if (rtkDuration >= RTK_GPS_CUTOFF_MS && internalGpsActive) {
            stopInternalGps()
        }
    }

    /**
     * Appelé quand le RTK est perdu (timeout).
     */
    private fun onRtkLost() {
        rtkActiveStartTimestamp = 0L
        restoreInternalGps()
    }

    // ─────────────────────────────────────────────
    // Traitement des positions (filtrage + lissage)
    // ─────────────────────────────────────────────

    private fun processLocation(raw: PendingLocation) {
        // 1. Vérifier le timeout RTK pour restaurer le GPS interne si nécessaire
        if (raw.source.startsWith("GPS Interne")) {
            val rtkIsActive = (System.currentTimeMillis() - lastRtkTimestamp) < RTK_PRIORITY_TIMEOUT_MS
            if (rtkIsActive) return // double sécurité
        }

        // 2. Filtre vitesse aberrante
        val last = lastValidLocation
        if (last != null) {
            val distanceM = haversineDistance(last.latitude, last.longitude, raw.latitude, raw.longitude)
            val deltaMs = raw.timestamp - last.timestamp
            if (deltaMs > 0) {
                val speedKmh = (distanceM / deltaMs) * 3600.0
                if (speedKmh > MAX_SPEED_KMH) {
                    Log.w(logTag, "Position aberrante ignorée: vitesse calculée ${String.format("%.1f", speedKmh)} km/h")
                    return
                }
            }
        }

        // 3. Throttle
        val now = System.currentTimeMillis()
        if (now - lastSentTimestamp.get() < MIN_SEND_INTERVAL_MS) {
            Log.d(logTag, "Throttle: ignoré (${raw.source})")
            return
        }
        lastSentTimestamp.set(now)
        lastValidLocation = raw

        // 4. Moyenne glissante (seulement pour RTK)
        val toSend = if (raw.source.startsWith("RTK")) {
            smoothPosition(raw)
        } else {
            raw
        }

        // 5. Mise en file
        val queued = sendQueue.offer(toSend)
        if (!queued) {
            Log.w(logTag, "File pleine, position ignorée")
        } else {
            val queueInfo = if (sendQueue.size > 1) " | file: ${sendQueue.size}" else ""
            val notifText = "${toSend.source} | ${String.format("%.7f", toSend.latitude)}, " +
                    "${String.format("%.7f", toSend.longitude)}$queueInfo"
            updateNotification(notifText)
        }
    }

    /**
     * Moyenne glissante sur SMOOTHING_COUNT positions.
     */
    private fun smoothPosition(pos: PendingLocation): PendingLocation {
        positionBuffer.addLast(pos)
        if (positionBuffer.size > SMOOTHING_COUNT) positionBuffer.removeFirst()
        if (positionBuffer.size < SMOOTHING_COUNT) return pos // pas encore assez de points

        val avgLat = positionBuffer.map { it.latitude }.average()
        val avgLon = positionBuffer.map { it.longitude }.average()
        val avgAlt = positionBuffer.map { it.altitude }.average()

        return pos.copy(latitude = avgLat, longitude = avgLon, altitude = avgAlt)
    }

    /**
     * Distance en mètres entre deux points GPS (formule Haversine).
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // ─────────────────────────────────────────────
    // Thread d'envoi avec file d'attente
    // ─────────────────────────────────────────────

    private fun startSenderThread() {
        senderThread = Thread {
            Log.d(logTag, "Thread d'envoi démarré")
            var consecutiveFailures = 0

            while (isRunning) {
                try {
                    // Vérifier si RTK perdu (restaurer GPS interne)
                    val rtkIsActive = (System.currentTimeMillis() - lastRtkTimestamp) < RTK_PRIORITY_TIMEOUT_MS
                    if (!rtkIsActive && rtkActiveStartTimestamp != 0L) {
                        onRtkLost()
                    }

                    val pending = sendQueue.poll(1, TimeUnit.SECONDS) ?: continue

                    val success = trySendLocation(pending)

                    if (success) {
                        consecutiveFailures = 0
                        if (sendQueue.size > 0) {
                            Log.d(logTag, "File d'attente: ${sendQueue.size} positions restantes")
                        }
                    } else {
                        consecutiveFailures++
                        val requeued = sendQueue.offer(pending)
                        if (!requeued) Log.w(logTag, "File pleine, position perdue")
                        val delay = minOf(2000L * (1 shl minOf(consecutiveFailures - 1, 3)), 30_000L)
                        Log.w(logTag, "Echec envoi ($consecutiveFailures), retry dans ${delay}ms | file: ${sendQueue.size}")
                        Thread.sleep(delay)
                    }

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(logTag, "Erreur thread envoi", e)
                }
            }
            Log.d(logTag, "Thread d'envoi arrêté")
        }.apply {
            name = "SenderThread"
            isDaemon = true
            start()
        }
    }

    private fun trySendLocation(pending: PendingLocation): Boolean {
        if (BuildConfig.API_URL.isBlank() || BuildConfig.API_KEY.isBlank() || BuildConfig.DEVICE_ID.isBlank()) {
            Log.w(logTag, "API non configurée — envoi ignoré")
            return true
        }

        return try {
            val json = JSONObject().apply {
                put("latitude", pending.latitude)
                put("longitude", pending.longitude)
                put("altitude", pending.altitude)
                put("accuracy", pending.accuracy)
                put("speed", pending.speed)         // m/s
                put("heading", pending.heading)     // degrés
                put("id", BuildConfig.DEVICE_ID)
                put("timestamp", pending.timestamp)
                put("key", BuildConfig.API_KEY)
                put("source", pending.source)
            }

            val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(BuildConfig.API_URL)
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(logTag, "Envoye (${pending.source}) HTTP ${response.code} | speed=${pending.speed}m/s heading=${pending.heading}°")
                    true
                } else {
                    Log.e(logTag, "Erreur HTTP ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "Erreur envoi [${e.javaClass.simpleName}]: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────
    // Port série — détection et reconnexion
    // ─────────────────────────────────────────────

    private fun startAutoDetectAndRead() {
        reconnectThread = Thread {
            while (isRunning) {
                if (readThread == null || readThread?.isAlive == false) {
                    Log.d(logTag, "Tentative de connexion au port série RTK...")
                    val found = tryOpenSerialPort()
                    if (!found) {
                        Log.w(logTag, "Aucun port série RTK trouvé, nouvelle tentative dans 5s")
                        updateNotification("RTK: aucun port détecté — GPS interne actif")
                        Thread.sleep(5000)
                    }
                } else {
                    Thread.sleep(2000)
                }
            }
        }.apply {
            name = "ReconnectThread"
            isDaemon = true
            start()
        }
    }

    private fun tryOpenSerialPort(): Boolean {
        for (path in SERIAL_PATHS) {
            val device = File(path)
            if (!device.exists()) { Log.d(logTag, "$path n'existe pas, on passe"); continue }
            if (!device.canRead()) { Log.w(logTag, "$path non lisible"); continue }
            try {
                configureSerialPort(path, BAUD_RATE)
                val fis = FileInputStream(device)
                serialFileStream = fis
                inputStream = fis
                Log.i(logTag, "Port série ouvert : $path @ $BAUD_RATE baud")
                startSerialReadThread(path)
                return true
            } catch (e: Exception) {
                Log.w(logTag, "Echec $path : ${e.message}")
            }
        }
        return false
    }

    private fun configureSerialPort(path: String, baudRate: Int) {
        try {
            val cmd = "stty -F $path $baudRate raw -echo -echoe -echok -echoctl -echoke cs8 -cstopb -parenb -crtscts"
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
            val exitCode = process.waitFor()
            if (exitCode == 0) Log.i(logTag, "Port $path configuré : $baudRate baud")
            else Log.w(logTag, "stty exit=$exitCode : ${process.errorStream.bufferedReader().readText()}")
        } catch (e: Exception) {
            Log.e(logTag, "Erreur stty sur $path", e)
        }
    }

    // ─────────────────────────────────────────────
    // Thread de lecture série
    // ─────────────────────────────────────────────

    private fun startSerialReadThread(path: String) {
        readThread = Thread {
            val buffer = ByteArray(4096)
            val sb = StringBuilder()
            var lastHeartbeat = System.currentTimeMillis()
            Log.d(logTag, "Thread lecture série démarré : $path")
            updateNotification("RTK connecté : $path")

            while (isRunning) {
                try {
                    val size = inputStream?.read(buffer) ?: -1
                    if (size <= 0) { Thread.sleep(10); continue }

                    sb.append(String(buffer, 0, size, Charsets.US_ASCII))

                    var processing = true
                    while (processing) {
                        val start = sb.indexOf("$")
                        if (start == -1) { sb.clear(); break }
                        if (start > 0) sb.delete(0, start)

                        var end = -1
                        for (i in 1 until sb.length) {
                            if (sb[i] == '\n' || sb[i] == '\r') { end = i; break }
                        }

                        if (end == -1) {
                            if (sb.length > 512) sb.delete(0, 1)
                            processing = false
                        } else {
                            val line = sb.substring(0, end).trim()
                            sb.delete(0, end + 1)
                            if (line.startsWith("$") && line.length > 10) parseNmeaLine(line)
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeat > 10_000L) {
                        Log.d(logTag, "Thread série actif sur $path | file: ${sendQueue.size}")
                        lastHeartbeat = now
                    }

                    Thread.sleep(5)

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(logTag, "Erreur lecture série $path : ${e.message}")
                    break
                }
            }

            Log.w(logTag, "Thread lecture série terminé ($path)")
            try { serialFileStream?.close() } catch (_: Exception) {}
            serialFileStream = null
            inputStream = null

        }.apply {
            name = "SerialReadThread"
            isDaemon = true
            start()
        }
    }

    // ─────────────────────────────────────────────
    // Parsing NMEA
    // ─────────────────────────────────────────────

    private fun parseNmeaLine(line: String) {
        if (!line.startsWith("\$")) return
        if (line.contains("*") && !verifyNmeaChecksum(line)) {
            Log.w(logTag, "Checksum invalide: $line")
            return
        }
        when {
            line.contains("GGA") -> parseGga(line)
            line.contains("RMC") -> parseRmc(line)
        }
    }

    /**
     * GGA : position, qualité fix, altitude
     * $GNGGA,hhmmss,Lat,N/S,Lon,E/W,quality,sats,HDOP,alt,M,...*XX
     */
    private fun parseGga(line: String) {
        val parts = (if (line.contains("*")) line.substringBefore("*") else line).split(",")
        if (parts.size < 10 || parts[2].isEmpty() || parts[4].isEmpty()) return

        try {
            val lat = convertNmeaToDecimal(parts[2], parts[3])
            val lon = convertNmeaToDecimal(parts[4], parts[5])
            val quality = parts[6].trim()
            val numSats = parts[7].trim()
            val hdop = parts[8].toDoubleOrNull() ?: 0.0
            val altitude = parts[9].toDoubleOrNull() ?: 0.0

            if (quality == "0") return

            val accuracy = when (quality) { "4" -> 0.02f; "5" -> 0.30f; "2" -> 1.0f; else -> 5.0f }
            val qualityLabel = when (quality) { "4" -> "RTK Fix"; "5" -> "RTK Float"; "2" -> "DGPS"; "1" -> "GPS"; else -> "Fix=$quality" }

            Log.i(logTag, "[$qualityLabel] lat=$lat lon=$lon alt=${altitude}m sats=$numSats hdop=$hdop")

            updateRtkStatus()

            processLocation(
                PendingLocation(
                    latitude = lat,
                    longitude = lon,
                    altitude = altitude,
                    accuracy = accuracy,
                    timestamp = System.currentTimeMillis(),
                    source = "RTK $qualityLabel",
                    speed = currentSpeedMs,
                    heading = currentHeading
                )
            )

        } catch (e: Exception) {
            Log.e(logTag, "Erreur parsing GGA: ${e.message}")
        }
    }

    /**
     * RMC : vitesse (nœuds) et cap (degrés)
     * $GNRMC,hhmmss,A,Lat,N/S,Lon,E/W,speed,heading,date,...*XX
     * Index :  0      1  2  3   4   5   6     7       8     9
     */
    private fun parseRmc(line: String) {
        val parts = (if (line.contains("*")) line.substringBefore("*") else line).split(",")
        if (parts.size < 9) return
        if (parts[2] != "A") return // A = données valides, V = invalides

        try {
            val speedKnots = parts[7].toDoubleOrNull() ?: return
            val heading = parts[8].toDoubleOrNull() ?: 0.0

            currentSpeedMs = (speedKnots * 0.514444).toFloat() // nœuds → m/s
            currentHeading = heading.toFloat()

            Log.d(logTag, "RMC speed=${String.format("%.2f", currentSpeedMs)}m/s heading=${String.format("%.1f", currentHeading)}°")
        } catch (e: Exception) {
            Log.e(logTag, "Erreur parsing RMC: ${e.message}")
        }
    }

    private fun convertNmeaToDecimal(value: String, direction: String): Double {
        val dotIdx = value.indexOf(".")
        if (dotIdx < 2) throw IllegalArgumentException("Format NMEA invalide: $value")
        val degrees = value.substring(0, dotIdx - 2).toDouble()
        val minutes = value.substring(dotIdx - 2).toDouble()
        var result = degrees + (minutes / 60.0)
        if (direction == "S" || direction == "W") result = -result
        return result
    }

    private fun verifyNmeaChecksum(sentence: String): Boolean {
        return try {
            val start = sentence.indexOf('$') + 1
            val end = sentence.indexOf('*')
            if (start < 1 || end < 0 || end <= start) return true
            val data = sentence.substring(start, end)
            val expected = sentence.substring(end + 1, minOf(end + 3, sentence.length)).toInt(16)
            sentence.substring(start, end).fold(0) { acc, c -> acc xor c.code } == expected
        } catch (_: Exception) { true }
    }

    // ─────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Service GPS RTK", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String) =
        NotificationCompat.Builder(this, channelId)
            .setContentTitle("GPS Sender RTK")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, createNotification(text))
    }
}
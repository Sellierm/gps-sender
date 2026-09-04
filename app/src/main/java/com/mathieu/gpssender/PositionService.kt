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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.*

class PositionService : Service() {

    private val logTag = "GPS_SENDER_LOG"
    private lateinit var locationManager: LocationManager
    private lateinit var locationListener: LocationListener

    // ─────────────────────────────────────────────
    // Envoi des positions — Socket.IO persistant (remplace l'ancien POST HTTP)
    // ─────────────────────────────────────────────

    private lateinit var socketClient: LocationSocketClient
    @Volatile private var socketConnected = false

    private val notificationId = 1
    private val channelId = "PositionServiceChannel"

    private var serialFileStream: FileInputStream? = null
    private var inputStream: InputStream? = null
    private var readThread: Thread? = null
    private var reconnectThread: Thread? = null
    private var senderThread: Thread? = null

    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var isRunning = false
    @Volatile private var lastRtkTimestamp = 0L
    @Volatile private var rtkActiveStartTimestamp = 0L
    @Volatile private var internalGpsActive = true

    private val RTK_PRIORITY_TIMEOUT_MS = 10_000L
    private val RTK_GPS_CUTOFF_MS = 30_000L
    private val MIN_SEND_INTERVAL_MS = 3000L
    private val MAX_SPEED_KMH = 200.0

    // ─────────────────────────────────────────────
    // File d'attente locale (max 500 positions)
    // ─────────────────────────────────────────────

    data class PendingLocation(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val accuracy: Float,
        val timestamp: Long,
        val source: String,
        val speed: Float = 0f,
        val heading: Float = 0f
    )

    private val sendQueue = LinkedBlockingQueue<PendingLocation>(500)

    // ─────────────────────────────────────────────
    // Moyenne glissante sur 3 positions RTK
    // Acces concurrent : thread lecture serie + listener GPS interne (ecriture),
    // thread d'envoi via onRtkLost() (clear). Protege par positionBufferLock.
    // ─────────────────────────────────────────────

    private val positionBufferLock = Any()
    private val positionBuffer = ArrayDeque<PendingLocation>()
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
    private val BAUD_RATE = 115200 //7724
    //private val BAUD_RATE = 460800 //6290

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

        socketClient = LocationSocketClient(
            deviceId = BuildConfig.DEVICE_ID,
            apiKey = BuildConfig.API_KEY,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(logTag, "PositionService onStartCommand()")

        // Garde anti-duplication : onStartCommand peut etre rappele (redemarrage
        // systeme, appel concurrent du Watchdog + BootReceiver + MainActivity).
        // Sans cette garde, plusieurs jeux de threads se retrouvent a lire/ecrire
        // sur le meme port serie et la meme file d'envoi.
        if (isRunning) {
            Log.d(logTag, "onStartCommand ignore : service deja actif")
            return START_STICKY
        }
        isRunning = true

        acquireWakeLock()

        val notification = createNotification("Démarrage…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(notificationId, notification)
        }

        connectSocket()
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
        try { socketClient.disconnect() } catch (_: Exception) {}
        releaseWakeLock()
        super.onDestroy()
        Log.d(logTag, "PositionService arrêté")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────
    // Wake lock — evite que Doze/App Standby ne suspende les threads
    // d'envoi et de lecture serie sur les OEM agressifs (Xiaomi, Huawei...)
    // ─────────────────────────────────────────────

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GpsSender:PositionServiceWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L /* 12h, renouvele si le service tourne plus longtemps */)
            }
            Log.d(logTag, "WakeLock acquis")
        } catch (e: Exception) {
            Log.e(logTag, "Erreur acquisition WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(logTag, "Erreur liberation WakeLock", e)
        }
    }

    // ─────────────────────────────────────────────
    // Connexion Socket.IO
    // ─────────────────────────────────────────────

    private fun connectSocket() {
        if (BuildConfig.API_URL.isBlank() || BuildConfig.API_KEY.isBlank() || BuildConfig.DEVICE_ID.isBlank()) {
            Log.w(logTag, "Socket non configuré — envoi désactivé (BuildConfig incomplet)")
            return
        }
        socketClient.connect(BuildConfig.API_URL, object : LocationSocketClient.StatusListener {
            override fun onConnected() {
                socketConnected = true
                updateNotification("Serveur connecté")
            }

            override fun onDisconnected(reason: String?) {
                socketConnected = false
                updateNotification("Serveur déconnecté — reconnexion...")
            }

            override fun onConnectError(message: String?) {
                socketConnected = false
            }
        })
    }

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

    private fun updateRtkStatus() {
        val now = System.currentTimeMillis()
        lastRtkTimestamp = now

        if (rtkActiveStartTimestamp == 0L) {
            rtkActiveStartTimestamp = now
            Log.d(logTag, "RTK actif — démarrage compteur GPS cutoff")
        }

        val rtkDuration = now - rtkActiveStartTimestamp
        if (rtkDuration >= RTK_GPS_CUTOFF_MS && internalGpsActive) {
            stopInternalGps()
        }
    }

    private fun onRtkLost() {
        rtkActiveStartTimestamp = 0L
        synchronized(positionBufferLock) { positionBuffer.clear() }
        restoreInternalGps()
        Log.w(logTag, "RTK perdu — buffer de lissage vidé")
    }

    // ─────────────────────────────────────────────
    // Traitement des positions (filtrage + lissage)
    // ─────────────────────────────────────────────

    private fun processLocation(raw: PendingLocation) {
        // 1. Filtre vitesse aberrante
        val last = lastValidLocation
        if (last != null) {
            val distanceM = haversineDistance(last.latitude, last.longitude, raw.latitude, raw.longitude)
            val deltaMs = raw.timestamp - last.timestamp
            if (deltaMs > 0) {
                val speedKmh = (distanceM / deltaMs) * 3600.0
                if (speedKmh > MAX_SPEED_KMH) {
                    Log.w(logTag, "Position aberrante ignorée: ${String.format("%.1f", speedKmh)} km/h")
                    return
                }
            }
        }

        // 2. Smoothing AVANT le throttle — retourne null si buffer pas encore plein
        val smoothed = if (raw.source.startsWith("RTK")) {
            smoothPosition(raw) ?: return // Pas encore SMOOTHING_COUNT points, on attend sans toucher au timestamp
        } else {
            raw
        }

        // 3. Throttle uniquement sur les positions qui passeront vraiment
        val now = System.currentTimeMillis()
        if (now - lastSentTimestampValue() < MIN_SEND_INTERVAL_MS) {
            Log.d(logTag, "Throttle: ignoré (${raw.source})")
            return
        }
        setLastSentTimestamp(now)
        lastValidLocation = smoothed

        // 4. Mise en file
        val queued = sendQueue.offer(smoothed)
        if (!queued) {
            Log.w(logTag, "File pleine (500), position ignorée")
        } else {
            val queueInfo = if (sendQueue.size > 1) " | file: ${sendQueue.size}" else ""
            updateNotification(
                "${if (socketConnected) "🟢" else "🔴"} ${smoothed.source} | ${String.format("%.7f", smoothed.latitude)}, " +
                        "${String.format("%.7f", smoothed.longitude)}$queueInfo"
            )
        }
    }

    // AtomicLong retire au profit d'un simple champ Volatile : un seul writer
    // (processLocation, appele sequentiellement depuis les listeners) donc pas
    // besoin d'atomicite au-dela de la visibilite garantie par @Volatile.
    @Volatile private var lastSentTimestamp = 0L
    private fun lastSentTimestampValue() = lastSentTimestamp
    private fun setLastSentTimestamp(v: Long) { lastSentTimestamp = v }

    /**
     * Moyenne glissante sur SMOOTHING_COUNT positions.
     * Retourne null si le buffer n'est pas encore plein.
     */
    private fun smoothPosition(pos: PendingLocation): PendingLocation? {
        synchronized(positionBufferLock) {
            positionBuffer.addLast(pos)
            if (positionBuffer.size > SMOOTHING_COUNT) positionBuffer.removeFirst()
            if (positionBuffer.size < SMOOTHING_COUNT) return null

            val avgLat = positionBuffer.map { it.latitude }.average()
            val avgLon = positionBuffer.map { it.longitude }.average()
            val avgAlt = positionBuffer.map { it.altitude }.average()

            return pos.copy(latitude = avgLat, longitude = avgLon, altitude = avgAlt)
        }
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
    // Thread d'envoi avec file d'attente (Socket.IO)
    // ─────────────────────────────────────────────

    private fun startSenderThread() {
        if (senderThread?.isAlive == true) {
            Log.d(logTag, "startSenderThread ignore : deja actif")
            return
        }
        senderThread = Thread {
            Log.d(logTag, "Thread d'envoi démarré")
            var consecutiveFailures = 0

            while (isRunning) {
                try {
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
                    Log.e(logTag, "Erreur thread envoi [${e.javaClass.simpleName}]: ${e.message}")
                }
            }
            Log.d(logTag, "Thread d'envoi arrêté")
        }.apply {
            name = "SenderThread"
            isDaemon = true
            start()
        }
    }

    /**
     * Envoie une position via le socket persistant. Remplace l'ancien
     * trySendLocation base sur OkHttp + POST : plus de handshake TCP/TLS
     * a chaque position, l'authentification se fait une seule fois a la
     * connexion et non plus dans chaque requete.
     */
    private fun trySendLocation(pending: PendingLocation): Boolean {
        if (BuildConfig.API_URL.isBlank() || BuildConfig.API_KEY.isBlank() || BuildConfig.DEVICE_ID.isBlank()) {
            Log.w(logTag, "Socket non configuré — envoi ignoré")
            return true
        }

        if (!socketClient.isConnected()) {
            Log.d(logTag, "Socket non connecté — envoi reporté")
            return false
        }

        return try {
            val json = JSONObject().apply {
                put("latitude", pending.latitude)
                put("longitude", pending.longitude)
                put("altitude", pending.altitude)
                put("accuracy", pending.accuracy)
                put("speed", pending.speed)
                put("heading", pending.heading)
                put("timestamp", pending.timestamp)
                put("source", pending.source)
            }

            val success = socketClient.sendLocation(json)
            if (success) {
                Log.i(logTag, "Envoye (${pending.source}) via socket | speed=${String.format("%.2f", pending.speed)}m/s heading=${String.format("%.1f", pending.heading)}°")
            } else {
                Log.w(logTag, "Echec ack socket")
            }
            success
        } catch (e: Exception) {
            Log.e(logTag, "Erreur envoi socket [${e.javaClass.simpleName}]: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────
    // Port série — détection et reconnexion
    // Le check de perte RTK est fait ici (cadence 2s), pas dans le thread
    // d'envoi (cadence ~1s liee au poll de la file), pour eviter de vider
    // le buffer de lissage sur une micro-coupure NTRIP transitoire.
    // ─────────────────────────────────────────────

    private fun startAutoDetectAndRead() {
        if (reconnectThread?.isAlive == true) {
            Log.d(logTag, "startAutoDetectAndRead ignore : deja actif")
            return
        }
        reconnectThread = Thread {
            while (isRunning) {
                val rtkIsActive = (System.currentTimeMillis() - lastRtkTimestamp) < RTK_PRIORITY_TIMEOUT_MS
                if (!rtkIsActive && rtkActiveStartTimestamp != 0L) {
                    onRtkLost()
                }

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
        var process: Process? = null
        try {
            val cmd = "stty -F $path $baudRate raw -echo -echoe -echok -echoctl -echoke cs8 -cstopb -parenb -crtscts"
            process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Log.i(logTag, "Port $path configuré : $baudRate baud")
            } else {
                val error = process.errorStream.bufferedReader().readText()
                Log.w(logTag, "stty exit=$exitCode : $error")
            }
        } catch (e: Exception) {
            Log.e(logTag, "Erreur stty sur $path", e)
        } finally {
            // Les flux du process ne sont jamais fermes explicitement par
            // Runtime.exec : sans ce bloc, chaque tentative de reconnexion
            // (toutes les 5s si aucun port n'est trouve) fuit 3 descripteurs.
            try { process?.inputStream?.close() } catch (_: Exception) {}
            try { process?.outputStream?.close() } catch (_: Exception) {}
            try { process?.errorStream?.close() } catch (_: Exception) {}
            try { process?.destroy() } catch (_: Exception) {}
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

                    // Parser sur $ — compatible \r, \n, \r\n
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

                    // Heartbeat toutes les 10s
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeat > 10_000L) {
                        Log.d(logTag, "Thread série actif sur $path | file: ${sendQueue.size} | RTK: ${if ((now - lastRtkTimestamp) < RTK_PRIORITY_TIMEOUT_MS) "actif" else "inactif"} | socket: ${if (socketConnected) "connecté" else "déconnecté"}")
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
     * Index :   0     1   2   3   4   5       6    7    8    9  10
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

            if (quality == "0") {
                Log.d(logTag, "GGA reçu mais pas de fix (quality=0)")
                return
            }

            val accuracy = when (quality) {
                "4" -> 0.02f
                "5" -> 0.30f
                "2" -> 1.0f
                else -> 5.0f
            }

            val qualityLabel = when (quality) {
                "4" -> "RTK Fix"
                "5" -> "RTK Float"
                "2" -> "DGPS"
                "1" -> "GPS"
                else -> "Fix=$quality"
            }

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
        if (parts[2] != "A") return // A = données valides

        try {
            val speedKnots = parts[7].toDoubleOrNull() ?: return
            val heading = parts[8].toDoubleOrNull() ?: 0.0
            currentSpeedMs = (speedKnots * 0.514444).toFloat()
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
            data.fold(0) { acc, c -> acc xor c.code } == expected
        } catch (_: Exception) { true }
    }

    // ─────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Service GPS RTK",
                NotificationManager.IMPORTANCE_LOW
            )
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
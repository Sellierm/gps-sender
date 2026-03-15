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
import java.util.concurrent.TimeUnit

class PositionService : Service() {

    private val logTag = "GPS_SENDER_LOG"
    private lateinit var locationManager: LocationManager
    private lateinit var locationListener: LocationListener

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val notificationId = 1
    private val channelId = "PositionServiceChannel"

    private var serialFileStream: FileInputStream? = null
    private var inputStream: InputStream? = null
    private var readThread: Thread? = null
    private var reconnectThread: Thread? = null

    @Volatile private var isRunning = false
    @Volatile private var lastRtkTimestamp = 0L
    @Volatile private var lastSentTimestamp = 0L

    private val RTK_PRIORITY_TIMEOUT_MS = 10_000L
    private val MIN_SEND_INTERVAL_MS = 2000L

    private val SERIAL_PATHS = listOf(
        "/dev/ttyS4",
        "/dev/tty3",
        "/dev/tty4",
        "/dev/ttyS3",
        "/dev/ttyUSB0",
        "/dev/ttyUSB1"
    )

    // ─────────────────────────────────────────────
    // Cycle de vie du service
    // ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag, "PositionService onCreate()")
        createNotificationChannel()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationListener = LocationListener { location ->
            val rtkIsActive = (System.currentTimeMillis() - lastRtkTimestamp) < RTK_PRIORITY_TIMEOUT_MS
            if (!rtkIsActive) {
                Log.d(logTag, "GPS interne utilisé (pas de RTK actif)")
                processAndSendLocation(location, "GPS Interne")
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

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        reconnectThread?.interrupt()
        readThread?.interrupt()
        try { serialFileStream?.close() } catch (e: Exception) { Log.e(logTag, "Erreur fermeture port série", e) }
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        super.onDestroy()
        Log.d(logTag, "PositionService arrêté")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────
    // Détection et reconnexion automatique du port série
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
            if (!device.exists()) {
                Log.d(logTag, "$path n'existe pas, on passe")
                continue
            }
            if (!device.canRead()) {
                Log.w(logTag, "$path existe mais n'est pas lisible")
                continue
            }
            try {
                configureSerialPort(path, 115200)

                Log.d(logTag, "Tentative ouverture $path")
                val fis = FileInputStream(device)
                serialFileStream = fis
                inputStream = fis
                Log.i(logTag, "Port série ouvert : $path @ 115200 baud RS232")
                startSerialReadThread(path)
                return true
            } catch (e: Exception) {
                Log.w(logTag, "Echec ouverture $path : ${e.message}")
            }
        }
        return false
    }

    private fun configureSerialPort(path: String, baudRate: Int) {
        try {
            val cmd = "stty -F $path $baudRate raw -echo -echoe -echok -echoctl -echoke cs8 -cstopb -parenb -crtscts"
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Log.i(logTag, "Port $path configuré : $baudRate baud, raw, RS232")
            } else {
                val error = process.errorStream.bufferedReader().readText()
                Log.w(logTag, "stty exit=$exitCode pour $path : $error")
            }
        } catch (e: Exception) {
            Log.e(logTag, "Erreur configuration stty sur $path", e)
        }
    }

    // ─────────────────────────────────────────────
    // Thread de lecture série
    // ─────────────────────────────────────────────

    private fun startSerialReadThread(path: String) {
        readThread = Thread {
            val buffer = ByteArray(4096)
            val sb = StringBuilder()
            Log.d(logTag, "Thread lecture série démarré : $path")
            updateNotification("RTK connecté : $path")

            while (isRunning) {
                try {
                    val size = inputStream?.read(buffer) ?: -1
                    if (size <= 0) {
                        Thread.sleep(10)
                        continue
                    }

                    sb.append(String(buffer, 0, size, Charsets.US_ASCII))

                    // Parser sur $ — compatible \r, \n, \r\n
                    var processing = true
                    while (processing) {
                        val start = sb.indexOf("$")
                        if (start == -1) { sb.clear(); break }
                        if (start > 0) sb.delete(0, start)

                        // Chercher \r ou \n après le $
                        var end = -1
                        for (i in 1 until sb.length) {
                            val c = sb[i]
                            if (c == '\n' || c == '\r') { end = i; break }
                        }

                        if (end == -1) {
                            // Trame incomplète
                            if (sb.length > 512) sb.delete(0, 1) // $ corrompu, on saute
                            processing = false
                        } else {
                            val line = sb.substring(0, end).trim()
                            sb.delete(0, end + 1)
                            if (line.startsWith("$") && line.length > 10) {
                                parseNmeaLine(line)
                            }
                        }
                    }

                    Thread.sleep(5)

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(logTag, "Erreur lecture série sur $path : ${e.message}")
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
            Log.w(logTag, "Checksum NMEA invalide, ligne ignorée: $line")
            return
        }

        if (line.contains("GGA")) {
            parseGga(line)
        }
    }

    /**
     * Parse une trame GGA :
     * $GNGGA,hhmmss,Lat,N/S,Lon,E/W,quality,nbSats,HDOP,alt,M,...*XX
     * Index :   0     1   2   3   4   5       6      7    8    9  10
     */
    private fun parseGga(line: String) {
        val cleanLine = if (line.contains("*")) line.substringBefore("*") else line
        val parts = cleanLine.split(",")

        if (parts.size < 10) return
        if (parts[2].isEmpty() || parts[4].isEmpty()) return

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

            val location = Location("RTK_SERIAL").apply {
                latitude = lat
                longitude = lon
                this.altitude = altitude
                time = System.currentTimeMillis()
                this.accuracy = accuracy
            }

            lastRtkTimestamp = System.currentTimeMillis()
            Log.i(logTag, "[$qualityLabel] lat=$lat lon=$lon alt=${altitude}m sats=$numSats hdop=$hdop")
            updateNotification("$qualityLabel | sats=$numSats | hdop=$hdop")
            processAndSendLocation(location, "RTK $qualityLabel")

        } catch (e: Exception) {
            Log.e(logTag, "Erreur parsing GGA '$line': ${e.message}")
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
            val computed = data.fold(0) { acc, c -> acc xor c.code }
            computed == expected
        } catch (_: Exception) {
            true
        }
    }

    // ─────────────────────────────────────────────
    // GPS interne (fallback)
    // ─────────────────────────────────────────────

    private fun startInternalGps() {
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L,
                    0f,
                    locationListener
                )
                Log.d(logTag, "GPS interne (fallback) activé")
            }
        } catch (e: Exception) {
            Log.e(logTag, "Erreur démarrage GPS interne", e)
        }
    }

    // ─────────────────────────────────────────────
    // Envoi HTTP
    // ─────────────────────────────────────────────

    private fun processAndSendLocation(location: Location, source: String) {
        val now = System.currentTimeMillis()
        if (now - lastSentTimestamp < MIN_SEND_INTERVAL_MS) {
            Log.d(logTag, "Throttle: envoi ignoré ($source)")
            return
        }
        lastSentTimestamp = now

        val message = "$source | ${String.format("%.7f", location.latitude)}, ${String.format("%.7f", location.longitude)}"
        updateNotification(message)
        sendLocation(location, source)
    }

    private fun sendLocation(location: Location, source: String) {
        Thread {
            try {
                val json = JSONObject().apply {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("altitude", location.altitude)
                    put("accuracy", location.accuracy)
                    put("id", "6290")
                    put("timestamp", System.currentTimeMillis())
                    put("key", "yVi9gGEvcL2E")
                    put("source", source)
                }

                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url("https://sellier.alwaysdata.net/api/location")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful)
                        Log.i(logTag, "Envoye ($source) HTTP ${response.code}")
                    else
                        Log.e(logTag, "Erreur HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(logTag, "Erreur envoi HTTP", e)
            }
        }.apply { isDaemon = true }.start()
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
package com.mathieu.gpssender

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import org.json.JSONArray
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.*

class PositionService : Service() {

    companion object {
        const val ACTION_RELOAD_SETTINGS = "com.mathieu.gpssender.RELOAD_SETTINGS"

        /** Vrai tant que le service tourne dans ce process (remplace ActivityManager.getRunningServices, déprécié). */
        @Volatile
        var isActive = false
            private set

        /**
         * Démarre le service en avant-plan. Depuis Android 12, le démarrage depuis l'arrière-plan
         * (watchdog, boot) peut être refusé par le système : on log au lieu de crasher.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PositionService::class.java))
            } catch (e: Exception) {
                AppLogger.e("GPS_SENDER_LOG", "Démarrage du service refusé par le système", e)
            }
        }

        private const val NOTIFICATION_MIN_INTERVAL_MS = 1000L

        // Auto-détection du baud rate : durée d'écoute par vitesse et nombre de trames NMEA valides exigées
        // (à une mauvaise vitesse on ne lit que du bruit, dont le checksum ne tombe juste que par hasard)
        private const val BAUD_PROBE_DURATION_MS = 2_000L
        private const val BAUD_PROBE_MIN_VALID_SENTENCES = 2
        // En mode auto, sans trame valide pendant ce délai, on relance la détection (récepteur reconfiguré...)
        private const val AUTO_BAUD_STALE_MS = 15_000L
        private val NMEA_SENTENCE_REGEX = Regex("""\$[A-Z]{5},[^$*\r\n]*\*[0-9A-Fa-f]{2}""")
        private const val LOCATION_DISABLED_TEXT = "⚠️ Localisation désactivée sur l'appareil — aucune position"
        private const val RTK_FIX_SOURCE = "RTK RTK Fix"
        // Au-delà, la date RMC est jugée périmée (récepteur qui n'émet plus de RMC) : on prend celle du téléphone
        private const val RMC_DATE_MAX_AGE_MS = 5_000L
        // Une trame GST n'est utilisée que si elle date de moins de 2 s (précision de l'époque courante)
        private const val GST_MAX_AGE_MS = 2_000L
        // Le WakeLock est pris pour 1 h et renouvelé toutes les 10 min tant que le service tourne :
        // pas d'expiration en cours de route, et il retombe de lui-même si le service se bloque.
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 10 * 60 * 1000L
    }

    private val logTag = "GPS_SENDER_LOG"
    private lateinit var locationManager: LocationManager
    private lateinit var locationListener: LocationListener
    // Dernier fix du fournisseur GPS : tant qu'il est récent, les positions réseau (±centaines de m) sont ignorées
    @Volatile private var lastInternalGpsFix = 0L
    private val GPS_PREFERRED_WINDOW_MS = 5_000L

    @Volatile private var socketClient: LocationSocketClient? = null
    @Volatile private var socketConnected = false

    private val notificationId = 1
    private val channelId = "PositionServiceChannel"

    // Flux du port série ouvert ; le thread de lecture s'arrête dès qu'il ne correspond plus au sien
    @Volatile private var serialFileStream: FileInputStream? = null
    @Volatile private var readThread: Thread? = null
    private var reconnectThread: Thread? = null
    private var senderThread: Thread? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wakeLockRenewer = object : Runnable {
        override fun run() {
            try {
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            } catch (e: Exception) {
                AppLogger.e(logTag, "Erreur renouvellement WakeLock", e)
            }
            mainHandler.postDelayed(this, WAKE_LOCK_RENEW_MS)
        }
    }

    @Volatile private var isRunning = false
    @Volatile private var lastRtkTimestamp = 0L
    @Volatile private var rtkActiveStartTimestamp = 0L
    @Volatile private var internalGpsActive = false
    @Volatile private var sendIntervalMs = AppPreferences.DEFAULT_SEND_INTERVAL_MS
    @Volatile private var lastNotificationUpdate = 0L
    // Avertissement ajouté à la notification quand l'économie d'énergie bride le GPS (vide sinon)
    @Volatile private var powerSaveWarning = ""

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = checkLocationPowerSave()
    }

    private val RTK_PRIORITY_TIMEOUT_MS = 10_000L
    private val RTK_GPS_CUTOFF_MS = 30_000L
    // Une dernière position connue plus ancienne n'est pas réinjectée : elle serait envoyée comme
    // position actuelle alors que l'appareil a pu être déplacé depuis
    private val LAST_KNOWN_MAX_AGE_MS = 60_000L

    private lateinit var locationQueue: LocationQueue
    // Réveille le thread d'envoi dès qu'une position est ajoutée à la file
    private val queueSignal = Semaphore(0)
    // Réveille le thread d'envoi en attente après un échec dès que le socket se reconnecte
    private val connectionSignal = Semaphore(0)
    private val BATCH_SIZE = 50

    private val positionBufferLock = Any()
    private val positionBuffer = ArrayDeque<PendingLocation>()
    private val SMOOTHING_COUNT = 3

    @Volatile private var lastValidLocation: PendingLocation? = null

    @Volatile private var currentSpeedMs = 0f
    @Volatile private var currentHeading = 0f
    // Date + heure UTC de la dernière RMC valide, et heure système de sa réception
    @Volatile private var rmcEpochMs = 0L
    @Volatile private var rmcReceivedAt = 0L

    @Volatile private var gstHorizontalErrorM = 0f
    @Volatile private var gstReceivedAt = 0L

    // Qualité de la dernière GGA : seuls ses changements sont journalisés (pas chaque fix à 10 Hz)
    @Volatile private var lastGgaQuality: String? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.d(logTag, "PositionService onCreate()")
        createNotificationChannel()
        locationQueue = LocationQueue(this)
        sendIntervalMs = AppPreferences.getSendIntervalMs(this)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        // Objet complet (et non lambda) : avant Android 11, onProviderEnabled/Disabled/onStatusChanged
        // n'ont pas d'implémentation par défaut et provoqueraient un AbstractMethodError.
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) = onInternalLocation(location)

            override fun onProviderEnabled(provider: String) {
                AppLogger.i(logTag, "Fournisseur de localisation activé : $provider")
                updateNotification("Localisation activée ($provider) — recherche de position…", force = true)
            }

            override fun onProviderDisabled(provider: String) {
                AppLogger.w(logTag, "Fournisseur de localisation désactivé : $provider")
                if (!isLocationEnabled()) {
                    updateNotification(LOCATION_DISABLED_TEXT, force = true)
                }
            }

            @Deprecated("Requis avant Android 11")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
    }

    private fun onInternalLocation(location: Location) {
        val now = System.currentTimeMillis()
        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastInternalGpsFix = now
        } else if (now - lastInternalGpsFix < GPS_PREFERRED_WINDOW_MS) {
            return
        }
        val rtkIsActive = (now - lastRtkTimestamp) < RTK_PRIORITY_TIMEOUT_MS
        if (!rtkIsActive) {
            AppLogger.trace(logTag) { "GPS interne utilisé (RTK inactif) | fournisseur=${location.provider} ±${location.accuracy.roundToInt()}m" }
            processLocation(
                PendingLocation(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = location.altitude,
                    accuracy = location.accuracy,
                    timestamp = now,
                    // Une position réseau (Wi-Fi/antennes, ±100 m, ~1 toutes les 20 s) n'est pas un fix GPS
                    source = if (location.provider == LocationManager.GPS_PROVIDER) "GPS Interne" else "Réseau",
                    speed = if (location.hasSpeed()) location.speed else 0f,
                    heading = if (location.hasBearing()) location.bearing else 0f
                )
            )
        } else {
            AppLogger.trace(logTag) { "GPS interne ignoré (RTK actif)" }
        }
    }

    private fun isLocationEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(locationManager)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.d(logTag, "PositionService onStartCommand() action=${intent?.action}")

        if (intent?.action == ACTION_RELOAD_SETTINGS) {
            if (isRunning) {
                AppLogger.d(logTag, "Rechargement dynamique des paramètres...")
                reloadSettings()
            }
            return START_STICKY
        }

        if (isRunning) {
            AppLogger.d(logTag, "onStartCommand ignoré : service déjà actif")
            return START_STICKY
        }

        val notification = createNotification("Démarrage…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            // Ex : ForegroundServiceStartNotAllowedException / SecurityException (permission retirée)
            AppLogger.e(logTag, "Impossible de passer en avant-plan, arrêt du service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        isActive = true
        acquireWakeLock()

        connectSocket()
        startAutoDetectAndRead()
        startInternalGps()
        startSenderThread()

        registerReceiver(powerSaveReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        checkLocationPowerSave()
        logBatteryOptimization()

        return START_STICKY
    }

    private fun logBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            AppLogger.i(logTag, "Optimisation de batterie désactivée pour l'appli : relance par le watchdog possible")
        } else {
            AppLogger.w(logTag, "Optimisation de batterie active : sur Android 12+, le watchdog ne pourra pas relancer le service")
        }
    }

    /**
     * En mode Économie d'énergie, Android coupe (ou bride) le GPS écran éteint, même pour un service
     * de premier plan : seules les positions réseau (~1 toutes les 20 s, ±100 m) arrivent alors.
     * Aucune API ne permet de contourner ce réglage : on le signale à l'utilisateur.
     */
    private fun checkLocationPowerSave() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val restricted = pm.isPowerSaveMode && (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                pm.locationPowerSaveMode != PowerManager.LOCATION_MODE_NO_CHANGE)
        if (restricted) {
            AppLogger.w(logTag, "Économie d'énergie active : le GPS est coupé/bridé écran éteint — désactivez-la pour une cadence précise")
            powerSaveWarning = " | ⚠️ Éco. d'énergie"
            updateNotification("⚠️ Économie d'énergie active : GPS bridé écran éteint", force = true)
        } else if (powerSaveWarning.isNotEmpty()) {
            AppLogger.i(logTag, "Économie d'énergie désactivée — GPS à pleine cadence")
            powerSaveWarning = ""
        }
    }

    private fun reloadSettings() {
        val newDeviceId = AppPreferences.getDeviceId(this)
        val newBaudRate = AppPreferences.getBaudRate(this)
        val newPort = AppPreferences.getSerialPort(this)
        val newInterval = AppPreferences.getSendIntervalMs(this)
        sendIntervalMs = newInterval
        nextSendSlot = 0L

        AppLogger.i(logTag, "Paramètres rechargés: ID=$newDeviceId, Port=$newPort, Baud=${AppPreferences.baudRateLabel(newBaudRate)}, Interval=${newInterval}ms")

        try {
            socketClient?.disconnect()
            socketClient = null
            socketConnected = false
        } catch (e: Exception) {
            AppLogger.w(logTag, "Erreur déconnexion socket lors du rechargement: ${e.message}")
        }
        connectSocket()

        // Fermer le flux et le retirer suffit à arrêter le thread de lecture (il vérifie que le flux
        // est toujours le sien) ; le ReconnectThread rouvre ensuite le port avec les nouveaux paramètres.
        val stream = serialFileStream
        serialFileStream = null
        try { stream?.close() } catch (_: Exception) {}
        readThread?.interrupt()

        if (internalGpsActive) {
            stopInternalGps("mise à jour de la cadence")
            startInternalGps()
        }

        updateNotification("Paramètres mis à jour (ID: $newDeviceId)", force = true)
    }

    override fun onDestroy() {
        isRunning = false
        isActive = false
        mainHandler.removeCallbacks(wakeLockRenewer)
        try { unregisterReceiver(powerSaveReceiver) } catch (_: Exception) {}
        reconnectThread?.interrupt()
        readThread?.interrupt()
        senderThread?.interrupt()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            AppLogger.e(logTag, "Erreur stopForeground", e)
        }

        try { serialFileStream?.close() } catch (e: Exception) { AppLogger.e(logTag, "Erreur fermeture port série", e) }
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        try { socketClient?.disconnect() } catch (_: Exception) {}
        releaseWakeLock()
        super.onDestroy()
        AppLogger.d(logTag, "PositionService arrêté")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GpsSender:PositionServiceWakeLock"
            ).apply {
                // Non compté : chaque acquire() repousse simplement l'échéance
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            mainHandler.removeCallbacks(wakeLockRenewer)
            mainHandler.postDelayed(wakeLockRenewer, WAKE_LOCK_RENEW_MS)
            AppLogger.d(logTag, "WakeLock acquis (renouvelé toutes les ${WAKE_LOCK_RENEW_MS / 60_000} min)")
        } catch (e: Exception) {
            AppLogger.e(logTag, "Erreur acquisition WakeLock", e)
        }

        // Écran éteint, le Wi-Fi passe en économie d'énergie : les paquets sont regroupés et retardés
        // de plusieurs centaines de ms, ce qui casse la cadence d'envoi (chaque envoi attend son ack)
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GpsSender:PositionServiceWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            AppLogger.d(logTag, "WifiLock acquis")
        } catch (e: Exception) {
            AppLogger.e(logTag, "Erreur acquisition WifiLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {
            AppLogger.e(logTag, "Erreur libération WakeLock", e)
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (e: Exception) {
            AppLogger.e(logTag, "Erreur libération WifiLock", e)
        }
    }

    private fun connectSocket() {
        val currentDeviceId = AppPreferences.getDeviceId(this)
        val apiUrl = AppPreferences.getApiUrl(this)
        val apiKey = AppPreferences.getApiKey(this)
        if (apiUrl.isBlank() || apiKey.isBlank() || currentDeviceId.isBlank()) {
            AppLogger.w(logTag, "Socket non configuré — envoi désactivé (URL, clé API ou ID vide)")
            updateNotification("⚠️ Serveur non configuré (URL / clé API)", force = true)
            return
        }

        AppLogger.d(logTag, "Initialisation socket vers $apiUrl avec deviceId=$currentDeviceId")
        val client = LocationSocketClient(
            deviceId = currentDeviceId,
            apiKey = apiKey,
        )
        socketClient = client

        client.connect(apiUrl, object : LocationSocketClient.StatusListener {
            override fun onConnected() {
                socketConnected = true
                connectionSignal.release()
                updateNotification("Serveur connecté (ID: $currentDeviceId)", force = true)
            }

            override fun onDisconnected(reason: String?) {
                socketConnected = false
                updateNotification("Serveur déconnecté — reconnexion...", force = true)
            }

            override fun onConnectError(message: String?) {
                socketConnected = false
            }
        })
    }

    private val internalGpsLock = Any()

    // Appelé depuis le main thread (démarrage) et le ReconnectThread (secours) : sérialisé pour éviter un double abonnement
    private fun startInternalGps(): Unit = synchronized(internalGpsLock) {
        if (internalGpsActive) return
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            ) {
                val minTime = minOf(1000L, sendIntervalMs)

                // On s'abonne même aux fournisseurs désactivés : les positions arriveront dès que
                // l'utilisateur réactivera la localisation, sans redémarrer le service.
                // Le fournisseur réseau ne livre qu'une position toutes les ~20 s ; le fusionné (API 31+)
                // peut être plus rapide sans fix GPS. Ignoré tant que le GPS a un fix récent.
                val candidates = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) candidates += LocationManager.FUSED_PROVIDER
                val providers = candidates.filter { it in locationManager.allProviders }
                for (provider in providers) {
                    locationManager.requestLocationUpdates(provider, minTime, 0f, locationListener, Looper.getMainLooper())
                }

                internalGpsActive = true
                AppLogger.d(logTag, "GPS interne (fallback) activé sur $providers (cadence: ${minTime}ms)")

                if (!isLocationEnabled()) {
                    AppLogger.w(logTag, "Localisation désactivée dans les paramètres de l'appareil — aucune position interne tant qu'elle n'est pas réactivée")
                    updateNotification(LOCATION_DISABLED_TEXT, force = true)
                }

                val lastKnown = getBestLastKnownLocation()
                if (lastKnown != null) {
                    val ageMs = System.currentTimeMillis() - lastKnown.time
                    if (ageMs < LAST_KNOWN_MAX_AGE_MS) {
                        AppLogger.d(logTag, "Dernière position connue récente (lat=${lastKnown.latitude}, lon=${lastKnown.longitude}, il y a ${ageMs / 1000}s)")
                        locationListener.onLocationChanged(lastKnown)
                    } else {
                        AppLogger.d(logTag, "Dernière position connue trop ancienne (${ageMs / 60_000} min), ignorée")
                    }
                }
            } else {
                AppLogger.w(logTag, "Permission localisation manquante pour le GPS interne (fallback)")
            }
        } catch (e: Exception) {
            AppLogger.e(logTag, "Erreur démarrage GPS interne", e)
        }
    }

    private fun getBestLastKnownLocation(): Location? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        var bestLocation: Location? = null
        for (provider in locationManager.getProviders(true)) {
            val loc = try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: Exception) {
                null
            } ?: continue
            if (bestLocation == null || loc.time > bestLocation.time) {
                bestLocation = loc
            }
        }
        return bestLocation
    }

    private fun stopInternalGps(reason: String = "RTK actif"): Unit = synchronized(internalGpsLock) {
        try {
            locationManager.removeUpdates(locationListener)
            internalGpsActive = false
            AppLogger.i(logTag, "GPS interne désactivé ($reason)")
        } catch (e: Exception) {
            AppLogger.e(logTag, "Erreur arrêt GPS interne", e)
        }
    }

    private fun restoreInternalGps() {
        if (!internalGpsActive) {
            AppLogger.i(logTag, "Restauration du GPS interne en secours (RTK non disponible)")
            startInternalGps()
        }
    }

    private fun updateRtkStatus() {
        val now = System.currentTimeMillis()
        lastRtkTimestamp = now

        if (rtkActiveStartTimestamp == 0L) {
            rtkActiveStartTimestamp = now
            AppLogger.d(logTag, "RTK actif — démarrage compteur GPS cutoff")
        }

        val rtkDuration = now - rtkActiveStartTimestamp
        if (rtkDuration >= RTK_GPS_CUTOFF_MS && internalGpsActive) {
            stopInternalGps("RTK stable depuis ${RTK_GPS_CUTOFF_MS / 1000}s")
        }
    }

    private fun onRtkLost() {
        rtkActiveStartTimestamp = 0L
        lastGgaQuality = null
        synchronized(positionBufferLock) { positionBuffer.clear() }
        restoreInternalGps()
        AppLogger.w(logTag, "RTK perdu/inactif — bascule sur GPS interne de secours")
    }

    // Appelé depuis le main looper (GPS interne) et le thread série (RTK) : sérialisé pour le throttle
    @Synchronized
    private fun processLocation(raw: PendingLocation) {
        val last = lastValidLocation
        if (last != null && SpeedFilter.isAberrant(last, raw)) {
            val speedKmh = SpeedFilter.apparentSpeedKmh(last, raw) ?: 0.0
            AppLogger.w(logTag, "Position aberrante ignorée: ${"%.1f".fmt(speedKmh)} km/h")
            return
        }

        // Un RTK Fix (±2 cm) n'est pas lissé : la moyenne glissante ajouterait un retard de
        // ~30 cm à 3 m/s. Seul le RTK Float, plus bruité, est moyenné.
        val smoothed = when {
            raw.source == RTK_FIX_SOURCE -> {
                synchronized(positionBufferLock) { positionBuffer.clear() }
                raw
            }
            raw.source.startsWith("RTK") -> smoothPosition(raw) ?: return
            else -> raw
        }

        val interval = sendIntervalMs
        val now = System.currentTimeMillis()
        // Tolérance : un fix arrivé un peu avant l'échéance (gigue du GPS, plus forte écran éteint) est accepté.
        // Le rejeter ferait sauter un créneau entier (ex. 1 Hz → 0,5 Hz quand le GPS tourne à la même cadence).
        if (now < nextSendSlot - interval / 4) {
            AppLogger.trace(logTag) { "Throttle: ignoré (${raw.source}) [< ${interval}ms]" }
            return
        }
        // Créneau suivant calé sur l'échéance et non sur l'heure d'arrivée : pas de dérive ni d'accélération.
        // Après une interruption (aucun fix pendant plus d'un intervalle), on se recale sur maintenant.
        nextSendSlot = if (now - nextSendSlot > interval) now + interval else nextSendSlot + interval
        lastValidLocation = smoothed

        try {
            locationQueue.add(smoothed)
        } catch (e: Exception) {
            AppLogger.e(logTag, "Erreur écriture file d'attente, position perdue", e)
            return
        }
        queueSignal.release()
        val queueSize = locationQueue.size()
        val queueInfo = if (queueSize > 1) " | file: $queueSize" else ""
        updateNotification(
            "${if (socketConnected) "🟢" else "🔴"} ${smoothed.source} | ${"%.7f".fmt(smoothed.latitude)}, " +
                    "${"%.7f".fmt(smoothed.longitude)}$queueInfo$powerSaveWarning"
        )
    }

    @Volatile private var nextSendSlot = 0L

    /** Formatage indépendant de la locale (évite "48,1234567" en français). */
    private fun String.fmt(vararg args: Any): String = String.format(Locale.US, this, *args)

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

    private fun startSenderThread() {
        if (senderThread?.isAlive == true) {
            AppLogger.d(logTag, "startSenderThread ignoré : déjà actif")
            return
        }
        senderThread = Thread {
            AppLogger.d(logTag, "Thread d'envoi démarré | file: ${locationQueue.size()}")
            var consecutiveFailures = 0

            while (isRunning) {
                try {
                    // Les positions restent en file jusqu'à l'ack : l'ordre chronologique est préservé
                    // et rien n'est perdu si le service est tué pendant l'envoi.
                    queueSignal.drainPermits()
                    val batch = locationQueue.peek(BATCH_SIZE)
                    if (batch == null) {
                        queueSignal.tryAcquire(1, TimeUnit.SECONDS)
                        continue
                    }

                    if (trySendBatch(batch)) {
                        locationQueue.removeUpTo(batch.lastId)
                        consecutiveFailures = 0
                        connectionSignal.drainPermits()
                        val remaining = locationQueue.size()
                        if (remaining > 0) {
                            AppLogger.d(logTag, "File d'attente: $remaining positions restantes")
                        }
                    } else {
                        consecutiveFailures++
                        // 2, 4, 8 puis 16 s ; une reconnexion du socket écourte l'attente
                        val delay = 2000L shl minOf(consecutiveFailures - 1, 3)
                        AppLogger.w(logTag, "Echec envoi ($consecutiveFailures), retry dans ${delay}ms ou dès la reconnexion | file: ${locationQueue.size()}")
                        connectionSignal.tryAcquire(delay, TimeUnit.MILLISECONDS)
                    }

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    AppLogger.e(logTag, "Erreur thread envoi [${e.javaClass.simpleName}]: ${e.message}")
                    // Évite une boucle serrée si la base locale est en erreur
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                }
            }
            AppLogger.d(logTag, "Thread d'envoi arrêté")
        }.apply {
            name = "SenderThread"
            isDaemon = true
            start()
        }
    }

    private fun trySendBatch(batch: QueuedBatch): Boolean {
        // Non configuré : les positions sont conservées en file jusqu'à la configuration
        if (AppPreferences.getApiUrl(this).isBlank() || AppPreferences.getApiKey(this).isBlank() ||
            AppPreferences.getDeviceId(this).isBlank()
        ) {
            AppLogger.w(logTag, "Socket non configuré — envoi reporté")
            return false
        }

        val client = socketClient
        if (client == null || !client.isConnected()) {
            AppLogger.d(logTag, "Socket non connecté — envoi reporté")
            return false
        }

        return try {
            val points = JSONArray()
            for (pending in batch.locations) {
                points.put(JSONObject().apply {
                    put("latitude", pending.latitude)
                    put("longitude", pending.longitude)
                    put("altitude", pending.altitude)
                    put("accuracy", pending.accuracy)
                    put("speed", pending.speed)
                    put("heading", pending.heading)
                    put("timestamp", pending.timestamp)
                    put("source", pending.source)
                })
            }

            val result = client.sendBatch(points)
            if (result.success) {
                val last = batch.locations.last()
                val rejectedInfo = if (result.rejected > 0) " | ${result.rejected} rejetée(s) par le serveur" else ""
                AppLogger.i(logTag, "Envoyé ${batch.locations.size} position(s) (${last.source}) via socket | speed=${"%.2f".fmt(last.speed)}m/s heading=${"%.1f".fmt(last.heading)}°$rejectedInfo")
            } else {
                AppLogger.w(logTag, "Échec ack socket${result.message?.let { " : $it" } ?: ""}")
            }
            result.success
        } catch (e: Exception) {
            AppLogger.e(logTag, "Erreur envoi socket [${e.javaClass.simpleName}]: ${e.message}")
            false
        }
    }

    private fun startAutoDetectAndRead() {
        if (reconnectThread?.isAlive == true) {
            AppLogger.d(logTag, "startAutoDetectAndRead ignoré : déjà actif")
            return
        }
        reconnectThread = Thread {
            while (isRunning) {
                try {
                    val rtkIsActive = (System.currentTimeMillis() - lastRtkTimestamp) < RTK_PRIORITY_TIMEOUT_MS
                    if (!rtkIsActive && rtkActiveStartTimestamp != 0L) {
                        onRtkLost()
                    }

                    if (readThread == null || readThread?.isAlive == false) {
                        AppLogger.d(logTag, "Tentative de connexion au port série RTK...")
                        val found = tryOpenSerialPort()
                        if (!found) {
                            AppLogger.w(logTag, "Aucun port série RTK trouvé, nouvelle tentative dans 5s (GPS interne actif)")
                            if (!internalGpsActive) {
                                restoreInternalGps()
                            }
                            updateNotification(
                                if (isLocationEnabled()) "RTK: aucun port détecté — GPS interne actif" else LOCATION_DISABLED_TEXT,
                                force = true
                            )
                            Thread.sleep(5000)
                        }
                    } else {
                        Thread.sleep(2000)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    AppLogger.e(logTag, "Erreur reconnectThread: ${e.message}")
                }
            }
        }.apply {
            name = "ReconnectThread"
            isDaemon = true
            start()
        }
    }

    // Dernier baud rate auto-détecté : essayé en premier lors des reconnexions
    @Volatile private var detectedBaudRate: Int? = null

    private fun tryOpenSerialPort(): Boolean {
        val selectedPort = AppPreferences.getSerialPort(this)
        val configuredBaudRate = AppPreferences.getBaudRate(this)
        val autoBaud = configuredBaudRate == AppPreferences.BAUD_RATE_AUTO

        val portsToTry = mutableListOf<String>()

        if (selectedPort != AppPreferences.DEFAULT_PORT_AUTO && selectedPort.isNotBlank()) {
            portsToTry.add(selectedPort)
        }

        for (path in AppPreferences.SERIAL_PATHS) {
            if (!portsToTry.contains(path)) {
                portsToTry.add(path)
            }
        }

        for (path in portsToTry) {
            val device = File(path)
            if (!device.exists()) { AppLogger.d(logTag, "$path n'existe pas, on passe"); continue }
            if (!device.canRead()) { AppLogger.w(logTag, "$path non lisible"); continue }
            try {
                val baudRate = if (autoBaud) {
                    detectBaudRate(path) ?: continue
                } else {
                    configureSerialPort(path, configuredBaudRate)
                    configuredBaudRate
                }
                val fis = FileInputStream(device)
                serialFileStream = fis
                AppLogger.i(logTag, "Port série ouvert : $path @ $baudRate baud${if (autoBaud) " (auto-détecté)" else ""}")
                startSerialReadThread(path, fis, autoBaud)
                return true
            } catch (e: Exception) {
                AppLogger.w(logTag, "Echec $path : ${e.message}")
            }
        }
        return false
    }

    private fun detectBaudRate(path: String): Int? {
        val candidates = (listOfNotNull(detectedBaudRate) + AppPreferences.AUTO_DETECT_BAUD_ORDER).distinct()
        AppLogger.i(logTag, "Auto-détection du baud rate sur $path...")
        updateNotification("RTK : détection de la vitesse sur $path…", force = true)

        for (baud in candidates) {
            if (!isRunning) return null
            // Si stty échoue, la vitesse n'est pas appliquée et la lecture pourrait bloquer : on ne sonde pas
            if (!configureSerialPort(path, baud)) continue
            if (probeNmea(path)) {
                AppLogger.i(logTag, "Baud rate détecté sur $path : $baud")
                detectedBaudRate = baud
                return baud
            }
            AppLogger.d(logTag, "$path @ $baud baud : aucune trame NMEA valide")
        }
        AppLogger.w(logTag, "$path : aucun baud rate ne donne de trames NMEA valides")
        return null
    }

    private fun probeNmea(path: String): Boolean {
        return try {
            FileInputStream(path).use { fis ->
                val buffer = ByteArray(1024)
                val sb = StringBuilder()
                val deadline = System.currentTimeMillis() + BAUD_PROBE_DURATION_MS
                var found = false
                while (!found && System.currentTimeMillis() < deadline && isRunning) {
                    val size = fis.read(buffer)
                    if (size <= 0) { Thread.sleep(50); continue }
                    sb.append(String(buffer, 0, size, Charsets.US_ASCII))
                    found = NMEA_SENTENCE_REGEX.findAll(sb).count { Nmea.verifyChecksum(it.value) } >= BAUD_PROBE_MIN_VALID_SENTENCES
                }
                found
            }
        } catch (e: Exception) {
            AppLogger.w(logTag, "Erreur sondage $path : ${e.message}")
            false
        }
    }

    /** Retourne true si stty a appliqué la configuration. */
    private fun configureSerialPort(path: String, baudRate: Int): Boolean {
        var process: Process? = null
        try {
            // min 0 time 5 : read() rend la main après 0,5 s sans données au lieu de bloquer indéfiniment
            val cmd = "stty -F $path $baudRate raw -echo -echoe -echok -echoctl -echoke cs8 -cstopb -parenb -crtscts min 0 time 5"
            process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                AppLogger.i(logTag, "Port $path configuré : $baudRate baud")
                return true
            } else {
                val error = process.errorStream.bufferedReader().readText()
                AppLogger.w(logTag, "stty exit=$exitCode : $error")
            }
        } catch (e: Exception) {
            AppLogger.e(logTag, "Erreur stty sur $path", e)
        } finally {
            try { process?.inputStream?.close() } catch (_: Exception) {}
            try { process?.outputStream?.close() } catch (_: Exception) {}
            try { process?.errorStream?.close() } catch (_: Exception) {}
            try { process?.destroy() } catch (_: Exception) {}
        }
        return false
    }

    private fun startSerialReadThread(path: String, stream: FileInputStream, autoBaud: Boolean) {
        readThread = Thread {
            val buffer = ByteArray(4096)
            val sb = StringBuilder()
            var lastHeartbeat = System.currentTimeMillis()
            var lastValidSentence = System.currentTimeMillis()
            AppLogger.d(logTag, "Thread lecture série démarré : $path")
            updateNotification("RTK connecté : $path", force = true)

            while (isRunning) {
                try {
                    // Flux fermé ou remplacé (rechargement des paramètres) : on rend la main au ReconnectThread
                    if (serialFileStream !== stream) {
                        AppLogger.i(logTag, "Port série $path fermé — réouverture avec les nouveaux paramètres")
                        break
                    }
                    if (autoBaud && System.currentTimeMillis() - lastValidSentence > AUTO_BAUD_STALE_MS) {
                        AppLogger.w(logTag, "Aucune trame valide depuis ${AUTO_BAUD_STALE_MS / 1000}s sur $path — nouvelle détection du baud rate")
                        break
                    }

                    val size = stream.read(buffer)
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
                            if (line.startsWith("$") && line.length > 10) {
                                AppLogger.trace("NMEA_RAW") { line }
                                if (parseNmeaLine(line)) lastValidSentence = System.currentTimeMillis()
                            }
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeat > 10_000L) {
                        AppLogger.d(logTag, "Thread série actif sur $path | file: ${locationQueue.size()} | RTK: ${if ((now - lastRtkTimestamp) < RTK_PRIORITY_TIMEOUT_MS) "actif" else "inactif"} | socket: ${if (socketConnected) "connecté" else "déconnecté"}")
                        lastHeartbeat = now
                    }

                    Thread.sleep(5)

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (serialFileStream === stream) AppLogger.e(logTag, "Erreur lecture série $path : ${e.message}")
                    break
                }
            }

            AppLogger.w(logTag, "Thread lecture série terminé ($path)")
            try { stream.close() } catch (_: Exception) {}
            if (serialFileStream === stream) serialFileStream = null

        }.apply {
            name = "SerialReadThread"
            isDaemon = true
            start()
        }
    }

    /** Retourne true si la trame est valide (checksum présent et correct), qu'elle soit exploitée ou non. */
    private fun parseNmeaLine(line: String): Boolean {
        if (!Nmea.verifyChecksum(line)) {
            AppLogger.w(logTag, "Checksum absent ou invalide: $line")
            return false
        }
        try {
            when (Nmea.sentenceType(line)) {
                "GGA" -> onGga(line)
                "RMC" -> onRmc(line)
                "GST" -> onGst(line)
            }
        } catch (e: Exception) {
            AppLogger.e(logTag, "Erreur traitement trame NMEA [${e.javaClass.simpleName}]: ${e.message}")
        }
        return true
    }

    private fun onGga(line: String) {
        val gga = Nmea.parseGga(line)
        if (gga == null) {
            AppLogger.w(logTag, "Trame GGA illisible: $line")
            return
        }
        val label = Nmea.qualityLabel(gga.quality)
        if (gga.quality != lastGgaQuality) {
            val previous = lastGgaQuality?.let { Nmea.qualityLabel(it) } ?: "—"
            AppLogger.i(logTag, "Qualité GNSS : $previous → $label | sats=${gga.satellites} hdop=${gga.hdop}")
            lastGgaQuality = gga.quality
        }
        val lat = gga.latitude ?: return
        val lon = gga.longitude ?: return
        if (!gga.hasFix) return

        val accuracy = estimateAccuracy(gga.quality, gga.hdop)
        AppLogger.trace(logTag) {
            "[$label] lat=$lat lon=$lon alt=${gga.altitude}m sats=${gga.satellites} hdop=${gga.hdop} ±${"%.3f".fmt(accuracy)}m"
        }

        updateRtkStatus()

        processLocation(
            PendingLocation(
                latitude = lat,
                longitude = lon,
                altitude = gga.altitude,
                accuracy = accuracy,
                timestamp = gnssTimestamp(gga.time),
                source = "RTK $label",
                speed = currentSpeedMs,
                heading = currentHeading
            )
        )
    }

    private fun onRmc(line: String) {
        val rmc = Nmea.parseRmc(line) ?: return
        currentSpeedMs = rmc.speedMs
        currentHeading = rmc.heading
        rmc.epochMs?.let {
            rmcEpochMs = it
            rmcReceivedAt = System.currentTimeMillis()
        }
        AppLogger.trace(logTag) { "RMC speed=${"%.2f".fmt(rmc.speedMs)}m/s heading=${"%.1f".fmt(rmc.heading)}°" }
    }

    private fun onGst(line: String) {
        val gst = Nmea.parseGst(line) ?: return
        gstHorizontalErrorM = gst.horizontalErrorM
        gstReceivedAt = System.currentTimeMillis()
    }

    /**
     * Précision horizontale (m) : celle de la trame GST si le récepteur en émet,
     * sinon une valeur type selon la qualité du fix, pondérée par le HDOP.
     */
    private fun estimateAccuracy(quality: String, hdop: Double): Float {
        if (System.currentTimeMillis() - gstReceivedAt < GST_MAX_AGE_MS && gstHorizontalErrorM > 0f) {
            return gstHorizontalErrorM
        }
        return Nmea.typicalAccuracy(quality, hdop)
    }

    /**
     * Horodatage du fix : heure UTC de la trame GGA (instant de la mesure, et non de la réception),
     * datée par la dernière RMC ou, à défaut, par l'horloge du téléphone.
     */
    private fun gnssTimestamp(ggaTime: String): Long {
        val now = System.currentTimeMillis()
        val timeOfDay = Nmea.parseTimeOfDay(ggaTime) ?: return now
        val reference = if (rmcEpochMs > 0L && now - rmcReceivedAt < RMC_DATE_MAX_AGE_MS) {
            rmcEpochMs + (now - rmcReceivedAt)
        } else {
            now
        }
        return Nmea.closestTimestamp(timeOfDay, reference)
    }

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

    private fun createNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("GPS Sender RTK")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_gps)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * Les mises à jour de position (jusqu'à 10 Hz) sont limitées à 1/s : au-delà, Android ignore
     * les notify() et le coût CPU est inutile. Les changements d'état passent avec force = true.
     */
    private fun updateNotification(text: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationUpdate < NOTIFICATION_MIN_INTERVAL_MS) return
        lastNotificationUpdate = now
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, createNotification(text))
    }
}

package com.mathieu.gpssender

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object AppPreferences {
    private const val PREF_NAME = "gps_sender_prefs"

    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_BAUD_RATE = "baud_rate"
    private const val KEY_SERIAL_PORT = "serial_port"
    private const val KEY_SEND_INTERVAL_MS = "send_interval_ms"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_API_URL = "api_url"
    private const val KEY_API_KEY = "api_key"

    const val DEFAULT_DEVICE_ID = "CHCNAV_TABLET"

    const val DEFAULT_PORT_AUTO = "AUTO"
    const val DEFAULT_BAUD_RATE = 115200
    const val DEFAULT_SEND_INTERVAL_MS = 1000L

    // Ordre de priorité utilisé pour l'auto-détection du port série RTK
    val SERIAL_PATHS = listOf(
        "/dev/ttyS4",
        "/dev/ttyS3",
        "/dev/ttyS2",
        "/dev/ttyS1",
        "/dev/ttyS0",
        "/dev/tty3",
        "/dev/tty4",
        "/dev/ttyUSB0",
        "/dev/ttyUSB1",
    )

    val AVAILABLE_PORTS = listOf("Auto-détection" to DEFAULT_PORT_AUTO) + SERIAL_PATHS.map { it to it }

    const val BAUD_RATE_AUTO = 0

    val AVAILABLE_BAUD_RATES = listOf(
        BAUD_RATE_AUTO, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600,
    )

    // Ordre d'essai en auto-détection : vitesses les plus courantes des récepteurs GNSS/RTK d'abord
    val AUTO_DETECT_BAUD_ORDER = listOf(115200, 9600, 38400, 57600, 230400, 460800, 921600, 19200)

    fun baudRateLabel(baudRate: Int): String =
        if (baudRate == BAUD_RATE_AUTO) "Auto-détection" else "$baudRate baud"

    val AVAILABLE_INTERVALS = listOf(
        "100 ms (10 Hz)" to 100L,
        "200 ms (5 Hz)" to 200L,
        "500 ms (2 Hz)" to 500L,
        "1000 ms (1 Hz)" to 1000L,
        "2000 ms (0.5 Hz)" to 2000L,
        "5000 ms (0.2 Hz)" to 5000L,
        "10000 ms (10 s)" to 10000L,
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getDeviceId(context: Context): String {
        val saved = getPrefs(context).getString(KEY_DEVICE_ID, null)
        if (!saved.isNullOrBlank()) return saved
        return DEFAULT_DEVICE_ID
    }

    fun setDeviceId(context: Context, id: String) {
        getPrefs(context).edit { putString(KEY_DEVICE_ID, id.trim()) }
    }

    // URL et clé serveur : saisies dans l'appli, avec en repli les valeurs optionnelles de local.properties
    fun getApiUrl(context: Context): String {
        val saved = getPrefs(context).getString(KEY_API_URL, null)
        if (!saved.isNullOrBlank()) return saved
        return BuildConfig.API_URL
    }

    fun setApiUrl(context: Context, url: String) {
        getPrefs(context).edit { putString(KEY_API_URL, url.trim()) }
    }

    fun getApiKey(context: Context): String {
        val saved = getPrefs(context).getString(KEY_API_KEY, null)
        if (!saved.isNullOrBlank()) return saved
        return BuildConfig.API_KEY
    }

    fun setApiKey(context: Context, key: String) {
        getPrefs(context).edit { putString(KEY_API_KEY, key.trim()) }
    }

    fun getBaudRate(context: Context): Int {
        return getPrefs(context).getInt(KEY_BAUD_RATE, DEFAULT_BAUD_RATE)
    }

    fun setBaudRate(context: Context, baudRate: Int) {
        getPrefs(context).edit { putInt(KEY_BAUD_RATE, baudRate) }
    }

    fun getSerialPort(context: Context): String {
        return getPrefs(context).getString(KEY_SERIAL_PORT, DEFAULT_PORT_AUTO) ?: DEFAULT_PORT_AUTO
    }

    fun setSerialPort(context: Context, port: String) {
        getPrefs(context).edit { putString(KEY_SERIAL_PORT, port.trim()) }
    }

    fun getSendIntervalMs(context: Context): Long {
        return getPrefs(context).getLong(KEY_SEND_INTERVAL_MS, DEFAULT_SEND_INTERVAL_MS)
    }

    fun setSendIntervalMs(context: Context, intervalMs: Long) {
        getPrefs(context).edit { putLong(KEY_SEND_INTERVAL_MS, intervalMs) }
    }

    /**
     * Intention de l'utilisateur : le service doit-il tourner ?
     * Consulté par le BootReceiver et le watchdog pour ne pas relancer un service arrêté volontairement.
     * Vrai par défaut pour conserver le démarrage automatique au boot.
     */
    fun isServiceEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SERVICE_ENABLED, true)
    }

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit { putBoolean(KEY_SERVICE_ENABLED, enabled) }
    }
}

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

    const val DEFAULT_PORT_AUTO = "AUTO"
    const val DEFAULT_BAUD_RATE = 115200
    const val DEFAULT_SEND_INTERVAL_MS = 1000L

    val AVAILABLE_PORTS = listOf(
        "Auto-détection" to DEFAULT_PORT_AUTO,
        "/dev/ttyS4" to "/dev/ttyS4",
        "/dev/ttyS3" to "/dev/ttyS3",
        "/dev/ttyS2" to "/dev/ttyS2",
        "/dev/ttyS1" to "/dev/ttyS1",
        "/dev/ttyS0" to "/dev/ttyS0",
        "/dev/tty3" to "/dev/tty3",
        "/dev/tty4" to "/dev/tty4",
        "/dev/ttyUSB0" to "/dev/ttyUSB0",
        "/dev/ttyUSB1" to "/dev/ttyUSB1",
    )

    val AVAILABLE_BAUD_RATES = listOf(
        9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600,
    )

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
        return BuildConfig.DEVICE_ID.ifBlank { "CHCNAV_TABLET" }
    }

    fun setDeviceId(context: Context, id: String) {
        getPrefs(context).edit { putString(KEY_DEVICE_ID, id.trim()) }
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
}

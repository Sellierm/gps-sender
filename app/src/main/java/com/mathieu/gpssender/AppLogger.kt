package com.mathieu.gpssender

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object AppLogger {
    private const val MAX_LOGS = 1000

    // DateTimeFormatter est thread-safe (contrairement à SimpleDateFormat)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val logBuffer = ArrayDeque<String>(MAX_LOGS)
    private val lock = Any()

    interface LogListener {
        fun onLogAdded(logLine: String)
        fun onLogsCleared()
    }

    @Volatile
    private var listener: LogListener? = null

    fun setListener(listener: LogListener?) {
        this.listener = listener
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append("W", tag, msg)
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, msg, throwable)
            append("E", tag, "$msg : ${throwable.localizedMessage}")
        } else {
            Log.e(tag, msg)
            append("E", tag, msg)
        }
    }

    private fun append(level: String, tag: String, msg: String) {
        val timestamp = LocalTime.now().format(timeFormat)
        val logLine = "[$timestamp][$level][$tag] $msg"

        synchronized(lock) {
            if (logBuffer.size >= MAX_LOGS) logBuffer.removeFirst()
            logBuffer.addLast(logLine)
        }

        listener?.onLogAdded(logLine)
    }

    fun getAllLogs(): List<String> = synchronized(lock) { logBuffer.toList() }

    fun getFormattedLogs(): String = synchronized(lock) { logBuffer.joinToString("\n") }

    fun clear() {
        synchronized(lock) { logBuffer.clear() }
        listener?.onLogsCleared()
    }
}

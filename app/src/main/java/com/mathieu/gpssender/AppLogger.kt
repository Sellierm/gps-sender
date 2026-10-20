package com.mathieu.gpssender

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object AppLogger {
    private const val MAX_LOGS = 1000
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val logQueue = ConcurrentLinkedQueue<String>()

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
        val timestamp = timeFormat.format(Date())
        val logLine = "[$timestamp][$level][$tag] $msg"

        logQueue.add(logLine)
        while (logQueue.size > MAX_LOGS) {
            logQueue.poll()
        }

        listener?.onLogAdded(logLine)
    }

    fun getAllLogs(): List<String> {
        return logQueue.toList()
    }

    fun getFormattedLogs(): String {
        return logQueue.joinToString("\n")
    }

    fun clear() {
        logQueue.clear()
        listener?.onLogsCleared()
    }
}

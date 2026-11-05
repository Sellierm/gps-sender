package com.mathieu.gpssender

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Journal de l'appli : Logcat + tampon mémoire (écran Logs) + fichier tournant.
 *
 * Le fichier ([LOG_FILE_NAME], et l'ancien dans [LOG_FILE_NAME].1) est dans le stockage propre
 * à l'appli, récupérable même sur une version release :
 * `adb pull /sdcard/Android/data/com.mathieu.gpssender/files/logs/`
 *
 * Les messages à haute fréquence (trames NMEA brutes, positions écartées…) passent par [trace] :
 * Logcat uniquement, et seulement en debug, pour ne pas noyer le tampon ni le fichier.
 */
object AppLogger {
    private const val MAX_LOGS = 1000
    private const val LOG_FILE_NAME = "gps-sender.log"
    // 2 fichiers de 5 Mo : de quelques heures (envoi à 10 Hz) à plus d'une journée (1 Hz)
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val fileTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val logBuffer = ArrayDeque<String>(MAX_LOGS)
    private val lock = Any()

    private val fileExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LogFileWriter").apply { isDaemon = true }
    }
    private val pendingFileWrites = AtomicInteger(0)
    @Volatile private var logFile: File? = null
    private var writer: BufferedWriter? = null

    interface LogListener {
        fun onLogAdded(logLine: String)
        fun onLogsCleared()
    }

    @Volatile
    private var listener: LogListener? = null

    /** À appeler au démarrage du process (voir [GpsSenderApp]). */
    fun init(context: Context) {
        if (logFile != null) return
        val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
        dir.mkdirs()
        logFile = File(dir, LOG_FILE_NAME)
        i("GPS_SENDER_LOG", "Journal enregistré dans ${logFile?.absolutePath}")
    }

    fun setListener(listener: LogListener?) {
        this.listener = listener
    }

    /** Message à haute fréquence : Logcat seulement, en debug. Le message n'est construit que si besoin. */
    inline fun trace(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg())
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
        val now = LocalDateTime.now()
        val logLine = "[${now.toLocalTime().format(timeFormat)}][$level][$tag] $msg"

        synchronized(lock) {
            if (logBuffer.size >= MAX_LOGS) logBuffer.removeFirst()
            logBuffer.addLast(logLine)
        }

        listener?.onLogAdded(logLine)
        writeToFile("[${now.format(fileTimeFormat)}][$level][$tag] $msg")
    }

    private fun writeToFile(line: String) {
        val file = logFile ?: return
        pendingFileWrites.incrementAndGet()
        fileExecutor.execute {
            try {
                val w = writer ?: BufferedWriter(FileWriter(file, true)).also { writer = it }
                w.write(line)
                w.newLine()
                // Flush groupé : une seule écriture disque pour une rafale de lignes
                if (pendingFileWrites.decrementAndGet() == 0) {
                    w.flush()
                    if (file.length() > MAX_FILE_BYTES) rotate(file)
                }
            } catch (e: Exception) {
                Log.e("GPS_SENDER_LOG", "Écriture du journal impossible", e)
                try { writer?.close() } catch (_: Exception) {}
                writer = null
            }
        }
    }

    private fun rotate(file: File) {
        writer?.close()
        writer = null
        val previous = File(file.parentFile, "${file.name}.1")
        previous.delete()
        file.renameTo(previous)
    }

    fun getAllLogs(): List<String> = synchronized(lock) { logBuffer.toList() }

    fun getFormattedLogs(): String = synchronized(lock) { logBuffer.joinToString("\n") }

    fun clear() {
        synchronized(lock) { logBuffer.clear() }
        listener?.onLogsCleared()
    }
}

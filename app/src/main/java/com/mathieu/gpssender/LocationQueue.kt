package com.mathieu.gpssender

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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

/** Positions lues dans la file, à supprimer avec [LocationQueue.removeUpTo] une fois acquittées. */
data class QueuedBatch(val lastId: Long, val locations: List<PendingLocation>)

/*
 * File d'attente persistante (SQLite) des positions à envoyer.
 * Survit à l'arrêt du service par Android et couvre plusieurs heures de coupure réseau :
 * une position n'en sort qu'après l'ack du serveur, qui ne l'envoie qu'une fois écrite en base.
 */
class LocationQueue(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "location_queue.db", null, 1) {

    companion object {
        // ~8 h à 10 Hz (~25 Mo) ; au-delà, les positions les plus anciennes sont supprimées
        const val MAX_SIZE = 300_000
        private const val TRIM_CHUNK = 1_000
        private const val TABLE = "queue"
    }

    private val logTag = "GPS_SENDER_LOG"
    private var cachedSize = -1

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "latitude REAL NOT NULL, longitude REAL NOT NULL, altitude REAL NOT NULL, " +
                    "accuracy REAL NOT NULL, speed REAL NOT NULL, heading REAL NOT NULL, " +
                    "timestamp INTEGER NOT NULL, source TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun size(): Int {
        if (cachedSize < 0) {
            cachedSize = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        }
        return cachedSize
    }

    @Synchronized
    fun add(location: PendingLocation) {
        if (size() >= MAX_SIZE) {
            val removed = writableDatabase.delete(
                TABLE, "id IN (SELECT id FROM $TABLE ORDER BY id LIMIT $TRIM_CHUNK)", null
            )
            cachedSize -= removed
            AppLogger.w(logTag, "File pleine ($MAX_SIZE) : $removed positions les plus anciennes supprimées")
        }
        val values = ContentValues().apply {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("altitude", location.altitude)
            put("accuracy", location.accuracy)
            put("speed", location.speed)
            put("heading", location.heading)
            put("timestamp", location.timestamp)
            put("source", location.source)
        }
        if (writableDatabase.insert(TABLE, null, values) != -1L) cachedSize++
    }

    /** Les [limit] positions les plus anciennes, sans les retirer de la file. */
    @Synchronized
    fun peek(limit: Int): QueuedBatch? {
        val locations = mutableListOf<PendingLocation>()
        var lastId = -1L
        readableDatabase.rawQuery(
            "SELECT id, latitude, longitude, altitude, accuracy, speed, heading, timestamp, source " +
                    "FROM $TABLE ORDER BY id LIMIT $limit", null
        ).use { c ->
            while (c.moveToNext()) {
                lastId = c.getLong(0)
                locations += PendingLocation(
                    latitude = c.getDouble(1),
                    longitude = c.getDouble(2),
                    altitude = c.getDouble(3),
                    accuracy = c.getFloat(4),
                    speed = c.getFloat(5),
                    heading = c.getFloat(6),
                    timestamp = c.getLong(7),
                    source = c.getString(8)
                )
            }
        }
        return if (locations.isEmpty()) null else QueuedBatch(lastId, locations)
    }

    @Synchronized
    fun removeUpTo(lastId: Long) {
        val removed = writableDatabase.delete(TABLE, "id <= ?", arrayOf(lastId.toString()))
        if (cachedSize >= 0) cachedSize = maxOf(0, cachedSize - removed)
    }
}

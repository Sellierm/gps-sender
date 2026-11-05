package com.mathieu.gpssender

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Décodage des trames NMEA 0183 (GGA, RMC, GST), sans état ni dépendance Android :
 * testable en test unitaire JVM. L'état courant (dernière RMC, dernière GST…) reste dans le service.
 */
object Nmea {

    const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val KNOTS_TO_MS = 0.514444

    /** Trame GGA. [latitude] et [longitude] sont nulles sans fix. */
    data class Gga(
        val time: String,
        val latitude: Double?,
        val longitude: Double?,
        val quality: String,
        val satellites: String,
        val hdop: Double,
        val altitude: Double,
    ) {
        val hasFix: Boolean get() = quality != "0" && latitude != null && longitude != null
    }

    /** Trame RMC valide (statut A). [epochMs] : date + heure UTC, nulle si la date ou l'heure manque. */
    data class Rmc(val speedMs: Float, val heading: Float, val epochMs: Long?)

    data class Gst(val horizontalErrorM: Float)

    /** Vrai si la trame porte un checksum (`*hh`) correct. Une trame sans checksum est refusée. */
    fun verifyChecksum(sentence: String): Boolean {
        val start = sentence.indexOf('$') + 1
        val end = sentence.indexOf('*')
        if (start < 1 || end < 0 || end <= start || sentence.length < end + 3) return false
        val expected = sentence.substring(end + 1, end + 3).toIntOrNull(16) ?: return false
        var actual = 0
        for (i in start until end) actual = actual xor sentence[i].code
        return actual == expected
    }

    /** Type de trame : "GGA" pour "$GNGGA,…" (talker sur 2 caractères), null si l'en-tête est trop court. */
    fun sentenceType(line: String): String? =
        if (line.length >= 6 && line[0] == '$') line.substring(3, 6) else null

    private fun fields(line: String): List<String> = line.substringBefore('*').split(',')

    fun parseGga(line: String): Gga? {
        val parts = fields(line)
        if (parts.size < 10) return null
        return try {
            val hasPosition = parts[2].isNotEmpty() && parts[4].isNotEmpty()
            Gga(
                time = parts[1],
                latitude = if (hasPosition) toDecimalDegrees(parts[2], parts[3]) else null,
                longitude = if (hasPosition) toDecimalDegrees(parts[4], parts[5]) else null,
                quality = parts[6].trim().ifEmpty { "0" },
                satellites = parts[7].trim(),
                hdop = parts[8].toDoubleOrNull() ?: 0.0,
                altitude = parts[9].toDoubleOrNull() ?: 0.0,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun parseRmc(line: String): Rmc? {
        val parts = fields(line)
        if (parts.size < 9 || parts[2] != "A") return null
        val speedKnots = parts[7].toDoubleOrNull() ?: return null
        val heading = parts[8].toDoubleOrNull() ?: 0.0
        val timeOfDay = parseTimeOfDay(parts[1])
        val dayStart = if (parts.size > 9) parseDate(parts[9]) else null
        return Rmc(
            speedMs = (speedKnots * KNOTS_TO_MS).toFloat(),
            heading = heading.toFloat(),
            epochMs = if (timeOfDay != null && dayStart != null) dayStart + timeOfDay else null,
        )
    }

    fun parseGst(line: String): Gst? {
        val parts = fields(line)
        if (parts.size < 8) return null
        val latErr = parts[6].toDoubleOrNull() ?: return null
        val lonErr = parts[7].toDoubleOrNull() ?: return null
        return Gst(sqrt(latErr * latErr + lonErr * lonErr).toFloat())
    }

    fun qualityLabel(quality: String): String = when (quality) {
        "0" -> "Pas de fix"
        "1" -> "GPS"
        "2" -> "DGPS"
        "4" -> "RTK Fix"
        "5" -> "RTK Float"
        else -> "Fix=$quality"
    }

    fun typicalAccuracy(quality: String, hdop: Double): Float {
        val typical = when (quality) {
            "4" -> 0.02f
            "5" -> 0.30f
            "2" -> 1.0f
            else -> 5.0f
        }
        return if (hdop > 0.0) typical * hdop.toFloat() else typical
    }

    /** "ddmm.mmmm" / "dddmm.mmmm" + direction → degrés décimaux. */
    fun toDecimalDegrees(value: String, direction: String): Double {
        val dotIdx = value.indexOf('.')
        require(dotIdx >= 2) { "Format NMEA invalide: $value" }
        val degrees = value.substring(0, dotIdx - 2).ifEmpty { "0" }.toDouble()
        val minutes = value.substring(dotIdx - 2).toDouble()
        val result = degrees + minutes / 60.0
        return if (direction == "S" || direction == "W") -result else result
    }

    /** "hhmmss.ss" → millisecondes depuis minuit UTC. */
    fun parseTimeOfDay(value: String): Long? {
        if (value.length < 6) return null
        val hours = value.substring(0, 2).toIntOrNull() ?: return null
        val minutes = value.substring(2, 4).toIntOrNull() ?: return null
        val seconds = value.substring(4).toDoubleOrNull() ?: return null
        if (hours > 23 || minutes > 59 || seconds < 0.0 || seconds >= 61.0) return null
        return (hours * 3600 + minutes * 60) * 1000L + (seconds * 1000).roundToLong()
    }

    /** "ddmmyy" → minuit UTC de ce jour, en ms epoch (années 2000 à 2099). */
    fun parseDate(value: String): Long? {
        if (value.length != 6) return null
        return try {
            LocalDate.of(
                2000 + value.substring(4, 6).toInt(),
                value.substring(2, 4).toInt(),
                value.substring(0, 2).toInt()
            ).toEpochDay() * DAY_MS
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Horodatage d'un fix à partir de son heure UTC ([timeOfDayMs]) : on retient le jour
     * (veille, même jour ou lendemain) qui le place au plus près de [referenceMs].
     * Gère le passage de minuit entre la référence (RMC ou horloge du téléphone) et la GGA.
     */
    fun closestTimestamp(timeOfDayMs: Long, referenceMs: Long): Long {
        val dayStart = Math.floorDiv(referenceMs, DAY_MS) * DAY_MS
        return longArrayOf(dayStart - DAY_MS, dayStart, dayStart + DAY_MS)
            .map { it + timeOfDayMs }
            .minBy { abs(it - referenceMs) }
    }
}

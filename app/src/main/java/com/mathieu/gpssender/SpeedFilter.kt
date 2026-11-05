package com.mathieu.gpssender

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object SpeedFilter {

    const val MAX_SPEED_KMH = 200.0
    // Au-delà de cet écart, la position précédente est trop ancienne pour juger d'une vitesse :
    // la nouvelle est acceptée. Sans cette limite, une position de référence fausse ou ancienne
    // (appareil déplacé éteint) bloquerait tous les fix suivants pendant des heures.
    const val MAX_GAP_MS = 60_000L

    /** Vitesse apparente (km/h) entre deux positions, incertitudes retranchées ; null si non mesurable. */
    fun apparentSpeedKmh(last: PendingLocation, next: PendingLocation): Double? {
        val deltaMs = next.timestamp - last.timestamp
        if (deltaMs <= 0 || deltaMs > MAX_GAP_MS) return null
        // On retranche l'incertitude des deux positions : un saut d'une position réseau (±400 m)
        // vers un fix GPS précis n'est pas un déplacement réel et ne doit pas bloquer les fix suivants
        val uncertaintyM = last.accuracy + next.accuracy
        val distanceM = (haversineDistance(last.latitude, last.longitude, next.latitude, next.longitude) - uncertaintyM)
            .coerceAtLeast(0.0)
        return (distanceM / deltaMs) * 3600.0
    }

    fun isAberrant(last: PendingLocation, next: PendingLocation): Boolean =
        (apparentSpeedKmh(last, next) ?: 0.0) > MAX_SPEED_KMH

    fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

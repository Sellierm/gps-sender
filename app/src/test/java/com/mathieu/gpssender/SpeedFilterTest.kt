package com.mathieu.gpssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedFilterTest {

    private fun at(lat: Double, lon: Double, timestamp: Long, accuracy: Float = 0.02f) =
        PendingLocation(lat, lon, 0.0, accuracy, timestamp, "test")

    // 0,001° de latitude ≈ 111 m
    private val paris = at(48.8566, 2.3522, 1_000_000L)

    @Test
    fun `distance haversine`() {
        // Paris → Lyon : ~392 km
        assertEquals(392_000.0, SpeedFilter.haversineDistance(48.8566, 2.3522, 45.7640, 4.8357), 2_000.0)
    }

    @Test
    fun `deplacement normal accepte`() {
        // ~111 m en 10 s = 40 km/h
        assertFalse(SpeedFilter.isAberrant(paris, at(48.8576, 2.3522, paris.timestamp + 10_000)))
    }

    @Test
    fun `saut impossible rejete`() {
        // ~1,1 km en 1 s = ~4 000 km/h
        assertTrue(SpeedFilter.isAberrant(paris, at(48.8666, 2.3522, paris.timestamp + 1_000)))
    }

    @Test
    fun `incertitude des positions retranchee`() {
        // ~1,1 km en 10 s, mais position précédente réseau à ±1 km : déplacement réel ~100 m
        val reseau = paris.copy(accuracy = 1_000f)
        assertFalse(SpeedFilter.isAberrant(reseau, at(48.8666, 2.3522, paris.timestamp + 10_000)))
    }

    @Test
    fun `position de reference trop ancienne ignoree`() {
        // Appareil déplacé de 400 km : la position précédente date de plus d'une minute, on accepte
        val lyon = at(45.7640, 4.8357, paris.timestamp + SpeedFilter.MAX_GAP_MS + 1)
        assertNull(SpeedFilter.apparentSpeedKmh(paris, lyon))
        assertFalse(SpeedFilter.isAberrant(paris, lyon))
    }

    @Test
    fun `horodatage anterieur ignore`() {
        assertFalse(SpeedFilter.isAberrant(paris, at(45.7640, 4.8357, paris.timestamp - 1_000)))
    }
}

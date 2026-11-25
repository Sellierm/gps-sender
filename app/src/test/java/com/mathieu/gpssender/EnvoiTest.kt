package com.mathieu.gpssender

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Thread d'envoi : retrait de la file seulement après l'ack, lecture de l'ack, délais de nouvel essai. */
@RunWith(RobolectricTestRunner::class)
// Application vide : GpsSenderApp programme WorkManager au démarrage, inutile ici
@Config(application = android.app.Application::class)
class EnvoiTest {

    private lateinit var queue: LocationQueue

    private fun position(i: Int) = PendingLocation(
        latitude = 49.0 + i * 1e-6,
        longitude = 2.0,
        altitude = 100.0,
        accuracy = 0.02f,
        timestamp = 1_764_000_000_000L + i * 100L,
        source = "RTK RTK Fix"
    )

    @Before
    fun setUp() {
        queue = LocationQueue(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        queue.close()
    }

    // --- sendNext ---------------------------------------------------------------

    @Test
    fun `file vide rien n'est envoye`() {
        var appels = 0
        val r = Envoi.sendNext(queue, 50) { appels++; true }
        assertEquals(Envoi.Resultat.FILE_VIDE, r)
        assertEquals(0, appels)
    }

    @Test
    fun `envoi acquitte le lot quitte la file`() {
        repeat(3) { queue.add(position(it)) }
        val envoyes = mutableListOf<PendingLocation>()

        val r = Envoi.sendNext(queue, 50) { envoyes += it.locations; true }

        assertEquals(Envoi.Resultat.ENVOYE, r)
        assertEquals((0..2).map { position(it) }, envoyes)
        assertEquals(0, queue.size())
    }

    @Test
    fun `echec le lot reste en file et repart a l'identique`() {
        repeat(3) { queue.add(position(it)) }
        val essais = mutableListOf<List<PendingLocation>>()

        assertEquals(Envoi.Resultat.ECHEC, Envoi.sendNext(queue, 50) { essais += it.locations; false })
        assertEquals(3, queue.size())
        assertEquals(Envoi.Resultat.ENVOYE, Envoi.sendNext(queue, 50) { essais += it.locations; true })

        assertEquals(2, essais.size)
        assertEquals(essais[0], essais[1])
        assertEquals(0, queue.size())
    }

    @Test
    fun `positions arrivees pendant l'envoi gardees pour le lot suivant`() {
        repeat(2) { queue.add(position(it)) }

        Envoi.sendNext(queue, 50) {
            queue.add(position(2))
            queue.add(position(3))
            true
        }

        assertEquals(listOf(position(2), position(3)), queue.peek(50)!!.locations)
    }

    @Test
    fun `lots de taille limitee dans l'ordre chronologique`() {
        repeat(7) { queue.add(position(it)) }
        val lots = mutableListOf<List<Long>>()

        while (Envoi.sendNext(queue, 3) { lots += it.locations.map { p -> p.timestamp }; true } ==
            Envoi.Resultat.ENVOYE
        ) Unit

        assertEquals(listOf(3, 3, 1), lots.map { it.size })
        val tous = lots.flatten()
        assertEquals(tous.sorted(), tous)
        assertEquals((0..6).map { position(it).timestamp }, tous)
    }

    @Test
    fun `exception pendant l'envoi le lot reste en file`() {
        queue.add(position(0))
        try {
            Envoi.sendNext(queue, 50) { throw IllegalStateException("socket fermé") }
        } catch (_: IllegalStateException) {
        }
        assertEquals(1, queue.size())
    }

    // --- retryDelayMs -----------------------------------------------------------

    @Test
    fun `delais de nouvel essai 2 4 8 puis 16 s`() {
        assertEquals(2_000L, Envoi.retryDelayMs(1))
        assertEquals(4_000L, Envoi.retryDelayMs(2))
        assertEquals(8_000L, Envoi.retryDelayMs(3))
        assertEquals(16_000L, Envoi.retryDelayMs(4))
        assertEquals(16_000L, Envoi.retryDelayMs(50))
        assertEquals(16_000L, Envoi.retryDelayMs(Int.MAX_VALUE))
        assertEquals(2_000L, Envoi.retryDelayMs(0))
    }

    // --- pointsJson -------------------------------------------------------------

    @Test
    fun `points au format du serveur`() {
        val p = PendingLocation(49.5, -1.25, 87.5, 0.014f, 1_764_012_345_678L, "GPS interne", 2.5f, 180f)
        val points = Envoi.pointsJson(listOf(p, position(1)))

        assertEquals(2, points.length())
        val o = points.getJSONObject(0)
        assertEquals(
            setOf("latitude", "longitude", "altitude", "accuracy", "speed", "heading", "timestamp", "source"),
            o.keys().asSequence().toSet()
        )
        assertEquals(49.5, o.getDouble("latitude"), 0.0)
        assertEquals(-1.25, o.getDouble("longitude"), 0.0)
        assertEquals(87.5, o.getDouble("altitude"), 0.0)
        assertEquals(0.014, o.getDouble("accuracy"), 1e-6)
        assertEquals(2.5, o.getDouble("speed"), 1e-6)
        assertEquals(180.0, o.getDouble("heading"), 1e-6)
        assertEquals(1_764_012_345_678L, o.getLong("timestamp"))
        assertEquals("GPS interne", o.getString("source"))
    }

    @Test
    fun `lot vide donne un tableau vide`() {
        assertEquals(0, Envoi.pointsJson(emptyList()).length())
    }

    // --- BatchResult.fromAck ----------------------------------------------------

    @Test
    fun `ack accepte avec positions rejetees`() {
        val r = LocationSocketClient.BatchResult.fromAck(
            JSONObject("""{"success":true,"accepted":47,"rejected":3,"message":"3 positions invalides"}""")
        )
        assertTrue(r.success)
        assertEquals(3, r.rejected)
        assertEquals("3 positions invalides", r.message)
    }

    @Test
    fun `ack de succes minimal`() {
        val r = LocationSocketClient.BatchResult.fromAck(JSONObject("""{"success":true}"""))
        assertTrue(r.success)
        assertEquals(0, r.rejected)
        assertNull(r.message)
    }

    @Test
    fun `ack d'echec du serveur`() {
        val r = LocationSocketClient.BatchResult.fromAck(
            JSONObject("""{"success":false,"message":"erreur base"}""")
        )
        assertFalse(r.success)
        assertEquals("erreur base", r.message)
    }

    @Test
    fun `ack absent ou sans success compte comme un echec`() {
        assertFalse(LocationSocketClient.BatchResult.fromAck(null).success)
        assertFalse(LocationSocketClient.BatchResult.fromAck(JSONObject()).success)
        assertFalse(LocationSocketClient.BatchResult.fromAck(JSONObject("""{"accepted":50}""")).success)
        assertFalse(LocationSocketClient.BatchResult.fromAck(JSONObject("""{"success":"oui"}""")).success)
    }

    @Test
    fun `message vide ignore`() {
        assertNull(LocationSocketClient.BatchResult.fromAck(JSONObject("""{"success":true,"message":""}""")).message)
    }
}

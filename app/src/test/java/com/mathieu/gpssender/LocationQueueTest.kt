package com.mathieu.gpssender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** File SQLite réelle (Robolectric) : rien ne doit se perdre ni se dupliquer avant l'ack. */
@RunWith(RobolectricTestRunner::class)
// Application vide : GpsSenderApp programme WorkManager au démarrage, inutile ici
@Config(application = android.app.Application::class)
class LocationQueueTest {

    private lateinit var context: Context
    private val queues = mutableListOf<LocationQueue>()

    private fun queue(maxSize: Int = LocationQueue.MAX_SIZE, trimChunk: Int = 1_000) =
        LocationQueue(context, maxSize, trimChunk).also { queues += it }

    private fun position(i: Int) = PendingLocation(
        latitude = 49.0 + i * 1e-6,
        longitude = 2.0 + i * 1e-6,
        altitude = 100.0 + i,
        accuracy = 0.02f,
        timestamp = 1_764_000_000_000L + i * 100L,
        source = "RTK RTK Fix",
        speed = 1.5f,
        heading = 90f
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        queues.forEach { it.close() }
    }

    @Test
    fun `file vide`() {
        val q = queue()
        assertEquals(0, q.size())
        assertNull(q.peek(50))
    }

    @Test
    fun `tous les champs sont conserves`() {
        val q = queue()
        val p = PendingLocation(
            latitude = 49.123456789012,
            longitude = -1.987654321098,
            altitude = 87.654,
            accuracy = 0.014f,
            timestamp = 1_764_012_345_678L,
            source = "RTK RTK Float",
            speed = 2.75f,
            heading = 359.5f
        )
        q.add(p)
        assertEquals(listOf(p), q.peek(10)!!.locations)
    }

    @Test
    fun `lecture dans l'ordre d'arrivee et limitee`() {
        val q = queue()
        repeat(5) { q.add(position(it)) }

        val batch = q.peek(3)!!
        assertEquals((0..2).map { position(it) }, batch.locations)
        // peek ne retire rien
        assertEquals(5, q.size())
        assertEquals(batch, q.peek(3))
    }

    @Test
    fun `retrait jusqu'au dernier id du lot seulement`() {
        val q = queue()
        repeat(5) { q.add(position(it)) }

        val batch = q.peek(3)!!
        q.removeUpTo(batch.lastId)

        assertEquals(2, q.size())
        assertEquals(listOf(position(3), position(4)), q.peek(10)!!.locations)
    }

    @Test
    fun `positions ajoutees apres la lecture du lot conservees`() {
        val q = queue()
        repeat(3) { q.add(position(it)) }
        val batch = q.peek(50)!!

        // Arrivent pendant l'envoi du lot
        q.add(position(3))
        q.add(position(4))
        q.removeUpTo(batch.lastId)

        assertEquals(listOf(position(3), position(4)), q.peek(50)!!.locations)
        assertEquals(2, q.size())
    }

    @Test
    fun `retrait repete ou sur file vide sans effet`() {
        val q = queue()
        repeat(2) { q.add(position(it)) }
        val batch = q.peek(50)!!
        q.removeUpTo(batch.lastId)
        q.removeUpTo(batch.lastId)
        q.removeUpTo(Long.MAX_VALUE)
        assertEquals(0, q.size())
        assertNull(q.peek(50))

        q.add(position(9))
        assertEquals(1, q.size())
    }

    @Test
    fun `la file survit a la fermeture (service tue par Android)`() {
        val q1 = queue()
        repeat(4) { q1.add(position(it)) }
        q1.removeUpTo(q1.peek(1)!!.lastId)
        q1.close()

        val q2 = queue()
        assertEquals(3, q2.size())
        assertEquals((1..3).map { position(it) }, q2.peek(50)!!.locations)
    }

    @Test
    fun `les ids continuent apres reouverture (pas de reutilisation)`() {
        val q1 = queue()
        repeat(2) { q1.add(position(it)) }
        val firstLastId = q1.peek(50)!!.lastId
        q1.removeUpTo(firstLastId)
        q1.close()

        val q2 = queue()
        q2.add(position(2))
        val batch = q2.peek(50)!!
        assertEquals(listOf(position(2)), batch.locations)
        assertTrue("id réutilisé : ${batch.lastId} <= $firstLastId", batch.lastId > firstLastId)
    }

    @Test
    fun `file pleine les plus anciennes sont supprimees`() {
        val q = queue(maxSize = 5, trimChunk = 2)
        repeat(5) { q.add(position(it)) }
        assertEquals(5, q.size())

        q.add(position(5))
        // 2 plus anciennes supprimées, puis ajout
        assertEquals(4, q.size())
        assertEquals((2..5).map { position(it) }, q.peek(50)!!.locations)
    }

    @Test
    fun `compteur coherent avec la base apres reouverture`() {
        val q1 = queue(maxSize = 3, trimChunk = 1)
        repeat(10) { q1.add(position(it)) }
        assertEquals(3, q1.size())
        q1.close()

        val q2 = queue(maxSize = 3, trimChunk = 1)
        assertEquals(3, q2.size())
        val batch = q2.peek(50)
        assertNotNull(batch)
        assertEquals((7..9).map { position(it) }, batch!!.locations)
    }
}

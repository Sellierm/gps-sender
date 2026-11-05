package com.mathieu.gpssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NmeaTest {

    // Trames d'exemple de référence de la norme NMEA 0183 (checksums connus)
    private val gga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
    private val rmc = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"

    /** Ajoute le checksum à un corps de trame (sans "$" ni "*"), pour construire des cas de test. */
    private fun sentence(body: String): String {
        val checksum = body.fold(0) { acc, c -> acc xor c.code }
        return "\$$body*%02X".format(checksum)
    }

    private fun epoch(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun <T> notNull(value: T?): T {
        assertTrue("valeur nulle inattendue", value != null)
        return value!!
    }

    @Test
    fun `checksum correct accepte`() {
        assertTrue(Nmea.verifyChecksum(gga))
        assertTrue(Nmea.verifyChecksum(rmc))
    }

    @Test
    fun `checksum en minuscules accepte`() {
        assertTrue(Nmea.verifyChecksum(rmc.replace("*6A", "*6a")))
    }

    @Test
    fun `checksum faux refuse`() {
        assertFalse(Nmea.verifyChecksum(gga.replace("*47", "*48")))
    }

    @Test
    fun `trame modifiee refusee`() {
        assertFalse(Nmea.verifyChecksum(gga.replace("4807.038", "4807.039")))
    }

    @Test
    fun `trame sans checksum refusee`() {
        assertFalse(Nmea.verifyChecksum(gga.substringBefore('*')))
    }

    @Test
    fun `checksum tronque ou illisible refuse`() {
        assertFalse(Nmea.verifyChecksum(gga.dropLast(1)))
        assertFalse(Nmea.verifyChecksum(gga.replace("*47", "*")))
        assertFalse(Nmea.verifyChecksum(gga.replace("*47", "*ZZ")))
    }

    @Test
    fun `type de trame lu apres le talker`() {
        assertEquals("GGA", Nmea.sentenceType(gga))
        assertEquals("RMC", Nmea.sentenceType("\$GNRMC,"))
        assertNull(Nmea.sentenceType("\$GN"))
    }

    @Test
    fun `conversion degres minutes vers decimal`() {
        assertEquals(48.1173, Nmea.toDecimalDegrees("4807.038", "N"), 1e-9)
        assertEquals(11.516666666, Nmea.toDecimalDegrees("01131.000", "E"), 1e-8)
    }

    @Test
    fun `sud et ouest negatifs`() {
        assertEquals(-48.1173, Nmea.toDecimalDegrees("4807.038", "S"), 1e-9)
        assertEquals(-11.516666666, Nmea.toDecimalDegrees("01131.000", "W"), 1e-8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `coordonnee mal formee rejetee`() {
        Nmea.toDecimalDegrees("4807", "N")
    }

    @Test
    fun `GGA complete`() {
        val parsed = notNull(Nmea.parseGga(gga))
        assertEquals("123519", parsed.time)
        assertEquals(48.1173, parsed.latitude!!, 1e-9)
        assertEquals(11.516666666, parsed.longitude!!, 1e-8)
        assertEquals("1", parsed.quality)
        assertEquals("08", parsed.satellites)
        assertEquals(0.9, parsed.hdop, 1e-9)
        assertEquals(545.4, parsed.altitude, 1e-9)
        assertTrue(parsed.hasFix)
    }

    @Test
    fun `GGA sans fix`() {
        val parsed = notNull(Nmea.parseGga(sentence("GNGGA,123519.00,,,,,0,00,99.99,,,,,,")))
        assertEquals("0", parsed.quality)
        assertNull(parsed.latitude)
        assertFalse(parsed.hasFix)
    }

    @Test
    fun `GGA RTK Fix`() {
        val parsed = notNull(Nmea.parseGga(sentence("GNGGA,083000.10,4851.12345678,N,00221.98765432,E,4,24,0.6,35.120,M,47.0,M,1.0,0001")))
        assertEquals("4", parsed.quality)
        assertEquals("RTK Fix", Nmea.qualityLabel(parsed.quality))
        assertEquals(48 + 51.12345678 / 60, parsed.latitude!!, 1e-12)
    }

    @Test
    fun `GGA tronquee ou illisible`() {
        assertNull(Nmea.parseGga(sentence("GPGGA,123519,4807.038,N")))
        assertNull(Nmea.parseGga(sentence("GPGGA,123519,48xx,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,")))
    }

    @Test
    fun `RMC vitesse cap et date`() {
        val parsed = notNull(Nmea.parseRmc(sentence("GNRMC,235959.50,A,4807.038,N,01131.000,E,022.4,084.4,041126,,,A")))
        assertEquals(22.4 * 0.514444, parsed.speedMs.toDouble(), 1e-4)
        assertEquals(84.4f, parsed.heading, 1e-4f)
        assertEquals(epoch("2026-11-04T23:59:59.500Z"), parsed.epochMs)
    }

    @Test
    fun `RMC invalide ignoree`() {
        assertNull(Nmea.parseRmc(rmc.replace(",A,", ",V,")))
    }

    @Test
    fun `RMC sans date`() {
        val parsed = notNull(Nmea.parseRmc(sentence("GNRMC,123519,A,4807.038,N,01131.000,E,0.0,,,,,A")))
        assertNull(parsed.epochMs)
        assertEquals(0f, parsed.heading, 0f)
    }

    @Test
    fun `GST erreur horizontale`() {
        val parsed = notNull(Nmea.parseGst(sentence("GNGST,172814.0,0.006,0.023,0.020,273.6,0.023,0.020,0.031")))
        assertEquals(kotlin.math.sqrt(0.023 * 0.023 + 0.020 * 0.020), parsed.horizontalErrorM.toDouble(), 1e-6)
    }

    @Test
    fun `GST incomplete ignoree`() {
        assertNull(Nmea.parseGst(sentence("GNGST,172814.0,0.006,0.023,0.020,273.6,,,")))
    }

    @Test
    fun `heure du jour en millisecondes`() {
        assertEquals((12 * 3600 + 35 * 60 + 19) * 1000L, Nmea.parseTimeOfDay("123519"))
        assertEquals((12 * 3600 + 35 * 60 + 19) * 1000L + 500, Nmea.parseTimeOfDay("123519.50"))
        assertNull(Nmea.parseTimeOfDay("2535"))
        assertNull(Nmea.parseTimeOfDay("256000"))
    }

    @Test
    fun `date ddmmyy`() {
        assertEquals(epoch("2026-11-05T00:00:00Z"), Nmea.parseDate("051126"))
        assertNull(Nmea.parseDate("320126"))
        assertNull(Nmea.parseDate("0511"))
    }

    @Test
    fun `horodatage le meme jour`() {
        val reference = epoch("2026-11-05T08:30:00.200Z")
        val timeOfDay = Nmea.parseTimeOfDay("083000.10")!!
        assertEquals(epoch("2026-11-05T08:30:00.100Z"), Nmea.closestTimestamp(timeOfDay, reference))
    }

    @Test
    fun `horodatage d'un fix de la veille juste apres minuit`() {
        val reference = epoch("2026-11-05T00:00:00.300Z")
        val timeOfDay = Nmea.parseTimeOfDay("235959.90")!!
        assertEquals(epoch("2026-11-04T23:59:59.900Z"), Nmea.closestTimestamp(timeOfDay, reference))
    }

    @Test
    fun `horodatage d'un fix du lendemain juste avant minuit`() {
        val reference = epoch("2026-11-04T23:59:59.900Z")
        val timeOfDay = Nmea.parseTimeOfDay("000000.10")!!
        assertEquals(epoch("2026-11-05T00:00:00.100Z"), Nmea.closestTimestamp(timeOfDay, reference))
    }

    @Test
    fun `precision type selon la qualite et le HDOP`() {
        assertEquals(0.02f, Nmea.typicalAccuracy("4", 0.0), 1e-6f)
        assertEquals(0.04f, Nmea.typicalAccuracy("4", 2.0), 1e-6f)
        assertEquals(0.30f, Nmea.typicalAccuracy("5", 1.0), 1e-6f)
        assertEquals(5.0f, Nmea.typicalAccuracy("1", 1.0), 1e-6f)
    }
}

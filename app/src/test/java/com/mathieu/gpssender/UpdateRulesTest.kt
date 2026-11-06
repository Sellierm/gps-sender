package com.mathieu.gpssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class UpdateRulesTest {

    private val sha = "0123456789abcdef".repeat(4)

    @Test
    fun `origine du serveur`() {
        assertEquals("https://sellier-farm.duckdns.org", UpdateRules.serverOrigin("https://sellier-farm.duckdns.org"))
        assertEquals("https://sellier-farm.duckdns.org", UpdateRules.serverOrigin(" https://sellier-farm.duckdns.org/ "))
        assertEquals("http://192.168.1.10:4200", UpdateRules.serverOrigin("http://192.168.1.10:4200/socket.io"))
        assertEquals("https://exemple.fr", UpdateRules.serverOrigin("HTTPS://exemple.fr?x=1"))
    }

    @Test
    fun `url de serveur invalide`() {
        assertNull(UpdateRules.serverOrigin(""))
        assertNull(UpdateRules.serverOrigin("sellier-farm.duckdns.org"))
        assertNull(UpdateRules.serverOrigin("ftp://exemple.fr"))
        assertNull(UpdateRules.serverOrigin("https://"))
        assertNull(UpdateRules.serverOrigin("https://exemple fr"))
    }

    @Test
    fun `version publiee valide`() {
        val remote = UpdateRules.validate(7, "2.3.0", sha, 1_847_361)
        assertNotNull(remote)
        assertTrue(UpdateRules.isNewer(remote!!, 6))
        assertFalse(UpdateRules.isNewer(remote, 7))
        assertFalse(UpdateRules.isNewer(remote, 8))
    }

    @Test
    fun `version publiee incomplete ou douteuse`() {
        // optLong / optString renvoient 0 et "" pour un champ absent
        assertNull(UpdateRules.validate(0, "2.3.0", sha, 1000))
        assertNull(UpdateRules.validate(7, "", sha, 1000))
        assertNull(UpdateRules.validate(7, "2.3\nfaux", sha, 1000))
        assertNull(UpdateRules.validate(7, "2.3.0", sha.uppercase(), 1000))
        assertNull(UpdateRules.validate(7, "2.3.0", sha.drop(1), 1000))
        assertNull(UpdateRules.validate(7, "2.3.0", sha, 0))
        assertNull(UpdateRules.validate(7, "2.3.0", sha, UpdateRules.MAX_APK_BYTES + 1))
    }

    @Test
    fun `comparaison des certificats`() {
        val ours = setOf("aa")
        assertFalse(UpdateRules.signersMismatch(ours, setOf("aa")))
        assertFalse(UpdateRules.signersMismatch(setOf("aa", "bb"), setOf("bb")))
        assertTrue(UpdateRules.signersMismatch(ours, setOf("cc")))
        // Certificat illisible : on laisse Android trancher
        assertFalse(UpdateRules.signersMismatch(null, setOf("cc")))
        assertFalse(UpdateRules.signersMismatch(ours, emptySet()))
    }

    @Test
    fun `hexadecimal`() {
        assertEquals("00ff7f80", UpdateRules.hex(byteArrayOf(0, -1, 127, -128)))
        val digest = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateRules.hex(digest))
    }
}

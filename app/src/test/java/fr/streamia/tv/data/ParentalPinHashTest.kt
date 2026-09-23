package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ParentalPinHashTest {
    @Test
    fun `same pin and salt hash identically`() {
        assertEquals(hashPin("1234", "salt-a"), hashPin("1234", "salt-a"))
    }

    @Test
    fun `different pins hash differently for the same salt`() {
        assertNotEquals(hashPin("1234", "salt-a"), hashPin("4321", "salt-a"))
    }

    @Test
    fun `same pin hashes differently across salts`() {
        assertNotEquals(hashPin("1234", "salt-a"), hashPin("1234", "salt-b"))
    }

    @Test
    fun `hash never contains the plain pin`() {
        val hash = hashPin("1234", "salt-a")
        assertNotEquals("1234", hash)
        org.junit.Assert.assertFalse(hash.contains("1234"))
    }

    @Test
    fun `slow hash is deterministic per salt and differs from the legacy hash`() {
        assertEquals(slowHashPin("1234", "salt-a"), slowHashPin("1234", "salt-a"))
        assertNotEquals(slowHashPin("1234", "salt-a"), slowHashPin("1234", "salt-b"))
        assertNotEquals(hashPin("1234", "salt-a"), slowHashPin("1234", "salt-a"))
    }

    @Test
    fun `lockout starts after five failures, doubles and is capped`() {
        assertEquals(0L, pinLockoutMillis(4))
        assertEquals(30_000L, pinLockoutMillis(5))
        assertEquals(60_000L, pinLockoutMillis(6))
        assertEquals(15 * 60_000L, pinLockoutMillis(50))
    }

    @Test
    fun `pbkdf2 matches the RFC 7914 test vector`() {
        val derived = pbkdf2HmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1)
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", derived.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `pbkdf2 matches the platform implementation`() {
        val spec = javax.crypto.spec.PBEKeySpec("1234".toCharArray(), "salt-a".toByteArray(), 1000, 256)
        val expected = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        org.junit.Assert.assertArrayEquals(expected, pbkdf2HmacSha256("1234".toByteArray(), "salt-a".toByteArray(), 1000))
    }
}

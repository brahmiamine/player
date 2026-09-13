package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        assertFalse(hash.contains("1234"))
        assertTrue(hash.startsWith(PIN_HASH_PREFIX))
    }

    @Test
    fun `stretched hash matches and legacy sha256 hashes still verify`() {
        assertTrue(pinMatches("1234", "salt-a", hashPin("1234", "salt-a")))
        assertFalse(pinMatches("4321", "salt-a", hashPin("1234", "salt-a")))
        assertTrue(pinMatches("1234", "salt-a", hashPinSha256("1234", "salt-a")))
        assertFalse(pinMatches("1234", "salt-a", hashPinSha256("4321", "salt-a")))
    }
}

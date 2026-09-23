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
}

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
}

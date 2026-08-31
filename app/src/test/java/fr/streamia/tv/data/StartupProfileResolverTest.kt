package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupProfileResolverTest {
    private val profiles = listOf("first", "active", "playback")

    @Test
    fun `last playback profile wins when it still exists`() {
        assertEquals("playback", resolveStartupProfileId(profiles, "playback", "active", false))
    }

    @Test
    fun `active profile is used when there is no playback session`() {
        assertEquals("active", resolveStartupProfileId(profiles, null, "active", false))
    }

    @Test
    fun `first profile is a one time migration fallback`() {
        assertEquals("first", resolveStartupProfileId(profiles, null, null, false))
    }

    @Test
    fun `deleted ids are ignored`() {
        assertEquals("first", resolveStartupProfileId(profiles, "deleted", "also-deleted", false))
    }

    @Test
    fun `explicit logout disables automatic opening`() {
        assertNull(resolveStartupProfileId(profiles, "playback", "active", true))
    }
}

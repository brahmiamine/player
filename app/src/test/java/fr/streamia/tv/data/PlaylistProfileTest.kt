package fr.streamia.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistProfileTest {
    @Test
    fun `fresh Xtream catalog stays local until interval expires`() {
        val now = 10_000_000L
        val profile = PlaylistProfile(
            id = "xtream",
            name = "TV",
            kind = PlaylistKind.Xtream,
            serverUrl = "http://provider.test",
            username = "u",
            password = "p",
            autoRefreshHours = 6,
            lastRefreshAt = now - 5L * 60L * 60L * 1000L,
        )

        assertFalse(profile.isCatalogRefreshDue(now))
    }

    @Test
    fun `Xtream catalog refresh becomes due after configured interval`() {
        val now = 30_000_000L
        val profile = PlaylistProfile(
            id = "xtream",
            name = "TV",
            kind = PlaylistKind.Xtream,
            autoRefreshHours = 6,
            lastRefreshAt = now - 6L * 60L * 60L * 1000L,
        )

        assertTrue(profile.isCatalogRefreshDue(now))
    }

    @Test
    fun `never refreshed profile is immediately due`() {
        val profile = PlaylistProfile(
            id = "xtream",
            name = "TV",
            kind = PlaylistKind.Xtream,
            lastRefreshAt = 0L,
        )

        assertTrue(profile.isCatalogRefreshDue(1_000L))
    }

    @Test
    fun `local M3U never requests automatic provider refresh`() {
        val profile = PlaylistProfile(
            id = "m3u",
            name = "Local",
            kind = PlaylistKind.M3u,
            m3uUri = "content://playlist/local",
            autoRefreshHours = 1,
            lastRefreshAt = 1L,
        )

        assertFalse(profile.shouldAutoRefresh(10_000_000L))
    }
}

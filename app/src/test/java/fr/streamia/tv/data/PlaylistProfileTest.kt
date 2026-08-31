package fr.streamia.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistProfileTest {
    @Test
    fun `fresh Xtream catalog never requests automatic refresh`() {
        val now = 100_000_000L
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

        assertFalse(profile.shouldAutoRefresh(now))
    }

    @Test
    fun `expired Xtream catalog still never requests automatic refresh`() {
        val now = 100_000_000L
        val profile = PlaylistProfile(
            id = "xtream",
            name = "TV",
            kind = PlaylistKind.Xtream,
            autoRefreshHours = 6,
            lastRefreshAt = now - 6L * 60L * 60L * 1000L,
        )

        assertFalse(profile.shouldAutoRefresh(now))
    }

    @Test
    fun `remote M3U becomes due according to its interval`() {
        val profile = PlaylistProfile(
            id = "m3u-remote",
            name = "Remote",
            kind = PlaylistKind.M3u,
            m3uUrl = "https://provider.test/list.m3u",
            lastRefreshAt = 0L,
        )

        assertTrue(profile.shouldAutoRefresh(1_000L))
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

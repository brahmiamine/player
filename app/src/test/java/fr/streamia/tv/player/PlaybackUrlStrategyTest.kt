package fr.streamia.tv.player

import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackUrlStrategyTest {
    @Test
    fun `live candidates try preferred container then all sequential fallbacks`() {
        val url = "http://provider.test/live/user/pass/42.ts"
        val candidates = PlaybackUrlStrategy.candidates(
            initialUrl = url,
            type = MediaType.Live,
            preference = PlaybackTransportPreference(scheme = "https", liveExtension = "m3u8"),
        )

        assertEquals("https://provider.test/live/user/pass/42.m3u8", candidates.first())
        assertEquals(4, candidates.distinct().size)
        assertTrue("http://provider.test/live/user/pass/42.ts" in candidates)
        assertTrue("http://provider.test/live/user/pass/42.m3u8" in candidates)
        assertTrue("https://provider.test/live/user/pass/42.ts" in candidates)
    }

    @Test
    fun `vod never changes the media extension`() {
        val candidates = PlaybackUrlStrategy.candidates(
            initialUrl = "http://provider.test/movie/user/pass/9.mkv",
            type = MediaType.Movie,
        )

        assertEquals(
            listOf(
                "http://provider.test/movie/user/pass/9.mkv",
                "https://provider.test/movie/user/pass/9.mkv",
            ),
            candidates,
        )
    }

    @Test
    fun `https streams never fall back to cleartext http`() {
        val live = PlaybackUrlStrategy.candidates(
            initialUrl = "https://provider.test/live/user/pass/42.ts",
            type = MediaType.Live,
            preference = PlaybackTransportPreference(scheme = "http", liveExtension = "m3u8"),
        )
        assertTrue(live.all { it.startsWith("https://") })
        assertEquals(
            listOf(
                "https://provider.test/live/user/pass/42.m3u8",
                "https://provider.test/live/user/pass/42.ts",
            ),
            live,
        )

        val vod = PlaybackUrlStrategy.candidates(
            initialUrl = "https://provider.test/movie/user/pass/9.mkv",
            type = MediaType.Movie,
            preference = PlaybackTransportPreference(scheme = "http"),
        )
        assertEquals(listOf("https://provider.test/movie/user/pass/9.mkv"), vod)
    }
}

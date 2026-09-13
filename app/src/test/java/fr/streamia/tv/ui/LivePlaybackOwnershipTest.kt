package fr.streamia.tv.ui

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackOwnershipTest {
    @Test
    fun `keeps the shared live player on the live browser and live fullscreen`() {
        assertTrue(shouldKeepLivePlayback(StreamiaScreen.Browser))
        assertTrue(shouldKeepLivePlayback(StreamiaScreen.Player(liveEntry())))
    }

    @Test
    fun `stops the shared live player on vod details home and settings`() {
        assertFalse(shouldKeepLivePlayback(StreamiaScreen.Home))
        assertFalse(shouldKeepLivePlayback(StreamiaScreen.Settings))
        assertFalse(shouldKeepLivePlayback(StreamiaScreen.Search))
        assertFalse(shouldKeepLivePlayback(StreamiaScreen.Epg))
        assertFalse(shouldKeepLivePlayback(StreamiaScreen.Player(movieEntry())))
        assertFalse(shouldKeepLivePlayback(StreamiaScreen.MovieDetails(movieEntry())))
        assertFalse(shouldKeepLivePlayback(StreamiaScreen.Series(seriesEntry())))
        assertFalse(shouldKeepLivePlayback(StreamiaScreen.LiveMatches))
    }

    private fun liveEntry() = MediaEntry(
        id = 1,
        name = "Live",
        type = MediaType.Live,
        categoryId = "1",
        iconUrl = null,
        number = 1,
    )

    private fun movieEntry() = MediaEntry(
        id = 2,
        name = "Movie",
        type = MediaType.Movie,
        categoryId = "10",
        iconUrl = null,
        number = 2,
    )

    private fun seriesEntry() = MediaEntry(
        id = 3,
        name = "Series",
        type = MediaType.Series,
        categoryId = "20",
        iconUrl = null,
        number = 3,
        playable = false,
    )
}

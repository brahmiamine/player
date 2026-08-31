package fr.streamia.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSelectionTest {
    @Test
    fun `first OK on another channel only previews it`() {
        assertEquals(
            LiveChannelConfirmAction.Preview,
            liveChannelConfirmAction(previewKey = "Live:10", channelKey = "Live:11"),
        )
    }

    @Test
    fun `second OK on already previewed channel opens fullscreen`() {
        assertEquals(
            LiveChannelConfirmAction.Fullscreen,
            liveChannelConfirmAction(previewKey = "Live:11", channelKey = "Live:11"),
        )
    }
}

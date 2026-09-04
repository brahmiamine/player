package fr.streamia.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `extra OK is ignored while fullscreen handoff is pending`() {
        assertEquals(
            LiveChannelConfirmAction.Ignore,
            liveChannelConfirmAction(
                previewKey = "Live:11",
                channelKey = "Live:11",
                fullscreenPending = true,
            ),
        )
    }
    @Test
    fun `same live channel keeps the current player session`() {
        assertFalse(
            shouldRestartLivePreview(
                currentEntryKey = "Live:11",
                currentMediaItemCount = 1,
                targetEntryKey = "Live:11",
            ),
        )
    }

    @Test
    fun `another live channel still restarts playback`() {
        assertTrue(
            shouldRestartLivePreview(
                currentEntryKey = "Live:10",
                currentMediaItemCount = 1,
                targetEntryKey = "Live:11",
            ),
        )
    }

    @Test
    fun `missing media item restarts even for the same channel`() {
        assertTrue(
            shouldRestartLivePreview(
                currentEntryKey = "Live:11",
                currentMediaItemCount = 0,
                targetEntryKey = "Live:11",
            ),
        )
    }


    @Test
    fun `active live url becomes first fallback candidate when reusing session`() {
        assertEquals(
            listOf(
                "https://provider/live/11.ts",
                "http://provider/live/11.ts",
                "http://provider/live/11.m3u8",
                "https://provider/live/11.m3u8",
            ),
            prioritizeActiveLiveCandidate(
                candidates = listOf(
                    "http://provider/live/11.ts",
                    "http://provider/live/11.m3u8",
                    "https://provider/live/11.ts",
                    "https://provider/live/11.m3u8",
                ),
                activeUrl = "https://provider/live/11.ts",
            ),
        )
    }

    @Test
    fun `active live url is prepended when current session url is not in regenerated candidates`() {
        assertEquals(
            listOf(
                "https://provider/live/11.ts",
                "http://provider/live/11.ts",
                "http://provider/live/11.m3u8",
            ),
            prioritizeActiveLiveCandidate(
                candidates = listOf(
                    "http://provider/live/11.ts",
                    "http://provider/live/11.m3u8",
                ),
                activeUrl = "https://provider/live/11.ts",
            ),
        )
    }

}

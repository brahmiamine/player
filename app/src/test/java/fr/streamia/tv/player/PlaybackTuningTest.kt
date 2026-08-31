package fr.streamia.tv.player

import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTuningTest {
    @Test
    fun `live starts with a small latency-oriented buffer`() {
        val profile = PlaybackTuning.forType(MediaType.Live)

        assertEquals(2_500, profile.minBufferMs)
        assertEquals(12_000, profile.maxBufferMs)
        assertEquals(350, profile.bufferForPlaybackMs)
        assertEquals(900, profile.bufferForPlaybackAfterRebufferMs)
    }

    @Test
    fun `vod keeps a larger stability-oriented buffer`() {
        listOf(MediaType.Movie, MediaType.Series).forEach { type ->
            val profile = PlaybackTuning.forType(type)
            assertEquals(25_000, profile.minBufferMs)
            assertEquals(90_000, profile.maxBufferMs)
            assertEquals(650, profile.bufferForPlaybackMs)
            assertEquals(3_000, profile.bufferForPlaybackAfterRebufferMs)
            assertTrue(profile.maxBufferMs > PlaybackTuning.forType(MediaType.Live).maxBufferMs)
        }
    }
}

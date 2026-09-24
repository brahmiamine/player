package fr.streamia.tv.player

import fr.streamia.tv.data.BufferMode
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTuningTest {
    @Test
    fun `live starts with a small latency-oriented buffer`() {
        val profile = PlaybackTuning.forType(MediaType.Live)

        assertEquals(4_000, profile.minBufferMs)
        assertEquals(20_000, profile.maxBufferMs)
        assertEquals(350, profile.bufferForPlaybackMs)
        assertEquals(1_500, profile.bufferForPlaybackAfterRebufferMs)
    }

    @Test
    fun `stable live buffering is larger than low latency`() {
        val low = PlaybackTuning.forType(MediaType.Live, BufferMode.LowLatency)
        val stable = PlaybackTuning.forType(MediaType.Live, BufferMode.Stable)

        assertTrue(stable.minBufferMs > low.minBufferMs)
        assertTrue(stable.maxBufferMs > low.maxBufferMs)
        assertTrue(stable.bufferForPlaybackAfterRebufferMs > low.bufferForPlaybackAfterRebufferMs)
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

    @Test
    fun `buffer memory is a share of the app heap, bounded`() {
        assertEquals(32 * 1024 * 1024, bufferBytesForHeap(64))
        assertEquals((512 * 0.35 * 1024 * 1024).toInt(), bufferBytesForHeap(512))
        assertEquals(200 * 1024 * 1024, bufferBytesForHeap(1024))
    }
}

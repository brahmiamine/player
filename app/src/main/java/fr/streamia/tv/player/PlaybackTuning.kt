package fr.streamia.tv.player

import fr.streamia.tv.domain.MediaType

data class BufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
)

object PlaybackTuning {
    fun forType(type: MediaType): BufferProfile = when (type) {
        MediaType.Live -> BufferProfile(
            minBufferMs = 2_500,
            maxBufferMs = 12_000,
            bufferForPlaybackMs = 350,
            bufferForPlaybackAfterRebufferMs = 900,
        )
        MediaType.Movie,
        MediaType.Series,
        -> BufferProfile(
            minBufferMs = 15_000,
            maxBufferMs = 50_000,
            bufferForPlaybackMs = 800,
            bufferForPlaybackAfterRebufferMs = 2_000,
        )
    }
}

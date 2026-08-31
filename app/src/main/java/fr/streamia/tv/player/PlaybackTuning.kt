package fr.streamia.tv.player

import fr.streamia.tv.domain.MediaType

data class BufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
)

object PlaybackTuning {
    fun forType(type: MediaType): BufferProfile = BufferProfile(
        minBufferMs = 50_000,
        maxBufferMs = 50_000,
        bufferForPlaybackMs = 2_500,
        bufferForPlaybackAfterRebufferMs = 5_000,
    )
}

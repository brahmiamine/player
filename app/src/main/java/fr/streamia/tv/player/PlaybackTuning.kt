package fr.streamia.tv.player

import fr.streamia.tv.data.BufferMode
import fr.streamia.tv.domain.MediaType

data class BufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
)

object PlaybackTuning {
    fun forType(type: MediaType, mode: BufferMode = BufferMode.Auto): BufferProfile = when (type) {
        MediaType.Live -> when (mode) {
            BufferMode.LowLatency -> BufferProfile(1_500, 6_000, 250, 600)
            BufferMode.Auto -> BufferProfile(2_500, 12_000, 350, 900)
            BufferMode.Stable -> BufferProfile(5_000, 25_000, 700, 2_000)
        }
        MediaType.Movie,
        MediaType.Series,
        -> when (mode) {
            BufferMode.LowLatency -> BufferProfile(12_000, 45_000, 450, 1_500)
            BufferMode.Auto -> BufferProfile(25_000, 90_000, 650, 3_000)
            BufferMode.Stable -> BufferProfile(40_000, 120_000, 1_000, 5_000)
        }
    }
}

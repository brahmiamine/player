package fr.streamia.tv.player

data class PlaybackDiagnostics(
    val startupTimeMs: Long? = null,
    val rebufferCount: Int = 0,
    val totalRebufferTimeMs: Long = 0L,
)

class PlaybackDiagnosticsTracker {
    fun reset(nowMs: Long) = Unit
    fun onBufferingStarted(nowMs: Long) = Unit
    fun onBufferingEnded(nowMs: Long) = Unit
    fun onFirstFrame(nowMs: Long) = Unit
    fun snapshot(nowMs: Long): PlaybackDiagnostics = PlaybackDiagnostics()
}

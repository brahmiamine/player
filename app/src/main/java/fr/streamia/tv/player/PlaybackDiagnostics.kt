package fr.streamia.tv.player

data class PlaybackDiagnostics(
    val startupTimeMs: Long? = null,
    val rebufferCount: Int = 0,
    val totalRebufferTimeMs: Long = 0L,
)

class PlaybackDiagnosticsTracker {
    private var resetAtMs: Long = 0L
    private var firstFrameAtMs: Long? = null
    private var bufferingStartedAtMs: Long? = null
    private var currentBufferIsRebuffer = false
    private var completedRebufferTimeMs: Long = 0L
    private var rebufferCount = 0

    fun reset(nowMs: Long) {
        resetAtMs = nowMs
        firstFrameAtMs = null
        bufferingStartedAtMs = null
        currentBufferIsRebuffer = false
        completedRebufferTimeMs = 0L
        rebufferCount = 0
    }

    fun onBufferingStarted(nowMs: Long) {
        if (bufferingStartedAtMs != null) return
        bufferingStartedAtMs = nowMs
        currentBufferIsRebuffer = firstFrameAtMs != null
        if (currentBufferIsRebuffer) rebufferCount += 1
    }

    fun onBufferingEnded(nowMs: Long) {
        val startedAt = bufferingStartedAtMs ?: return
        if (currentBufferIsRebuffer) {
            completedRebufferTimeMs += (nowMs - startedAt).coerceAtLeast(0L)
        }
        bufferingStartedAtMs = null
        currentBufferIsRebuffer = false
    }

    fun onFirstFrame(nowMs: Long) {
        if (firstFrameAtMs == null) firstFrameAtMs = nowMs
    }

    fun snapshot(nowMs: Long): PlaybackDiagnostics {
        val activeRebufferMs = if (currentBufferIsRebuffer) {
            bufferingStartedAtMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        } else {
            0L
        }
        return PlaybackDiagnostics(
            startupTimeMs = firstFrameAtMs?.let { (it - resetAtMs).coerceAtLeast(0L) },
            rebufferCount = rebufferCount,
            totalRebufferTimeMs = completedRebufferTimeMs + activeRebufferMs,
        )
    }
}

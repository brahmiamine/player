package fr.streamia.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test
    fun `startup buffering is measured separately from rebuffers`() {
        val tracker = PlaybackDiagnosticsTracker()
        tracker.reset(1_000)
        tracker.onBufferingStarted(1_000)
        tracker.onFirstFrame(1_650)
        tracker.onBufferingEnded(1_650)

        val snapshot = tracker.snapshot(1_650)
        assertEquals(650L, snapshot.startupTimeMs)
        assertEquals(0, snapshot.rebufferCount)
        assertEquals(0L, snapshot.totalRebufferTimeMs)
    }

    @Test
    fun `buffering after first frame increments rebuffer count and duration`() {
        val tracker = PlaybackDiagnosticsTracker()
        tracker.reset(0)
        tracker.onFirstFrame(500)
        tracker.onBufferingStarted(2_000)
        tracker.onBufferingEnded(2_450)
        tracker.onBufferingStarted(3_000)

        val snapshot = tracker.snapshot(3_200)
        assertEquals(500L, snapshot.startupTimeMs)
        assertEquals(2, snapshot.rebufferCount)
        assertEquals(650L, snapshot.totalRebufferTimeMs)
    }
}

package fr.streamia.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivePlaybackCheckTest {
    @Test
    fun measuresRealFramesPerSecondAndDroppedFrames() {
        val monitor = VideoFrameMonitor(fpsWindowMs = 3_000L)
        monitor.reset(0L)
        monitor.sample(1_000L, renderedFrames = 100, droppedFrames = 0, playing = true)
        monitor.sample(2_000L, renderedFrames = 125, droppedFrames = 0, playing = true)
        monitor.sample(3_000L, renderedFrames = 150, droppedFrames = 5, playing = true)
        assertNull(monitor.realFps)
        monitor.sample(4_000L, renderedFrames = 175, droppedFrames = 25, playing = true)
        // 75 images en 3 s = 25 fps réels, 25 perdues sur 100.
        assertEquals(25f, monitor.realFps!!, 0.01f)
        assertEquals(0.25f, monitor.droppedRatio!!, 0.001f)
    }

    @Test
    fun detectsAFrozenPictureOnlyWhilePlaying() {
        val monitor = VideoFrameMonitor()
        monitor.reset(0L)
        assertEquals(0L, monitor.sample(1_000L, 50, 0, playing = true))
        assertEquals(1_000L, monitor.sample(2_000L, 50, 0, playing = true))
        assertEquals(2_000L, monitor.sample(3_000L, 50, 0, playing = true))
        // En pause ou en chargement, ce n'est pas une image figée.
        assertEquals(0L, monitor.sample(4_000L, 50, 0, playing = false))
        // Reprise : le temps sans image repart de la reprise, pas du début de la pause.
        assertEquals(1_000L, monitor.sample(5_000L, 50, 0, playing = true))
        assertEquals(0L, monitor.sample(6_000L, 75, 0, playing = true))
    }

    @Test
    fun restartsWhenTheDecoderCountersAreRecreated() {
        val monitor = VideoFrameMonitor()
        monitor.reset(0L)
        monitor.sample(1_000L, 500, 0, playing = true)
        assertEquals(0L, monitor.sample(2_000L, 10, 0, playing = true))
        assertEquals(0L, monitor.sample(3_000L, 35, 0, playing = true))
    }

    @Test
    fun reportsMissingUnsupportedOrFailingSound() {
        assertEquals(LiveAudioIssue.NoTrack, liveAudioIssue(hasVideoTrack = true, audioTrackGroups = 0, anyAudioSelected = false, audioErrors = 0))
        assertEquals(LiveAudioIssue.Unsupported, liveAudioIssue(hasVideoTrack = true, audioTrackGroups = 2, anyAudioSelected = false, audioErrors = 0))
        assertEquals(LiveAudioIssue.Errors, liveAudioIssue(hasVideoTrack = true, audioTrackGroups = 1, anyAudioSelected = true, audioErrors = 3))
        assertNull(liveAudioIssue(hasVideoTrack = true, audioTrackGroups = 1, anyAudioSelected = true, audioErrors = 2))
        // Sans image ni son, c'est l'absence d'image qui est signalée.
        assertNull(liveAudioIssue(hasVideoTrack = false, audioTrackGroups = 0, anyAudioSelected = false, audioErrors = 0))
    }
}

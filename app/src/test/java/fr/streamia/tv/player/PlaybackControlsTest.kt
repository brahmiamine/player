package fr.streamia.tv.player

import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackControlsTest {
    @Test
    fun `movie and series directional keys seek by ten seconds`() {
        assertEquals(PlaybackRemoteAction.SeekBackward, playbackRemoteAction(MediaType.Movie, PlaybackRemoteButton.Left))
        assertEquals(PlaybackRemoteAction.SeekForward, playbackRemoteAction(MediaType.Movie, PlaybackRemoteButton.Right))
        assertEquals(PlaybackRemoteAction.SeekBackward, playbackRemoteAction(MediaType.Series, PlaybackRemoteButton.Left))
        assertEquals(PlaybackRemoteAction.SeekForward, playbackRemoteAction(MediaType.Series, PlaybackRemoteButton.Right))
    }

    @Test
    fun `dedicated rewind and fast forward keys seek VOD`() {
        assertEquals(PlaybackRemoteAction.SeekBackward, playbackRemoteAction(MediaType.Movie, PlaybackRemoteButton.Rewind))
        assertEquals(PlaybackRemoteAction.SeekForward, playbackRemoteAction(MediaType.Series, PlaybackRemoteButton.FastForward))
    }

    @Test
    fun `live OK and left return to the main live browser`() {
        assertEquals(PlaybackRemoteAction.OpenLivePicker, playbackRemoteAction(MediaType.Live, PlaybackRemoteButton.Ok))
        assertEquals(PlaybackRemoteAction.OpenLivePicker, playbackRemoteAction(MediaType.Live, PlaybackRemoteButton.Left))
    }

    @Test
    fun `seek position is clamped to media boundaries`() {
        assertEquals(0L, resolveSeekPosition(currentPositionMs = 4_000L, durationMs = 60_000L, deltaMs = -10_000L))
        assertEquals(60_000L, resolveSeekPosition(currentPositionMs = 57_000L, durationMs = 60_000L, deltaMs = 10_000L))
        assertEquals(32_000L, resolveSeekPosition(currentPositionMs = 22_000L, durationMs = 60_000L, deltaMs = 10_000L))
    }
}

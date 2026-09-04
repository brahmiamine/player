package fr.streamia.tv.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackContinuationTest {
    @Test
    fun `idle reused live player requires prepare before play`() {
        assertTrue(shouldPrepareLivePlayback(Player.STATE_IDLE))
    }

    @Test
    fun `ready reused live player does not prepare again`() {
        assertFalse(shouldPrepareLivePlayback(Player.STATE_READY))
    }

    @Test
    fun `buffering reused live player does not prepare again`() {
        assertFalse(shouldPrepareLivePlayback(Player.STATE_BUFFERING))
    }
}

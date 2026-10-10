package fr.streamia.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class StablePlaybackControlTest {
    @Test
    fun `first rebuffer keeps the usual margin`() {
        assertEquals(1_500L, requiredRebufferMarginMs(1_500, 0, 10_000))
        assertEquals(1_500L, requiredRebufferMarginMs(1_500, 1, 10_000))
    }

    @Test
    fun `repeated rebuffers double the margin up to the cap`() {
        assertEquals(3_000L, requiredRebufferMarginMs(1_500, 2, 10_000))
        assertEquals(6_000L, requiredRebufferMarginMs(1_500, 3, 10_000))
        assertEquals(10_000L, requiredRebufferMarginMs(1_500, 4, 10_000))
        assertEquals(12_000L, requiredRebufferMarginMs(3_000, 50, 12_000))
    }

    @Test
    fun `cap never goes below the usual margin`() {
        assertEquals(5_000L, requiredRebufferMarginMs(5_000, 3, 3_000))
    }
}

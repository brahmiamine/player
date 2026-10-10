package fr.streamia.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUhdDetectorTest {
    @Test
    fun `only a real 3840 x 2160 picture counts as uhd`() {
        assertTrue(isTrueUhd(3840, 2160))
        assertTrue(isTrueUhd(4096, 2160))
        assertFalse(isTrueUhd(1920, 1080))
        assertFalse(isTrueUhd(2560, 1440))
        assertFalse(isTrueUhd(3840, 1600))
        assertFalse(isTrueUhd(0, 0))
    }
}

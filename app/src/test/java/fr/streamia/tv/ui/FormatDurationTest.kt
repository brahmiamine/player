package fr.streamia.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDurationTest {
    @Test
    fun `minutes and seconds are zero padded`() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:09", formatDuration(9_999))
        assertEquals("01:05", formatDuration(65_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test
    fun `hours are shown without padding`() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("1:02:05", formatDuration(3_725_000))
        assertEquals("12:00:07", formatDuration(43_207_000))
    }

    @Test
    fun `negative positions read as zero`() {
        assertEquals("00:00", formatDuration(-5_000))
    }
}

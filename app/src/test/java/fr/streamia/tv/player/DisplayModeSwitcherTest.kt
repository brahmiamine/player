package fr.streamia.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayModeSwitcherTest {
    private val hd60 = DisplayModeSpec(1, 1920, 1080, 60f)
    private val hd50 = DisplayModeSpec(2, 1920, 1080, 50f)
    private val hd24 = DisplayModeSpec(3, 1920, 1080, 23.976f)
    private val uhd60 = DisplayModeSpec(4, 3840, 2160, 60f)
    private val uhd50 = DisplayModeSpec(5, 3840, 2160, 50f)
    private val uhd24 = DisplayModeSpec(6, 3840, 2160, 23.976f)
    private val modes = listOf(hd60, hd50, hd24, uhd60, uhd50, uhd24)

    @Test
    fun `4k film on a 1080p interface switches to 4k at its frame rate`() {
        assertEquals(uhd24, bestDisplayMode(modes, hd60, 3840, 2160, 23.976f))
    }

    @Test
    fun `25 fps european channel uses a 50 hz multiple`() {
        assertEquals(uhd50, bestDisplayMode(modes, uhd60, 1920, 1080, 25f))
    }

    @Test
    fun `hd content never lowers a 4k output`() {
        assertEquals(uhd24, bestDisplayMode(modes, uhd60, 1280, 720, 23.976f))
    }

    @Test
    fun `unknown frame rate keeps the current rate and a matching mode means no change`() {
        assertEquals(uhd60, bestDisplayMode(modes, hd60, 3840, 2160, -1f))
        assertNull(bestDisplayMode(modes, hd60, 1920, 1080, 60f))
    }

    @Test
    fun `screen without 4k keeps its best resolution`() {
        assertEquals(hd24, bestDisplayMode(listOf(hd60, hd24), hd60, 3840, 2160, 23.976f))
    }
}

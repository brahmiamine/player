package fr.streamia.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbySupportTest {
    @Test
    fun `Dolby Vision is detected from mime or codec`() {
        assertTrue(isDolbyVisionFormat("video/dolby-vision", null))
        assertTrue(isDolbyVisionFormat("video/hevc", "dvh1.05.06"))
        assertFalse(isDolbyVisionFormat("video/hevc", "hvc1.2.4.L153"))
    }

    @Test
    fun `Atmos is detected only from JOC stream metadata`() {
        assertTrue(isDolbyAtmosFormat("audio/eac3-joc"))
        assertFalse(isDolbyAtmosFormat("audio/eac3"))
        assertFalse(isDolbyAtmosFormat("audio/ac3"))
    }

    @Test
    fun `Dolby labels distinguish active from unsupported output`() {
        assertEquals("Dolby Vision actif", dolbyPlaybackLabel("Dolby Vision", true, true))
        assertEquals(
            "Dolby Atmos détecté · sortie non compatible",
            dolbyPlaybackLabel("Dolby Atmos", true, false),
        )
        assertEquals(null, dolbyPlaybackLabel("Dolby Atmos", false, true))
    }
}

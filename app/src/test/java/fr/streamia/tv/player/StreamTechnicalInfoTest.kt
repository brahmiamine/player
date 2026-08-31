package fr.streamia.tv.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamTechnicalInfoTest {
    @Test
    fun `quality label is derived only from measured dimensions`() {
        assertEquals("SD", StreamTechnicalInfo(width = 720, height = 576).qualityLabel)
        assertEquals("HD", StreamTechnicalInfo(width = 1280, height = 720).qualityLabel)
        assertEquals("FHD", StreamTechnicalInfo(width = 1920, height = 1080).qualityLabel)
        assertEquals("QHD", StreamTechnicalInfo(width = 2560, height = 1440).qualityLabel)
        assertEquals("4K UHD", StreamTechnicalInfo(width = 3840, height = 2160).qualityLabel)
    }

    @Test
    fun `codec and hdr labels use decoded format metadata`() {
        assertEquals("H.264 / AVC", codecLabel("video/avc", null))
        assertEquals("H.265 / HEVC", codecLabel("video/hevc", null))
        assertEquals("Dolby Vision", codecLabel("video/dolby-vision", null))
        assertEquals("HDR10 / PQ", hdrLabel("video/hevc", C.COLOR_TRANSFER_ST2084))
        assertEquals("HLG", hdrLabel("video/hevc", C.COLOR_TRANSFER_HLG))
        assertEquals("Dolby Vision", hdrLabel("video/dolby-vision", C.COLOR_TRANSFER_ST2084))
        assertEquals("SDR", hdrLabel("video/avc", C.COLOR_TRANSFER_SDR))
    }

    @Test
    fun `unknown fps and bitrate stay explicit`() {
        val info = StreamTechnicalInfo(width = 1920, height = 1080)
        assertEquals("FPS —", info.fpsText)
        assertEquals("Débit —", info.bitrateText)
    }
}

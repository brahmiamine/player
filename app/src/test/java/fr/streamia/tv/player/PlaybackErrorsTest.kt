package fr.streamia.tv.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorsTest {
    @Test
    fun `only decoder failures are reported as unsupported formats`() {
        assertTrue(isDecoderError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(isDecoderError(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES))
        assertFalse(isDecoderError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
    }

    @Test
    fun `message names the codec and resolution`() {
        assertEquals(
            "Ce boîtier ne sait pas décoder cette vidéo (HEVC · 3840×2160). Essayez une autre version de ce contenu (HD par exemple).",
            unsupportedFormatMessage("video/hevc", 3840, 2160),
        )
    }
}

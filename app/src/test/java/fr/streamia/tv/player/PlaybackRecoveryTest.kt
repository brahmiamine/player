package fr.streamia.tv.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryTest {
    @Test
    fun `network drops and live window errors are retried on the same url`() {
        assertTrue(isRecoverableStreamError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(isRecoverableStreamError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertTrue(isRecoverableStreamError(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertTrue(isRecoverableStreamError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
    }

    @Test
    fun `decoder and missing content errors are not retried`() {
        assertFalse(isRecoverableStreamError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertFalse(isRecoverableStreamError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertFalse(isRecoverableStreamError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertFalse(isRecoverableStreamError(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED))
    }

    @Test
    fun `retry delay backs off then caps`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L, 15_000L),
            (1..MAX_STREAM_RECOVERY_ATTEMPTS).map(::streamRecoveryDelayMs))
    }
}

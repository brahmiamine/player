package fr.streamia.tv.player

import androidx.media3.common.PlaybackException

/**
 * Erreur passagère d'un flux qui a déjà affiché une image : coupure réseau, serveur qui ferme la
 * connexion au bout de quelques heures, direct HLS dépassé (BEHIND_LIVE_WINDOW), paquet TS
 * corrompu, sortie audio HDMI réinitialisée… Relancer la même URL suffit. Changer d'URL (TS/HLS,
 * HTTP/HTTPS) n'est utile qu'au démarrage, quand on ne sait pas encore laquelle fonctionne.
 */
fun isRecoverableStreamError(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_UNSPECIFIED,
    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
    PlaybackException.ERROR_CODE_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    -> true
    else -> false
}

/** Relances consécutives sans nouvelle image avant d'abandonner (≈ 1 min 15 au total). */
const val MAX_STREAM_RECOVERY_ATTEMPTS = 8

/** Attente avant la relance n° [attempt] (1, 2, 4, 8 s puis 15 s) : laisse le réseau revenir. */
fun streamRecoveryDelayMs(attempt: Int): Long =
    (1_000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(15_000L)

/** Relance programmée d'un flux : [attempt] distingue deux relances successives de la même URL. */
data class StreamRecovery(val url: String, val positionMs: Long, val attempt: Int)

package fr.streamia.tv.player

import androidx.media3.common.PlaybackException

/**
 * Échec du décodeur (format absent ou au-delà des capacités du boîtier : HEVC 4K sur un vieux
 * boîtier, AV1…) : changer d'URL (TS/HLS, HTTP/HTTPS) n'y changera rien, le message doit le dire.
 */
fun isDecoderError(errorCode: Int): Boolean =
    errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED

fun unsupportedFormatMessage(mimeType: String?, width: Int, height: Int): String {
    if (mimeType?.startsWith("audio/") == true) {
        return "Ce boîtier ne sait pas décoder le son de ce contenu (${mimeType.substringAfter('/').uppercase()}). " +
            "Choisissez une autre piste audio ou une autre version."
    }
    val codec = mimeType?.let {
        when {
            "dolby-vision" in it -> "Dolby Vision"
            "hevc" in it -> "HEVC"
            "av01" in it -> "AV1"
            "vp9" in it -> "VP9"
            "avc" in it -> "H.264"
            else -> null
        }
    }
    val resolution = if (width > 0 && height > 0) "${width}×$height" else null
    val detail = listOfNotNull(codec, resolution).joinToString(" · ")
    return "Ce boîtier ne sait pas décoder cette vidéo" + (if (detail.isNotEmpty()) " ($detail)" else "") +
        ". Essayez une autre version de ce contenu (HD par exemple)."
}

package fr.streamia.tv.ui

import androidx.media3.common.Format
import fr.streamia.tv.player.PlaybackDiagnostics
import fr.streamia.tv.player.StreamTechnicalInfo
import fr.streamia.tv.player.codecLabel
import fr.streamia.tv.player.hdrLabel
import fr.streamia.tv.player.isDolbyVisionFormat

// Informations techniques et diagnostics affichés par le lecteur.

/** Résolution, fps annoncés, codec, débit et HDR du format vidéo en cours. */
internal fun liveTechnicalInfo(format: Format): StreamTechnicalInfo? {
    if (format.width <= 0 || format.height <= 0) return null
    val isDolbyVision = isDolbyVisionFormat(format.sampleMimeType, format.codecs)
    return StreamTechnicalInfo(
        width = format.width,
        height = format.height,
        frameRate = format.frameRate.takeIf { it > 0f },
        codec = if (isDolbyVision) "Dolby Vision" else codecLabel(format.sampleMimeType, format.codecs),
        bitrate = format.bitrate.takeIf { it > 0 },
        hdr = if (isDolbyVision) "Dolby Vision" else hdrLabel(format.sampleMimeType, format.colorInfo?.colorTransfer),
    )
}

internal fun streamTransportLabel(url: String): String {
    if (url.isBlank()) return "Transport —"
    val scheme = url.substringBefore("://", "").uppercase().ifBlank { "HTTP" }
    val extension = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
    val container = when (extension) {
        "m3u8" -> "HLS"
        "ts" -> "TS"
        else -> extension.uppercase().ifBlank { "AUTO" }
    }
    return "$scheme · $container"
}

internal fun diagnosticsText(value: PlaybackDiagnostics): String {
    val startup = value.startupTimeMs?.let { "démarrage ${it} ms" } ?: "démarrage…"
    val rebuffer = if (value.rebufferCount == 0) {
        "0 rebuffer"
    } else {
        "${value.rebufferCount} rebuffer · ${value.totalRebufferTimeMs} ms"
    }
    return "$startup · $rebuffer"
}

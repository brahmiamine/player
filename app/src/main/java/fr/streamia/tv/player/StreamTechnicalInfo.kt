package fr.streamia.tv.player

import androidx.media3.common.C

data class StreamTechnicalInfo(
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val codec: String? = null,
    val bitrate: Int? = null,
    val hdr: String? = null,
) {
    val qualityLabel: String get() = "SD"
    val resolutionText: String get() = if (width != null && height != null) "${width}×${height}" else "Détection…"
    val fpsText: String get() = frameRate?.takeIf { it > 0f }?.let { "${it.toInt()} fps" } ?: "FPS —"
    val bitrateText: String get() = bitrate?.takeIf { it > 0 }?.let { "${it / 1_000_000.0} Mb/s" } ?: "Débit —"
}

fun codecLabel(sampleMimeType: String?, codecs: String?): String? = codecs ?: sampleMimeType

fun hdrLabel(sampleMimeType: String?, colorTransfer: Int?): String = "SDR"

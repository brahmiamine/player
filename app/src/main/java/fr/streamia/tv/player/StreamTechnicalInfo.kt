package fr.streamia.tv.player

import androidx.media3.common.C
import java.util.Locale
import kotlin.math.abs

data class StreamTechnicalInfo(
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val codec: String? = null,
    val bitrate: Int? = null,
    val hdr: String? = null,
) {
    val qualityLabel: String
        get() = when {
            width == null || height == null -> "—"
            width >= 3840 || height >= 2160 -> "4K UHD"
            width >= 2560 || height >= 1440 -> "QHD"
            width >= 1920 || height >= 1080 -> "FHD"
            width >= 1280 || height >= 720 -> "HD"
            else -> "SD"
        }

    val resolutionText: String
        get() = if (width != null && height != null) "${width}×${height}" else "Détection…"

    val fpsText: String
        get() = frameRate?.takeIf { it > 0f }?.let { value ->
            val rounded = value.toInt()
            val rendered = if (abs(value - rounded) < 0.01f) {
                rounded.toString()
            } else {
                String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
            }
            "$rendered fps"
        } ?: "FPS —"

    val bitrateText: String
        get() = bitrate?.takeIf { it > 0 }?.let { value ->
            if (value >= 1_000_000) {
                String.format(Locale.US, "%.1f Mb/s", value / 1_000_000.0)
            } else {
                "${value / 1_000} kb/s"
            }
        } ?: "Débit —"
}

fun codecLabel(sampleMimeType: String?, codecs: String?): String? = when (sampleMimeType?.lowercase()) {
    "video/avc" -> "H.264 / AVC"
    "video/hevc" -> "H.265 / HEVC"
    "video/dolby-vision" -> "Dolby Vision"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/x-vnd.on2.vp8" -> "VP8"
    else -> codecs?.takeIf(String::isNotBlank) ?: sampleMimeType?.takeIf(String::isNotBlank)
}

fun hdrLabel(sampleMimeType: String?, colorTransfer: Int?): String = when {
    sampleMimeType.equals("video/dolby-vision", ignoreCase = true) -> "Dolby Vision"
    colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10 / PQ"
    colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
    else -> "SDR"
}

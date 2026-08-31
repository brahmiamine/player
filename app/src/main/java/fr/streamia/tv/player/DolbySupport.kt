package fr.streamia.tv.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import android.view.WindowManager

data class DolbyDeviceCapabilities(
    val dolbyVision: Boolean = false,
    val dolbyAtmos: Boolean = false,
)

object DolbyCapabilityDetector {
    fun detect(context: Context): DolbyDeviceCapabilities = DolbyDeviceCapabilities(
        dolbyVision = supportsDolbyVision(context),
        dolbyAtmos = supportsDolbyAtmos(context),
    )

    private fun supportsDolbyVision(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val displaySupports = currentDisplay(context)
            ?.hdrCapabilities
            ?.supportedHdrTypes
            ?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
        return displaySupports && hasDecoder(DOLBY_VISION_MIME)
    }

    private fun supportsDolbyAtmos(context: Context): Boolean {
        val decoderSupports = hasDecoder(DOLBY_ATMOS_MIME)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return decoderSupports
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val outputSupports = runCatching {
            audioManager
                ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.any { device -> device.encodings.any { it == AudioFormat.ENCODING_E_AC3_JOC } }
                ?: false
        }.getOrDefault(false)
        return outputSupports || decoderSupports
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(context: Context): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }

    private fun hasDecoder(mimeType: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
    }.getOrDefault(false)
}

fun isDolbyVisionFormat(sampleMimeType: String?, codecs: String?): Boolean {
    if (sampleMimeType.equals(DOLBY_VISION_MIME, ignoreCase = true)) return true
    val normalized = codecs?.lowercase().orEmpty()
    return listOf("dvhe", "dvh1", "dvav", "dva1").any(normalized::contains)
}

fun isDolbyAtmosFormat(sampleMimeType: String?): Boolean =
    sampleMimeType.equals(DOLBY_ATMOS_MIME, ignoreCase = true)

fun dolbyPlaybackLabel(name: String, contentDetected: Boolean, outputSupported: Boolean): String? = when {
    !contentDetected -> null
    outputSupported -> "$name actif"
    else -> "$name détecté · sortie non compatible"
}

const val DOLBY_VISION_MIME = "video/dolby-vision"
const val DOLBY_ATMOS_MIME = "audio/eac3-joc"

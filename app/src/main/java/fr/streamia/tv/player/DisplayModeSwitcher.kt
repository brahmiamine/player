package fr.streamia.tv.player

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/** Un mode de sortie HDMI proposé par l'écran. */
data class DisplayModeSpec(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * Choisit le mode d'écran adapté à une vidéo : une résolution au moins égale à la vidéo (un film
 * 4K ne sort plus en 1080p sur un boîtier dont l'interface est en 1080p), sans jamais descendre
 * sous la résolution courante, et une fréquence multiple de celle de la vidéo (24 i/s → 24 Hz,
 * 25/50 i/s → 50 Hz…) pour supprimer les saccades. Null si le mode courant convient déjà.
 */
fun bestDisplayMode(
    modes: List<DisplayModeSpec>,
    current: DisplayModeSpec,
    videoWidth: Int,
    videoHeight: Int,
    videoFrameRate: Float,
): DisplayModeSpec? {
    val minWidth = maxOf(videoWidth, current.width)
    val minHeight = maxOf(videoHeight, current.height)
    val resolution = modes
        .filter { it.width >= minWidth && it.height >= minHeight }
        .minByOrNull { it.width.toLong() * it.height }
        ?.let { it.width to it.height }
        ?: (current.width to current.height)
    val sameResolution = modes.filter { (it.width to it.height) == resolution }.ifEmpty { return null }
    val rateMatch = if (videoFrameRate > 0f) {
        // Multiple entier le plus bas (23,976 → 23,976 Hz, sinon 47,95 ; 25 → 25, sinon 50…).
        sameResolution
            .filter { mode ->
                val ratio = mode.refreshRate / videoFrameRate
                ratio >= 0.99f && abs(ratio - ratio.roundToInt()) < 0.01f
            }
            .minByOrNull { it.refreshRate }
    } else {
        null
    }
    val chosen = rateMatch
        ?: sameResolution.firstOrNull { abs(it.refreshRate - current.refreshRate) < 0.05f }
        ?: sameResolution.maxBy { it.refreshRate }
    return chosen.takeIf { it.id != current.id }
}

/** Applique [bestDisplayMode] à la fenêtre de l'activité ; [reset] rend la main au système. */
object DisplayModeSwitcher {
    fun apply(activity: Activity, videoWidth: Int, videoHeight: Int, videoFrameRate: Float) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val display = display(activity) ?: return
        val modes = display.supportedModes.map(::spec)
        val target = bestDisplayMode(modes, spec(display.mode), videoWidth, videoHeight, videoFrameRate) ?: return
        val window = activity.window
        if (window.attributes.preferredDisplayModeId == target.id) return
        window.attributes = window.attributes.apply { preferredDisplayModeId = target.id }
    }

    fun reset(activity: Activity) {
        val window = activity.window
        if (window.attributes.preferredDisplayModeId == 0) return
        window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
    }

    private fun spec(mode: Display.Mode) = DisplayModeSpec(mode.modeId, mode.physicalWidth, mode.physicalHeight, mode.refreshRate)

    @Suppress("DEPRECATION")
    private fun display(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay
}

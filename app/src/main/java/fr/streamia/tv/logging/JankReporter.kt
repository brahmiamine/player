package fr.streamia.tv.logging

import android.view.Window
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState

/**
 * Images saccadées mesurées en usage réel (JankStats), comptées par écran. À chaque changement
 * d'écran, le bilan de l'écran quitté (images saccadées / images dessinées) est ajouté au journal
 * Crashlytics : les rapports indiquent quels écrans saccadent sur quels boîtiers.
 */
class JankReporter private constructor(window: Window) {
    private val metricsState = PerformanceMetricsState.getHolderForHierarchy(window.decorView)
    @Volatile private var screen = "start"
    @Volatile private var frames = 0
    @Volatile private var janky = 0

    private val jankStats = JankStats.createAndTrack(window) { frame ->
        frames++
        if (frame.isJank) janky++
    }

    fun onScreen(name: String) {
        if (name == screen) return
        flush()
        screen = name
        metricsState.state?.putState(STATE_SCREEN, name)
    }

    fun setTracking(enabled: Boolean) {
        jankStats.isTrackingEnabled = enabled
    }

    private fun flush() {
        val total = frames
        val bad = janky
        frames = 0
        janky = 0
        if (total < MIN_FRAMES) return
        CrashReporter.log("jank screen=$screen janky=$bad frames=$total")
    }

    companion object {
        private const val STATE_SCREEN = "screen"
        private const val MIN_FRAMES = 30

        fun attach(window: Window): JankReporter = JankReporter(window)
    }
}

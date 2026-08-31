package fr.streamia.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.min

/**
 * Normalise les dimensions de l'interface autour d'une surface logique 1280x720.
 *
 * Android TV peut exposer des densités très différentes selon la marque, la résolution
 * (720p, 1080p, 4K) et le réglage de mise à l'échelle. En ajustant LocalDensity une seule
 * fois à la racine, tous les écrans qui utilisent des dp/sp conservent les mêmes proportions
 * sans devoir dupliquer des variantes pour chaque téléviseur.
 */
@Composable
fun ResponsiveTvViewport(content: @Composable () -> Unit) {
    val systemDensity = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthScale = maxWidth.value / REFERENCE_WIDTH_DP
        val heightScale = maxHeight.value / REFERENCE_HEIGHT_DP
        val viewportScale = min(widthScale, heightScale).coerceIn(MIN_SCALE, MAX_SCALE)
        val responsiveDensity = Density(
            density = systemDensity.density * viewportScale,
            fontScale = systemDensity.fontScale,
        )

        CompositionLocalProvider(LocalDensity provides responsiveDensity) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

private const val REFERENCE_WIDTH_DP = 1280f
private const val REFERENCE_HEIGHT_DP = 720f
private const val MIN_SCALE = 0.45f
private const val MAX_SCALE = 1.80f

package fr.streamia.tv.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    // Téléphone ou tablette (pas d'Android TV) : écran tenu en main, regardé de près. Surface de
    // référence un peu plus petite, donc textes et boutons ~12 % plus grands, plus faciles à toucher.
    val handheld = remember(context) { !context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    val referenceScale = if (handheld) HANDHELD_REFERENCE_RATIO else 1f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthScale = maxWidth.value / (REFERENCE_WIDTH_DP * referenceScale)
        val heightScale = maxHeight.value / (REFERENCE_HEIGHT_DP * referenceScale)
        val viewportScale = min(widthScale, heightScale).coerceIn(MIN_SCALE, MAX_SCALE)
        val responsiveDensity = Density(
            density = systemDensity.density * viewportScale,
            fontScale = systemDensity.fontScale,
        )

        CompositionLocalProvider(LocalDensity provides responsiveDensity, LocalHandheld provides handheld) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

/**
 * Vrai sur téléphone ou tablette, faux sur Android TV : les commandes au doigt n'existent que sur les premiers,
 * la TV se pilote uniquement à la télécommande (même avec une télécommande à pointeur).
 */
val LocalHandheld = staticCompositionLocalOf { false }

private const val REFERENCE_WIDTH_DP = 1280f
private const val REFERENCE_HEIGHT_DP = 720f
/** 1138x640 au lieu de 1280x720 sur téléphone : les écrans défilent déjà verticalement. */
private const val HANDHELD_REFERENCE_RATIO = 0.89f
private const val MIN_SCALE = 0.45f
private const val MAX_SCALE = 1.80f

package fr.streamia.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkLight
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.GlassScrim
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RadiusCard
import fr.streamia.tv.ui.theme.RadiusPill

/**
 * Un des larges dégradés diffus placés derrière le verre (thème iOS Glass), en fraction de la
 * taille de l'écran plutôt qu'en pixels absolus — comme les positions top/left en pourcentage du
 * prototype HTML — pour s'adapter à n'importe quelle résolution de télévision. Le centre peut
 * sortir du cadre (fraction négative ou > 1), exactement comme les blobs du prototype qui
 * débordent hors du canevas pour un halo diffus plutôt qu'un cercle net.
 */
data class GlassBlob(val center: Offset, val radiusFraction: Float, val color: Color)

/**
 * État de flou partagé par tous les panneaux de verre d'un même écran : le fond (dégradés +
 * contenu défilant) est déclaré une seule fois comme source, chaque panneau de verre s'y
 * raccorde. `null` en dehors de [StreamiaApp] (previews) — chaque composant de verre retombe
 * alors sur un aplat teinté simple.
 */
val LocalGlassHaze = compositionLocalOf<HazeState?> { null }

/** Fond plein écran d'un écran du thème iOS Glass : base quasi noire + dégradés diffus propres à
 * l'écran (voir les jeux de blobs par écran dans chaque fichier écran de ce package). */
@Composable
fun GlassBackdrop(blobs: List<GlassBlob>, modifier: Modifier = Modifier) {
    val hazeState = LocalGlassHaze.current
    Box(
        modifier
            .fillMaxSize()
            .background(Night)
            .then(if (hazeState != null) Modifier.hazeSource(hazeState) else Modifier)
            .drawBehind {
                blobs.forEach { blob ->
                    val radius = blob.radiusFraction * size.maxDimension
                    val center = Offset(blob.center.x * size.width, blob.center.y * size.height)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(blob.color.copy(alpha = 0.8f), blob.color.copy(alpha = 0f)),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                }
            },
    ) {}
}

/**
 * Panneau de verre "structurel" (barre de navigation, panneau latéral, formulaire, HUD…) : flou
 * temps réel de ce qu'il y a derrière sur Android 12+ (repli en aplat teinté sinon, voir
 * [fr.streamia.tv.ui.theme.GlassScrim]). Réservé aux éléments peu nombreux par écran — les lignes
 * répétées d'une grille/liste (chaînes, épisodes…) utilisent [FocusableSurface], moins coûteux,
 * pour ne pas dégrader le défilement de gros catalogues.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(RadiusCard),
    tintColor: Color = Color.White.copy(alpha = 0.09f),
    borderColor: Color = GlassBorder,
    blurRadius: Dp = 40.dp,
    elevation: Dp = 18.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val hazeState = LocalGlassHaze.current
    Box(
        modifier
            .shadow(elevation, shape, clip = false)
            .clip(shape)
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            tint = HazeTint(tintColor),
                            blurRadius = blurRadius,
                            noiseFactor = 0f,
                            fallbackTint = HazeTint(GlassScrim),
                        ),
                    )
                } else {
                    Modifier.background(GlassScrim)
                },
            )
            .border(BorderStroke(1.dp, borderColor), shape),
        content = content,
    )
}

/**
 * Dégradés propres à chaque écran, repris des positions/couleurs du prototype
 * `Streamia TV - iOS Glass.dc.html` (fractions calculées depuis le canevas 1920×1080 du
 * prototype). Le Lecteur reprend les couleurs de son overlay HUD mais assombries (le prototype
 * les affiche à `opacity:0.5`, la vidéo occupant le plan principal) ; les écrans absents du
 * prototype (Matchs du jour…) reprennent la palette de l'écran dont ils dérivent.
 */
fun glassBlobsFor(screen: StreamiaScreen): List<GlassBlob> = when (screen) {
    is StreamiaScreen.Login -> listOf(
        GlassBlob(Offset(0.11f, 0.12f), 0.234f, Color(0xFF5E5CE6)),
        GlassBlob(Offset(0.91f, 0.91f), 0.208f, Color(0xFFFF375F)),
    )
    is StreamiaScreen.Home -> listOf(
        GlassBlob(Offset(0.13f, 0.12f), 0.234f, Color(0xFFFF375F)),
        GlassBlob(Offset(0.93f, 0.56f), 0.208f, Color(0xFF5E5CE6)),
        GlassBlob(Offset(0.58f, 0.95f), 0.182f, Color(0xFF0A84FF)),
    )
    is StreamiaScreen.Browser -> listOf(
        GlassBlob(Offset(0.88f, 0.10f), 0.234f, Color(0xFF0A84FF)),
        GlassBlob(Offset(0.09f, 0.94f), 0.182f, Color(0xFFFF375F)),
    )
    is StreamiaScreen.MovieDetails -> listOf(
        GlassBlob(Offset(0.13f, 0.12f), 0.234f, Color(0xFFBF5AF2)),
        GlassBlob(Offset(0.91f, 0.91f), 0.208f, Color(0xFFFF375F)),
    )
    is StreamiaScreen.Series -> listOf(
        GlassBlob(Offset(0.87f, 0.12f), 0.234f, Color(0xFF30D158)),
        GlassBlob(Offset(0.09f, 0.94f), 0.182f, Color(0xFFFF375F)),
    )
    is StreamiaScreen.Player -> listOf(
        GlassBlob(Offset(0.88f, 0.11f), 0.260f, Color(0xFF0A84FF).copy(alpha = 0.5f)),
        GlassBlob(Offset(0.10f, 0.93f), 0.208f, Color(0xFFFF375F).copy(alpha = 0.5f)),
    )
    is StreamiaScreen.Epg -> listOf(
        GlassBlob(Offset(0.13f, 0.12f), 0.234f, Color(0xFF0A84FF)),
        GlassBlob(Offset(0.91f, 0.92f), 0.182f, Color(0xFFFF375F)),
    )
    is StreamiaScreen.Search -> listOf(
        GlassBlob(Offset(0.88f, 0.10f), 0.234f, Color(0xFF5E5CE6)),
        GlassBlob(Offset(0.09f, 0.92f), 0.182f, Color(0xFFFF375F)),
    )
    is StreamiaScreen.Settings -> listOf(
        GlassBlob(Offset(0.13f, 0.12f), 0.234f, Color(0xFFFF9F0A)),
        GlassBlob(Offset(0.91f, 0.91f), 0.208f, Color(0xFFFF375F)),
    )
    is StreamiaScreen.Organizer -> listOf(
        GlassBlob(Offset(0.87f, 0.12f), 0.234f, Color(0xFF30D158)),
        GlassBlob(Offset(0.09f, 0.92f), 0.182f, Color(0xFFFF375F)),
    )
    is StreamiaScreen.ParentalControl -> listOf(
        GlassBlob(Offset(0.88f, 0.10f), 0.234f, Color(0xFFFF9F0A)),
        GlassBlob(Offset(0.09f, 0.92f), 0.182f, Color(0xFFFF375F)),
    )
    is StreamiaScreen.Tools, is StreamiaScreen.About -> listOf(
        GlassBlob(Offset(0.13f, 0.12f), 0.234f, Color(0xFF0A84FF)),
        GlassBlob(Offset(0.91f, 0.91f), 0.208f, Color(0xFFFF375F)),
    )
    is StreamiaScreen.LiveMatches -> listOf(
        GlassBlob(Offset(0.13f, 0.12f), 0.234f, Color(0xFFFF375F)),
        GlassBlob(Offset(0.93f, 0.56f), 0.208f, Color(0xFF5E5CE6)),
    )
}

/** Bouton d'action principale : pilule dégradé accent avec lueur, texte blanc gras — l'équivalent
 * de `.btn-accent` du prototype. */
@Composable
fun AccentPill(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(RadiusPill),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .shadow(
                elevation = 14.dp,
                shape = shape,
                clip = false,
                ambientColor = AccentPink.copy(alpha = 0.45f),
                spotColor = AccentPink.copy(alpha = 0.45f),
            )
            .clip(shape)
            .background(Brush.verticalGradient(listOf(AccentPinkLight, AccentPink)))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)), shape),
        content = content,
    )
}

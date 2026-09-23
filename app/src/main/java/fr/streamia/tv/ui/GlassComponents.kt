package fr.streamia.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkLight
import fr.streamia.tv.ui.theme.GlassBorder
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

/** Fond plein écran d'un écran du thème iOS Glass : base quasi noire + dégradés diffus propres à
 * l'écran (voir les jeux de blobs par écran dans chaque fichier écran de ce package). */
@Composable
fun GlassBackdrop(blobs: List<GlassBlob>, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Night)
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
 * Panneau de verre "structurel" (barre de navigation, panneau latéral, formulaire, HUD…).
 *
 * Plus de flou temps réel : la seule chose derrière ces panneaux est le fond de dégradés diffus,
 * déjà flou par nature, si bien que le flou (RenderEffect hors écran à chaque panneau) coûtait
 * cher au GPU des boîtiers TV pour une différence invisible. Un voile translucide sur ce même fond
 * donne le même rendu.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(RadiusCard),
    tintColor: Color = Color.White.copy(alpha = 0.09f),
    borderColor: Color = GlassBorder,
    elevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false) else Modifier)
            .clip(shape)
            .background(tintColor)
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

package fr.streamia.tv.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusTile

/*
 * Rangées fantômes de l'accueil, affichées tant qu'un bloc n'a pas fini son premier chargement.
 * Mêmes titres, tailles, marges et surface que les vraies rangées, pour que les cartes réelles
 * prennent exactement leur place. Jamais focusables : la télécommande les ignore.
 */

private const val SKELETON_CARD_COUNT = 6
private val SkeletonFill = MutedInk.copy(alpha = 0.18f)

/** Titre réel (ou barre fantôme si inconnu) + cartes fantômes qui pulsent ensemble. */
@Composable
internal fun SkeletonRow(
    title: String?,
    modifier: Modifier = Modifier,
    card: @Composable () -> Unit,
) {
    val pulse = rememberSkeletonPulse()
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            SectionLabel(title, fontSize = 16.sp)
        } else {
            SkeletonBlock(Modifier.graphicsLayer { alpha = pulse.value }.width(200.dp).height(16.dp))
        }
        Spacer(Modifier.height(10.dp))
        // Comme les vraies rangées (LazyRow) : les cartes gardent leur taille au bord de l'écran.
        LazyRow(
            Modifier.graphicsLayer { alpha = pulse.value },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            userScrollEnabled = false,
        ) {
            items(SKELETON_CARD_COUNT) { card() }
        }
    }
}

/**
 * Opacité pulsée d'un groupe de blocs fantômes. Une seule animation par groupe, à lire en
 * graphicsLayer : aucune recomposition par image, léger pour les petits GPU des boîtiers TV.
 */
@Composable
internal fun rememberSkeletonPulse(): State<Float> = rememberInfiniteTransition(label = "skeleton")
    .animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "skeleton-alpha")

/** Carte programme (FR en direct / ce soir, beIN, UK) : voir ProgrammeCard. */
@Composable
internal fun ProgrammeCardSkeleton() = SkeletonSurface(220.dp, 215.dp, padding = 9.dp) {
    SkeletonBlock(Modifier.fillMaxWidth().height(92.dp), RoundedCornerShape(8.dp))
    Spacer(Modifier.height(9.dp))
    SkeletonBlock(Modifier.fillMaxWidth().height(13.dp))
    Spacer(Modifier.height(6.dp))
    SkeletonBlock(Modifier.fillMaxWidth(0.6f).height(13.dp))
    Spacer(Modifier.weight(1f))
    ChannelLineSkeleton(logo = 44.dp)
}

/** Carte match en direct (liveonsat) : voir LiveMatchesRow. */
@Composable
internal fun LiveMatchCardSkeleton() = SkeletonSurface(260.dp, 170.dp, padding = 10.dp) {
    SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(11.dp))
    Spacer(Modifier.height(10.dp))
    TeamLineSkeleton(logo = 24.dp)
    Spacer(Modifier.height(8.dp))
    TeamLineSkeleton(logo = 24.dp)
    Spacer(Modifier.weight(1f))
    ChannelLineSkeleton(logo = 36.dp)
}

/** Carte score football : voir FootballMatchCard. */
@Composable
internal fun FootballCardSkeleton() = SkeletonSurface(260.dp, 150.dp, padding = 10.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SkeletonBlock(Modifier.size(22.dp), CircleShape)
        Spacer(Modifier.width(6.dp))
        SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(11.dp))
    }
    Spacer(Modifier.height(8.dp))
    TeamLineSkeleton(logo = 28.dp)
    Spacer(Modifier.height(6.dp))
    TeamLineSkeleton(logo = 28.dp)
    Spacer(Modifier.weight(1f))
    SkeletonBlock(Modifier.width(48.dp).height(12.dp))
}

/** Carte recommandation (affiche) : voir HomeRecommendationCard. */
@Composable
internal fun PosterCardSkeleton() = SkeletonSurface(HomeCardWidth, HomeCardHeight, padding = 9.dp) {
    SkeletonBlock(Modifier.fillMaxWidth().height(128.dp), RoundedCornerShape(9.dp))
    Spacer(Modifier.height(9.dp))
    SkeletonBlock(Modifier.fillMaxWidth().height(12.dp))
    Spacer(Modifier.height(6.dp))
    SkeletonBlock(Modifier.fillMaxWidth(0.65f).height(12.dp))
    Spacer(Modifier.weight(1f))
    SkeletonBlock(Modifier.fillMaxWidth(0.45f).height(12.dp))
}

/** Même surface verre au repos que FocusableSurface (fond, bordure, arrondi), sans focus. */
@Composable
private fun SkeletonSurface(width: Dp, height: Dp, padding: Dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(RadiusTile)
    Column(
        Modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(DeepSurface, shape)
            .border(1.dp, GlassBorder, shape)
            .padding(padding),
        content = content,
    )
}

@Composable
private fun TeamLineSkeleton(logo: Dp) = Row(verticalAlignment = Alignment.CenterVertically) {
    SkeletonBlock(Modifier.size(logo), CircleShape)
    Spacer(Modifier.width(8.dp))
    SkeletonBlock(Modifier.fillMaxWidth(0.6f).height(14.dp))
}

@Composable
private fun ChannelLineSkeleton(logo: Dp) = Row(verticalAlignment = Alignment.CenterVertically) {
    SkeletonBlock(Modifier.size(logo), RoundedCornerShape(9.dp))
    Spacer(Modifier.width(8.dp))
    SkeletonBlock(Modifier.width(90.dp).height(12.dp))
}

@Composable
internal fun SkeletonBlock(modifier: Modifier, shape: Shape = RoundedCornerShape(4.dp)) =
    Box(modifier.clip(shape).background(SkeletonFill, shape))

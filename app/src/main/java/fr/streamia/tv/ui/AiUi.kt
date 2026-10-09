package fr.streamia.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.recommendation.RecommendedMedia
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkLight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import kotlinx.coroutines.delay

/** Similaires dans l'ordre proposé par l'IA ([keys]) ; ceux qu'elle n'a pas classés restent derrière, dans l'ordre du moteur. */
fun List<RecommendedMedia>.withAiOrder(keys: List<String>?): List<RecommendedMedia> {
    if (keys.isNullOrEmpty() || size < 2) return this
    val rank = keys.withIndex().associate { it.value to it.index }
    return sortedBy { rank[it.entry.key] ?: Int.MAX_VALUE }
}

/** Icône « IA » animée : une grande étincelle qui tourne et pulse, deux petites qui scintillent en décalé. */
@Composable
fun AiSparkle(modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val transition = rememberInfiniteTransition(label = "ai-sparkle")
    val turn by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "turn")
    val pulse by transition.animateFloat(0.75f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    val twinkle by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "twinkle")
    Canvas(modifier.size(size)) {
        val brush = Brush.linearGradient(listOf(AccentPinkLight, AccentPink, Ink.copy(alpha = 0.9f)))
        val main = this.size.minDimension * 0.62f
        val center = Offset(this.size.width * 0.42f, this.size.height * 0.58f)
        rotate(turn, center) { scale(pulse, center) { drawSparkle(center, main, brush) } }
        drawSparkle(Offset(this.size.width * 0.82f, this.size.height * 0.2f), main * 0.42f * (0.5f + twinkle / 2), brush)
        drawSparkle(Offset(this.size.width * 0.86f, this.size.height * 0.78f), main * 0.3f * (1f - twinkle / 2), brush)
    }
}

/** Étoile à quatre branches aux flancs incurvés, de diamètre [diameter] centrée sur [center]. */
private fun DrawScope.drawSparkle(center: Offset, diameter: Float, brush: Brush) {
    val r = diameter / 2
    val path = Path().apply {
        moveTo(center.x, center.y - r)
        quadraticTo(center.x, center.y, center.x + r, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + r)
        quadraticTo(center.x, center.y, center.x - r, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - r)
        close()
    }
    drawPath(path, brush)
}

/**
 * « ✦ Traduction de la description… » pendant qu'une fonction IA travaille. N'apparaît qu'après un court délai :
 * une réponse lue dans le cache (quasi instantanée) ne fait pas clignoter l'indicateur.
 */
@Composable
fun AiLoadingIndicator(label: String, visible: Boolean, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) delay(AI_INDICATOR_DELAY_MS)
        shown = visible
    }
    AnimatedVisibility(shown, modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiSparkle()
            Spacer(Modifier.width(8.dp))
            Text(label, color = MutedInk, fontSize = 14.sp)
        }
    }
}

private const val AI_INDICATOR_DELAY_MS = 250L

/** Début du statut de recherche de sous-titres pendant leur traduction par l'IA (le lecteur y ajoute l'icône animée). */
const val AI_SUBTITLE_STATUS_PREFIX = "Traduction IA"

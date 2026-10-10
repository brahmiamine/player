package fr.streamia.tv.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.Ink

/** « ← Retour » des boutons de retour : la flèche est une icône centrée sur le texte (le glyphe « ← » flottait sous la ligne). */
@Composable
fun BackLabel(fontSize: TextUnit, horizontalPadding: Dp = 14.dp) {
    Row(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)) {
        StreamiaIcon(StreamiaIconGlyph.ChevronLeft, tint = Ink, size = 24.dp)
        Text("Retour", color = Ink, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

/** Petit anneau qui tourne : recherche en cours dans un bouton. */
@Composable
fun ButtonSpinner(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = Ink) {
    val turn by rememberInfiniteTransition(label = "button-spinner").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "turn")
    Canvas(modifier.size(size)) {
        val width = this.size.minDimension * 0.13f
        drawArc(
            color = color.copy(alpha = 0.25f), startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(width / 2, width / 2), size = Size(this.size.width - width, this.size.height - width), style = Stroke(width),
        )
        drawArc(
            color = color, startAngle = turn, sweepAngle = 100f, useCenter = false,
            topLeft = Offset(width / 2, width / 2), size = Size(this.size.width - width, this.size.height - width), style = Stroke(width, cap = StrokeCap.Round),
        )
    }
}

/** Chargement d'une lecture (Direct, film, série, reprise) : un anneau rose seul, sans texte. */
@Composable
fun PlaybackLoader(modifier: Modifier = Modifier, size: Dp = 84.dp) {
    val turn by rememberInfiniteTransition(label = "playback-loader").animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "turn")
    Canvas(modifier.size(size)) {
        val width = this.size.minDimension * 0.048f
        val arcSize = Size(this.size.width - width, this.size.height - width)
        val topLeft = Offset(width / 2, width / 2)
        drawArc(
            color = Color.White.copy(alpha = 0.14f), startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = topLeft, size = arcSize, style = Stroke(width),
        )
        drawArc(
            color = AccentPink, startAngle = turn, sweepAngle = 90f, useCenter = false,
            topLeft = topLeft, size = arcSize, style = Stroke(width, cap = StrokeCap.Round),
        )
    }
}

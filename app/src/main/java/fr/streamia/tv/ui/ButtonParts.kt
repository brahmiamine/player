package fr.streamia.tv.ui

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
import fr.streamia.tv.ui.theme.Ink

/** « ← Retour » des boutons de retour : la flèche est une icône centrée sur le texte (le glyphe « ← » flottait sous la ligne). */
@Composable
fun BackLabel(fontSize: TextUnit, horizontalPadding: Dp = 14.dp) {
    Row(Modifier.padding(horizontal = horizontalPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StreamiaIcon(StreamiaIconGlyph.ArrowBack, tint = Ink, size = 20.dp)
        Text("Retour", color = Ink, fontSize = fontSize)
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

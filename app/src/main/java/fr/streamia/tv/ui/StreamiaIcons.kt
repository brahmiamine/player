package fr.streamia.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.streamia.tv.ui.theme.FocusBlueBright
import kotlin.math.cos
import kotlin.math.sin

/**
 * Petit jeu d'icônes vectorielles maison — un seul style (traits arrondis, grille normalisée
 * 24dp), plutôt que des glyphes Unicode dont le rendu dépend de la police du fabricant TV, ou une
 * dépendance Material Icons Extended dont l'empreinte APK réelle n'est pas mesurable dans cet
 * environnement (pas de SDK Android pour compiler et constater le résultat du tree-shaking R8).
 */
enum class StreamiaIconGlyph {
    Live, Movie, Series, Search, Guide, Settings, Refresh, Swap,
    Star, StarOutline, ChevronUp, ChevronDown, CheckboxOn, CheckboxOff, ArrowBack, ArrowForward,
    Reorder, Delete, Lock,
}

@Composable
fun StreamiaIcon(
    glyph: StreamiaIconGlyph,
    modifier: Modifier = Modifier,
    tint: Color = FocusBlueBright,
    size: Dp = 24.dp,
) {
    Canvas(modifier.size(size)) {
        // Trait plus fin (Phosphor "regular" plutôt que "bold") pour rester dans le registre
        // discret de Nocturne — un trait, jamais un aplat.
        val stroke = Stroke(width = this.size.minDimension * 0.072f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            StreamiaIconGlyph.Live -> drawLive(tint, stroke)
            StreamiaIconGlyph.Movie -> drawMovie(tint)
            StreamiaIconGlyph.Series -> drawSeries(tint, stroke)
            StreamiaIconGlyph.Search -> drawSearch(tint, stroke)
            StreamiaIconGlyph.Guide -> drawGuide(tint, stroke)
            StreamiaIconGlyph.Settings -> drawSettings(tint, stroke)
            StreamiaIconGlyph.Refresh -> drawRefresh(tint, stroke)
            StreamiaIconGlyph.Swap -> drawSwap(tint, stroke)
            StreamiaIconGlyph.Star -> drawStar(tint, filled = true, stroke = stroke)
            StreamiaIconGlyph.StarOutline -> drawStar(tint, filled = false, stroke = stroke)
            StreamiaIconGlyph.ChevronUp -> drawChevron(tint, stroke, up = true)
            StreamiaIconGlyph.ChevronDown -> drawChevron(tint, stroke, up = false)
            StreamiaIconGlyph.CheckboxOn -> drawCheckbox(tint, stroke, checked = true)
            StreamiaIconGlyph.CheckboxOff -> drawCheckbox(tint, stroke, checked = false)
            StreamiaIconGlyph.ArrowBack -> drawArrow(tint, stroke, pointRight = false)
            StreamiaIconGlyph.ArrowForward -> drawArrow(tint, stroke, pointRight = true)
            StreamiaIconGlyph.Reorder -> drawReorder(tint, stroke)
            StreamiaIconGlyph.Delete -> drawDelete(tint, stroke)
            StreamiaIconGlyph.Lock -> drawLock(tint, stroke)
        }
    }
}

/** Petit indicateur d'état rond (mémoire tampon, vérification en cours…) : dessiné plutôt que le
 * glyphe "●", dont la disponibilité dans la police système d'un boîtier TV n'est pas garantie. */
@Composable
fun StatusDot(modifier: Modifier = Modifier, color: Color = FocusBlueBright, diameter: Dp = 10.dp) {
    Canvas(modifier.size(diameter)) { drawCircle(color) }
}

private fun DrawScope.pt(fx: Float, fy: Float) = Offset(size.width * fx, size.height * fy)

private fun DrawScope.drawLive(tint: Color, stroke: Stroke) {
    drawRoundRect(
        tint,
        topLeft = pt(0.12f, 0.16f),
        size = Size(size.width * 0.76f, size.height * 0.52f),
        cornerRadius = CornerRadius(size.width * 0.07f),
        style = stroke,
    )
    drawLine(tint, pt(0.5f, 0.68f), pt(0.5f, 0.80f), stroke.width, cap = StrokeCap.Round)
    drawLine(tint, pt(0.32f, 0.82f), pt(0.68f, 0.82f), stroke.width, cap = StrokeCap.Round)
}

private fun DrawScope.drawMovie(tint: Color) {
    val path = Path().apply {
        moveTo(pt(0.30f, 0.20f).x, pt(0.30f, 0.20f).y)
        lineTo(pt(0.30f, 0.80f).x, pt(0.30f, 0.80f).y)
        lineTo(pt(0.80f, 0.50f).x, pt(0.80f, 0.50f).y)
        close()
    }
    drawPath(path, tint, style = Fill)
}

private fun DrawScope.drawSeries(tint: Color, stroke: Stroke) {
    drawRoundRect(
        tint.copy(alpha = 0.45f),
        topLeft = pt(0.28f, 0.14f),
        size = Size(size.width * 0.56f, size.height * 0.50f),
        cornerRadius = CornerRadius(size.width * 0.06f),
        style = stroke,
    )
    drawRoundRect(
        tint,
        topLeft = pt(0.16f, 0.34f),
        size = Size(size.width * 0.62f, size.height * 0.50f),
        cornerRadius = CornerRadius(size.width * 0.06f),
        style = stroke,
    )
}

private fun DrawScope.drawSearch(tint: Color, stroke: Stroke) {
    val center = pt(0.42f, 0.42f)
    val radius = size.minDimension * 0.22f
    drawCircle(tint, radius = radius, center = center, style = stroke)
    val handleStart = center + Offset(radius * 0.74f, radius * 0.74f)
    drawLine(tint, handleStart, pt(0.84f, 0.84f), stroke.width, cap = StrokeCap.Round)
}

private fun DrawScope.drawGuide(tint: Color, stroke: Stroke) {
    val w = stroke.width * 0.85f
    drawLine(tint, pt(0.16f, 0.28f), pt(0.84f, 0.28f), w, cap = StrokeCap.Round)
    drawLine(tint, pt(0.16f, 0.50f), pt(0.62f, 0.50f), w, cap = StrokeCap.Round)
    drawLine(tint, pt(0.16f, 0.72f), pt(0.84f, 0.72f), w, cap = StrokeCap.Round)
}

private fun DrawScope.drawSettings(tint: Color, stroke: Stroke) {
    val knobs = listOf(0.30f to 0.36f, 0.50f to 0.64f, 0.70f to 0.46f)
    val lineWidth = stroke.width * 0.72f
    val knobRadius = size.minDimension * 0.065f
    knobs.forEach { (x, knobY) ->
        drawLine(tint, pt(x, 0.18f), pt(x, 0.82f), lineWidth, cap = StrokeCap.Round)
        drawCircle(tint, radius = knobRadius, center = pt(x, knobY))
    }
}

private fun DrawScope.drawRefresh(tint: Color, stroke: Stroke) {
    drawArc(
        tint,
        startAngle = -60f,
        sweepAngle = 300f,
        useCenter = false,
        topLeft = pt(0.18f, 0.18f),
        size = Size(size.width * 0.64f, size.height * 0.64f),
        style = stroke,
    )
}

private fun DrawScope.drawSwap(tint: Color, stroke: Stroke) {
    val lineWidth = stroke.width * 0.9f
    drawLine(tint, pt(0.18f, 0.34f), pt(0.72f, 0.34f), lineWidth, cap = StrokeCap.Round)
    drawArrowhead(tint, tip = pt(0.82f, 0.34f), pointRight = true)
    drawLine(tint, pt(0.82f, 0.66f), pt(0.28f, 0.66f), lineWidth, cap = StrokeCap.Round)
    drawArrowhead(tint, tip = pt(0.18f, 0.66f), pointRight = false)
}

private fun DrawScope.drawArrowhead(tint: Color, tip: Offset, pointRight: Boolean) {
    val dx = size.width * 0.09f
    val dy = size.height * 0.08f
    val back = if (pointRight) tip.x - dx else tip.x + dx
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(back, tip.y - dy)
        lineTo(back, tip.y + dy)
        close()
    }
    drawPath(path, tint, style = Fill)
}

private fun DrawScope.drawArrow(tint: Color, stroke: Stroke, pointRight: Boolean) {
    val startX = if (pointRight) 0.18f else 0.82f
    val endX = if (pointRight) 0.72f else 0.28f
    drawLine(tint, pt(startX, 0.5f), pt(endX, 0.5f), stroke.width, cap = StrokeCap.Round)
    drawArrowhead(tint, tip = pt(if (pointRight) 0.84f else 0.16f, 0.5f), pointRight = pointRight)
}

private fun DrawScope.drawStar(tint: Color, filled: Boolean, stroke: Stroke) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val outerR = size.minDimension * 0.42f
    val innerR = outerR * 0.42f
    val path = Path().apply {
        for (i in 0 until 10) {
            val angleDeg = -90f + i * 36f
            val angleRad = angleDeg * (Math.PI.toFloat() / 180f)
            val r = if (i % 2 == 0) outerR else innerR
            val x = cx + r * cos(angleRad)
            val y = cy + r * sin(angleRad)
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
        fillType = PathFillType.NonZero
    }
    if (filled) {
        drawPath(path, tint, style = Fill)
    } else {
        drawPath(path, tint, style = Stroke(width = stroke.width * 0.75f, join = StrokeJoin.Round))
    }
}

private fun DrawScope.drawChevron(tint: Color, stroke: Stroke, up: Boolean) {
    val midY = if (up) 0.34f else 0.66f
    val sideY = if (up) 0.62f else 0.38f
    val path = Path().apply {
        moveTo(pt(0.24f, sideY).x, pt(0.24f, sideY).y)
        lineTo(pt(0.5f, midY).x, pt(0.5f, midY).y)
        lineTo(pt(0.76f, sideY).x, pt(0.76f, sideY).y)
    }
    drawPath(path, tint, style = stroke)
}

private fun DrawScope.drawReorder(tint: Color, stroke: Stroke) {
    val lineWidth = stroke.width * 0.9f
    drawLine(tint, pt(0.5f, 0.78f), pt(0.5f, 0.22f), lineWidth, cap = StrokeCap.Round)
    drawArrowheadVertical(tint, tip = pt(0.5f, 0.16f), pointDown = false)
    drawArrowheadVertical(tint, tip = pt(0.5f, 0.84f), pointDown = true)
}

private fun DrawScope.drawArrowheadVertical(tint: Color, tip: Offset, pointDown: Boolean) {
    val dx = size.width * 0.08f
    val dy = size.height * 0.09f
    val back = if (pointDown) tip.y - dy else tip.y + dy
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - dx, back)
        lineTo(tip.x + dx, back)
        close()
    }
    drawPath(path, tint, style = Fill)
}

private fun DrawScope.drawDelete(tint: Color, stroke: Stroke) {
    drawLine(tint, pt(0.20f, 0.28f), pt(0.80f, 0.28f), stroke.width, cap = StrokeCap.Round)
    drawLine(tint, pt(0.42f, 0.20f), pt(0.58f, 0.20f), stroke.width, cap = StrokeCap.Round)
    drawRoundRect(
        tint,
        topLeft = pt(0.27f, 0.30f),
        size = Size(size.width * 0.46f, size.height * 0.52f),
        cornerRadius = CornerRadius(size.width * 0.05f),
        style = stroke,
    )
    val ribWidth = stroke.width * 0.75f
    drawLine(tint, pt(0.41f, 0.40f), pt(0.41f, 0.72f), ribWidth, cap = StrokeCap.Round)
    drawLine(tint, pt(0.59f, 0.40f), pt(0.59f, 0.72f), ribWidth, cap = StrokeCap.Round)
}

private fun DrawScope.drawLock(tint: Color, stroke: Stroke) {
    drawArc(
        tint,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = pt(0.28f, 0.16f),
        size = Size(size.width * 0.44f, size.height * 0.40f),
        style = stroke,
    )
    drawRoundRect(
        tint,
        topLeft = pt(0.22f, 0.44f),
        size = Size(size.width * 0.56f, size.height * 0.40f),
        cornerRadius = CornerRadius(size.minDimension * 0.10f),
        style = Fill,
    )
}

private fun DrawScope.drawCheckbox(tint: Color, stroke: Stroke, checked: Boolean) {
    drawRoundRect(
        tint,
        topLeft = pt(0.16f, 0.16f),
        size = Size(size.width * 0.68f, size.height * 0.68f),
        cornerRadius = CornerRadius(size.width * 0.08f),
        style = stroke,
    )
    if (checked) {
        val path = Path().apply {
            moveTo(pt(0.30f, 0.52f).x, pt(0.30f, 0.52f).y)
            lineTo(pt(0.44f, 0.66f).x, pt(0.44f, 0.66f).y)
            lineTo(pt(0.72f, 0.34f).x, pt(0.72f, 0.34f).y)
        }
        drawPath(path, tint, style = Stroke(width = stroke.width * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

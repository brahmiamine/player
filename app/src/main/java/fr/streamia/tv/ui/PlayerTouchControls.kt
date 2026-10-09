package fr.streamia.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkLight
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import kotlinx.coroutines.delay

/**
 * Commandes du lecteur au doigt (smartphone, tablette) : affichées seulement après un toucher, jamais à la
 * télécommande. Les tailles tiennent compte de la mise à l'échelle TV de l'interface (ResponsiveTvViewport),
 * qui rend tout plus petit sur un téléphone : boutons larges pour rester faciles à viser.
 */
@Composable
fun PlayerTouchControls(
    title: String,
    live: Boolean,
    playing: Boolean,
    seekStepSeconds: Int,
    positionMs: () -> Long,
    durationMs: () -> Long,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onChannelUp: () -> Unit,
    onChannelDown: () -> Unit,
    onChannelList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.3f to Color.Transparent, 1f to Color.Transparent)),
    ) {
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 32.dp, vertical = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TouchButton(onBack, "Retour") { StreamiaIcon(StreamiaIconGlyph.ArrowBack, tint = Ink, size = 44.dp) }
            Spacer(Modifier.width(20.dp))
            Text(
                title, color = Ink, fontSize = 26.sp, fontWeight = HeadingWeight, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (live) {
                Spacer(Modifier.width(16.dp))
                TouchButton(onChannelList, "Liste des chaînes") { StreamiaIcon(StreamiaIconGlyph.Guide, tint = Ink, size = 44.dp) }
            }
            Spacer(Modifier.width(16.dp))
            TouchButton(onSettings, "Réglages de lecture") { StreamiaIcon(StreamiaIconGlyph.Settings, tint = Ink, size = 44.dp) }
        }

        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                if (live) {
                    TouchButton(onChannelDown, "Chaîne précédente") { StreamiaIcon(StreamiaIconGlyph.ChevronDown, tint = Ink, size = 52.dp) }
                } else {
                    TouchButton(onSeekBackward, "Reculer de $seekStepSeconds secondes") {
                        Text("−$seekStepSeconds", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TouchButton(onTogglePlayback, if (playing) "Pause" else "Lecture", size = 148.dp, accent = true) {
                    PlayPauseGlyph(playing, Modifier.size(60.dp))
                }
                if (live) {
                    TouchButton(onChannelUp, "Chaîne suivante") { StreamiaIcon(StreamiaIconGlyph.ChevronUp, tint = Ink, size = 52.dp) }
                } else {
                    TouchButton(onSeekForward, "Avancer de $seekStepSeconds secondes") {
                        Text("+$seekStepSeconds", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (!live) {
                Spacer(Modifier.height(30.dp))
                TouchSeekBar(positionMs, durationMs, onSeekTo, Modifier.width(820.dp))
            }
        }
    }
}

/** Bouton rond au doigt, sans focus télécommande (ces commandes disparaissent dès qu'une touche est pressée). */
@Composable
private fun TouchButton(
    onClick: () -> Unit,
    description: String,
    size: Dp = 108.dp,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (accent) Brush.verticalGradient(listOf(AccentPinkLight, AccentPink))
                else Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Black.copy(alpha = 0.5f))),
            )
            .border(1.dp, if (accent) Color.White.copy(alpha = 0.25f) else GlassBorder, CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PlayPauseGlyph(playing: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        if (playing) {
            val bar = size.width * 0.26f
            val radius = CornerRadius(bar * 0.3f)
            drawRoundRect(Ink, Offset(size.width * 0.16f, size.height * 0.1f), Size(bar, size.height * 0.8f), radius)
            drawRoundRect(Ink, Offset(size.width * 0.58f, size.height * 0.1f), Size(bar, size.height * 0.8f), radius)
        } else {
            val path = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.08f)
                lineTo(size.width * 0.9f, size.height * 0.5f)
                lineTo(size.width * 0.22f, size.height * 0.92f)
                close()
            }
            drawPath(path, Ink)
        }
    }
}

/** Barre de progression à toucher ou faire glisser ; la lecture saute à la position au lâcher du doigt. */
@Composable
private fun TouchSeekBar(positionMs: () -> Long, durationMs: () -> Long, onSeekTo: (Long) -> Unit, modifier: Modifier) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // Position et durée lues chaque demi-seconde ici plutôt que de recomposer tout le lecteur.
    var position by remember { mutableLongStateOf(positionMs()) }
    var duration by remember { mutableLongStateOf(durationMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            position = positionMs()
            duration = durationMs()
            delay(500)
        }
    }
    if (duration <= 0L) return
    val fraction = dragFraction ?: (position.toFloat() / duration).coerceIn(0f, 1f)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .pointerInput(duration) {
                    detectTapGestures { offset -> onSeekTo((offset.x / size.width).coerceIn(0f, 1f).times(duration).toLong()) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { onSeekTo((it * duration).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val trackHeight = 10.dp.toPx()
                val top = (size.height - trackHeight) / 2
                val radius = CornerRadius(trackHeight / 2)
                drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, top), Size(size.width, trackHeight), radius)
                drawRoundRect(AccentPink, Offset(0f, top), Size(size.width * fraction, trackHeight), radius)
                drawCircle(Ink, radius = size.height / 2, center = Offset(size.width * fraction, size.height / 2))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration((fraction * duration).toLong()), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(duration), color = MutedInk, fontSize = 20.sp)
        }
    }
}

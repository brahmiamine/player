package fr.streamia.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgNowContext
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.SeriesEpisode
import fr.streamia.tv.player.PlaybackDiagnostics
import fr.streamia.tv.player.StreamTechnicalInfo
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RadiusTile
import fr.streamia.tv.ui.theme.RadiusCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// HUD du lecteur : bandeau d'infos, timelines, mini-guide, épisode suivant.

@Composable
internal fun NextEpisodePrompt(
    episode: SeriesEpisode?,
    secondsLeft: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
) {
    if (episode == null) return
    val playNowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playNowFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier
                .padding(34.dp)
                .width(420.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(RadiusCard))
                .background(Night.copy(alpha = 0.85f))
                .border(BorderStroke(1.dp, GlassBorder), androidx.compose.foundation.shape.RoundedCornerShape(RadiusCard))
                .padding(22.dp),
        ) {
            Text("Épisode suivant dans ${secondsLeft}s", color = MutedInk, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "S${episode.season.toString().padStart(2, '0')}E${episode.number.toString().padStart(2, '0')} · ${episode.title}",
                color = Ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusableSurface(onClick = onPlayNow, accent = true, modifier = Modifier.weight(1f).height(48.dp).focusRequester(playNowFocus)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Lire maintenant", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                FocusableSurface(onClick = onCancel, modifier = Modifier.weight(1f).height(48.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Annuler", color = Ink, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlayerInfoBand(
    entry: MediaEntry,
    categoryName: String?,
    epg: EpgNowContext,
    isPlaying: Boolean,
    numberBuffer: String,
    technicalInfo: StreamTechnicalInfo,
    diagnostics: PlaybackDiagnostics,
    transport: String,
    audioLabel: String,
    subtitleLabel: String,
    dolbyVisionLabel: String?,
    dolbyAtmosLabel: String?,
    resumePositionMs: Long,
    positionMs: () -> Long,
    durationMs: () -> Long,
    modifier: Modifier = Modifier,
    otherVersionsCount: Int = 0,
) {
    var epgClockEpochSeconds by remember(
        entry.key,
        epg.current?.startEpochSeconds,
        epg.current?.endEpochSeconds,
    ) { mutableStateOf(System.currentTimeMillis() / 1000L) }

    LaunchedEffect(entry.key, epg.current?.startEpochSeconds, epg.current?.endEpochSeconds) {
        if (entry.type != MediaType.Live || epg.current == null) return@LaunchedEffect
        while (true) {
            epgClockEpochSeconds = System.currentTimeMillis() / 1000L
            delay(LIVE_EPG_PROGRESS_REFRESH_MS)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 22.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(RadiusCard))
            .background(Night.copy(alpha = 0.68f))
            .border(BorderStroke(1.dp, GlassBorder), androidx.compose.foundation.shape.RoundedCornerShape(RadiusCard))
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(entry.iconUrl, entry.displayName, Modifier.size(74.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                val prefix = if (entry.type == MediaType.Live) "${entry.number} · " else ""
                Text(
                    prefix + entry.displayName,
                    color = Ink,
                    fontSize = 21.sp,
                    fontWeight = HeadingWeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                if (entry.type == MediaType.Live) {
                    if (!epg.isEmpty) {
                        epg.current?.let { current ->
                            Text(
                                "EN CE MOMENT${current.timeRange()?.let { " · $it" }.orEmpty()}",
                                color = MutedInk,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            Text(
                                current.title,
                                color = FocusBlueBright,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            LiveProgramTimeline(
                                program = current,
                                nowEpochSeconds = epgClockEpochSeconds,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        epg.next?.let { next ->
                            Text(
                                "À suivre ${next.timeRange()?.let { "$it · " }.orEmpty()}${next.title}",
                                color = MutedInk,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(categoryName ?: "Direct", color = FocusBlueBright, fontSize = 13.sp)
                    }
                } else {
                    val resume = resumePositionMs.takeIf { it > 0 }?.let { " · reprise ${formatDuration(it)}" }.orEmpty()
                    Text(
                        "${entry.type.displayName}${categoryName?.let { " · $it" }.orEmpty()}$resume",
                        color = FocusBlueBright,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.plot?.takeIf(String::isNotBlank)?.let {
                        Text(it, color = MutedInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.width(20.dp))
            Column(Modifier.width(500.dp), horizontalAlignment = Alignment.End) {
                Text(
                    "${technicalInfo.qualityLabel} · ${technicalInfo.resolutionText} · ${technicalInfo.fpsText}",
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    "${technicalInfo.codec ?: "Codec —"} · ${technicalInfo.bitrateText} · ${technicalInfo.hdr ?: "HDR/SDR —"}",
                    color = FocusBlueBright,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                val dolbyText = listOfNotNull(dolbyVisionLabel, dolbyAtmosLabel).joinToString(" · ")
                if (dolbyText.isNotBlank()) {
                    Text(
                        dolbyText,
                        color = FocusBlueBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "$transport · Audio $audioLabel · ST $subtitleLabel",
                    color = MutedInk,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    diagnosticsText(diagnostics),
                    color = MutedInk,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }

        if (entry.type != MediaType.Live && durationMs() > 0L) {
            Spacer(Modifier.height(12.dp))
            PlaybackTimeline(positionMs, durationMs)
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (isPlaying) "⏸ Lecture" else "▶ Pause", color = Ink, fontSize = 13.sp)
            Spacer(Modifier.width(20.dp))
            if (entry.type == MediaType.Live) {
                Text("OK infos · ← liste · ↑ ↓ zap · ⏪ dernière chaîne · 0–9 chaîne", color = MutedInk, fontSize = 13.sp)
                Spacer(Modifier.width(20.dp))
            } else {
                Text("← −10 s · +10 s → · Lecture/Pause", color = MutedInk, fontSize = 13.sp)
                Spacer(Modifier.width(20.dp))
            }
            if (otherVersionsCount > 0) {
                Text(
                    "→ ${if (otherVersionsCount == 1) "1 autre version" else "$otherVersionsCount autres versions"}",
                    color = FocusBlueBright,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(20.dp))
            }
            Text("⚙ audio / sous-titres / écran", color = MutedInk, fontSize = 13.sp)
            if (numberBuffer.isNotBlank()) {
                Spacer(Modifier.weight(1f))
                Text("CH $numberBuffer", color = FocusBlueBright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LiveProgramTimeline(
    program: EpgProgram,
    nowEpochSeconds: Long,
) {
    val start = program.startEpochSeconds ?: return
    val end = program.endEpochSeconds ?: return
    if (end <= start) return

    val progress = ((nowEpochSeconds - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatter.format(Date(start * 1000L)), color = MutedInk, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.weight(1f).height(6.dp)) {
            val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            drawRoundRect(Ink.copy(alpha = 0.18f), cornerRadius = radius)
            drawRoundRect(
                FocusBlueBright,
                size = Size(size.width * progress, size.height),
                cornerRadius = radius,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(formatter.format(Date(end * 1000L)), color = MutedInk, fontSize = 12.sp)
    }
}

@Composable
private fun PlaybackTimeline(position: () -> Long, duration: () -> Long) {
    val positionMs = position()
    val durationMs = duration().coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatDuration(positionMs), color = Ink, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Canvas(Modifier.weight(1f).height(8.dp)) {
            drawRoundRect(Ink.copy(alpha = 0.22f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            drawRoundRect(FocusBlueBright, size = Size(size.width * progress, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        }
        Spacer(Modifier.width(12.dp))
        Text(formatDuration(durationMs), color = Ink, fontSize = 12.sp)
    }
}

@Composable
internal fun PlayerGuide(
    catalog: Catalog,
    currentEntry: MediaEntry,
    onEntrySelected: (MediaEntry) -> Unit,
    onClose: () -> Unit,
) {
    val categories = remember(catalog) { listOf(Catalog.allCategory(MediaType.Live)) + catalog.categoriesFor(MediaType.Live) }
    var selectedCategoryId by remember(currentEntry.categoryId) { mutableStateOf(currentEntry.categoryId) }
    val channels = remember(catalog, selectedCategoryId) { catalog.entriesIn(MediaType.Live, selectedCategoryId) }
    val firstFocus = remember(selectedCategoryId) { FocusRequester() }

    LaunchedEffect(selectedCategoryId, channels.size) {
        if (channels.isNotEmpty()) {
            yield()
            runCatching { firstFocus.requestFocus() }
        }
    }

    Row(
        Modifier
            .fillMaxHeight()
            .width(820.dp)
            .background(Night.copy(alpha = 0.85f))
            .border(BorderStroke(1.dp, GlassBorder))
            .padding(24.dp),
    ) {
        Column(Modifier.width(290.dp).fillMaxHeight()) {
            SectionLabel("Catégories", fontSize = 22.sp)
            Spacer(Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(categories, key = { it.key }) { category ->
                    FocusableSurface(
                        onClick = { selectedCategoryId = category.id },
                        selected = selectedCategoryId == category.id,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) {
                        Text(category.name, color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
            }
        }

        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Chaînes", fontSize = 22.sp)
                Spacer(Modifier.weight(1f))
                FocusableSurface(onClick = onClose, modifier = Modifier.width(105.dp).height(46.dp)) {
                    Text("Fermer", color = Ink, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(channels, key = { it.key }) { channel ->
                    FocusableSurface(
                        onClick = { onEntrySelected(channel) },
                        selected = channel.key == currentEntry.key,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .then(if (channel.key == channels.firstOrNull()?.key) Modifier.focusRequester(firstFocus) else Modifier),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChannelLogo(channel.iconUrl, channel.name, Modifier.size(46.dp))
                            Spacer(Modifier.width(11.dp))
                            Text(channel.name, color = Ink, fontSize = 15.sp, fontWeight = if (channel.key == currentEntry.key) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(channel.number.toString(), color = FocusBlueBright, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

private fun EpgProgram.timeRange(): String? {
    val start = startEpochSeconds ?: return null
    val end = endEpochSeconds ?: return null
    val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    return "${format.format(Date(start * 1000L))}–${format.format(Date(end * 1000L))}"
}

@Composable
internal fun SeekFeedbackText(feedback: String, position: () -> Long) {
    Text("$feedback · ${formatDuration(position())}", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
}

internal fun formatDuration(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

/**
 * Pastille de verre sombre des messages courts du lecteur : chaîne annoncée par un zap, numéro en
 * cours de saisie, version changée, numéro introuvable, saut dans la lecture.
 */
@Composable
internal fun PlayerToast(
    modifier: Modifier = Modifier,
    alpha: Float = 0.82f,
    horizontalPadding: Dp = 22.dp,
    verticalPadding: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(RadiusTile)
    Box(
        modifier
            .clip(shape)
            .background(Night.copy(alpha = alpha))
            .border(BorderStroke(1.dp, GlassBorder), shape)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

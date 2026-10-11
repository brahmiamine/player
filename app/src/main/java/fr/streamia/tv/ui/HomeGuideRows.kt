package fr.streamia.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.beinsports.ResolvedBeinProgrammeItem
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.ukguide.ResolvedUkProgrammeItem
import fr.streamia.tv.tvprogramme.ResolvedTvProgrammeItem
import fr.streamia.tv.tvprogramme.ResolvedTvProgrammeNowItem
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RadiusPill
import java.time.LocalTime
import java.time.ZoneId

// Rangées des guides tiers de l'accueil (FR en direct / ce soir, beIN, UK).

internal val UK_GUIDE_ZONE: ZoneId = ZoneId.of("Europe/London")

internal const val TV_PROGRAMME_PROGRESS_REFRESH_MS = 30_000L

internal const val TV_PROGRAMME_DATA_REFRESH_MS = 2 * 60_000L

@Composable
internal fun TvProgrammeNowRow(
    items: List<ResolvedTvProgrammeNowItem>,
    nowEpochMillis: () -> Long,
    firstFocusRequester: FocusRequester?,
    restoreItemKey: String?,
    onOpenProgramme: (ResolvedTvProgrammeNowItem) -> Unit,
) = ProgrammeRow("Programme TV FR en direct", items, { it.fingerprint }, firstFocusRequester, restoreItemKey) { item, modifier ->
    ProgrammeCard(
        title = item.programme.title,
        timeLabel = item.programme.timeRangeLabel,
        imageUrl = item.programme.imageUrl,
        badge = ProgrammeBadge.Live,
        progress = item.programme.progressAt(nowEpochMillis()),
        remaining = "reste " + formatRemaining(item.programme.endEpochMillis - nowEpochMillis()),
        channel = item.channel,
        onClick = { onOpenProgramme(item) },
        modifier = modifier,
    )
}

@Composable
internal fun TvProgrammeTonightRow(
    items: List<ResolvedTvProgrammeItem>,
    firstFocusRequester: FocusRequester?,
    restoreItemKey: String?,
    onOpenProgramme: (ResolvedTvProgrammeItem) -> Unit,
) = ProgrammeRow("Programme TV FR ce soir", items, { it.fingerprint }, firstFocusRequester, restoreItemKey) { item, modifier ->
    ProgrammeCard(
        title = item.programme.title,
        timeLabel = item.programme.time,
        imageUrl = item.programme.imageUrl,
        badge = ProgrammeBadge.None,
        channel = item.channel,
        onClick = { onOpenProgramme(item) },
        modifier = modifier,
    )
}

@Composable
internal fun BeinSportsProgrammeRow(
    title: String,
    items: List<ResolvedBeinProgrammeItem>,
    nowEpochMillis: () -> Long,
    showLive: Boolean,
    firstFocusRequester: FocusRequester?,
    restoreItemKey: String?,
    onOpenProgramme: (ResolvedBeinProgrammeItem) -> Unit,
) = ProgrammeRow(title, items, { it.fingerprint }, firstFocusRequester, restoreItemKey) { item, modifier ->
    ProgrammeCard(
        title = item.programme.title,
        timeLabel = item.programme.timeRangeLabel,
        imageUrl = item.programme.imageUrl,
        category = item.programme.category,
        badge = if (showLive) ProgrammeBadge.Live else ProgrammeBadge.Next,
        progress = if (showLive) item.programme.progressAt(nowEpochMillis()) else null,
        remaining = if (showLive) "reste " + formatRemaining(item.programme.endEpochMillis - nowEpochMillis())
        else "dans " + formatRemaining(item.programme.startEpochMillis - nowEpochMillis()),
        channel = item.channel,
        onClick = { onOpenProgramme(item) },
        modifier = modifier,
    )
}

@Composable
internal fun UkGuideProgrammeRow(
    title: String,
    items: List<ResolvedUkProgrammeItem>,
    now: () -> LocalTime,
    showLive: Boolean,
    firstFocusRequester: FocusRequester?,
    restoreItemKey: String?,
    onOpenProgramme: (ResolvedUkProgrammeItem) -> Unit,
) = ProgrammeRow(title, items, { it.fingerprint }, firstFocusRequester, restoreItemKey) { item, modifier ->
    ProgrammeCard(
        title = item.programme.title,
        timeLabel = item.programme.timeRangeLabel,
        imageUrl = item.programme.imageUrl,
        badge = if (showLive) ProgrammeBadge.Live else ProgrammeBadge.Next,
        progress = if (showLive) item.programme.progressAt(now()) else null,
        channel = item.channel,
        onClick = { onOpenProgramme(item) },
        modifier = modifier,
    )
}

/** Rangée commune aux guides (FR, beIN, UK) : titre, défilement horizontal, restauration du focus. */
@Composable
private fun <T> ProgrammeRow(
    title: String,
    items: List<T>,
    key: (T) -> String,
    firstFocusRequester: FocusRequester?,
    restoreItemKey: String?,
    card: @Composable (T, Modifier) -> Unit,
) {
    val rowState = rememberLazyListState()
    val restoreFocus = remember { FocusRequester() }
    val restoreIndex = rememberRowFocusRestore(rowState, restoreItemKey, items.map(key), restoreFocus)

    Column(Modifier.fillMaxWidth()) {
        SectionLabel(title, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        LazyRow(state = rowState, modifier = Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
                val cardModifier = when {
                    index == restoreIndex -> Modifier.focusRequester(restoreFocus)
                    index == 0 && firstFocusRequester != null -> Modifier.focusRequester(firstFocusRequester)
                    else -> Modifier
                }
                card(item, cardModifier)
            }
        }
    }
}

private enum class ProgrammeBadge { None, Live, Next }

/** « 1 h 30 » ou « 25 min » (arrondi à la minute supérieure). */
private fun formatRemaining(millis: Long): String {
    val minutes = ((millis.coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()
    return if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)}" else "$minutes min"
}

/** Nom de chaîne lisible : sans préfixe de playlist (« SS: ») ni ballon à la place du O. */
private fun cleanChannelName(name: String): String =
    name.replace("\u26BD", "O").replace(Regex("^[A-Za-z]{1,4}:\\s*"), "").trim()

/**
 * Carte commune aux programmes (en direct, ce soir, beIN, UK) : image pleine carte, logo en haut à
 * gauche, titre et horaire sur un voile en bas, progression collée au bord (direct).
 */
@Composable
private fun ProgrammeCard(
    title: String,
    timeLabel: String,
    imageUrl: String?,
    badge: ProgrammeBadge,
    channel: MediaEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    category: String? = null,
    progress: Float? = null,
    remaining: String? = null,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.width(220.dp).height(142.dp),
    ) {
        Box(Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(14.dp))) {
            if (!imageUrl.isNullOrBlank()) {
                MediaArtwork(imageUrl, title, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(MutedInk.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                    StreamiaIcon(StreamiaIconGlyph.Live, tint = MutedInk.copy(alpha = 0.55f), size = 32.dp)
                }
            }
            ChannelLogo(
                channel.iconUrl,
                channel.displayName,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Night.copy(alpha = 0.82f)),
                imagePadding = 3,
            )
            if (badge == ProgrammeBadge.Live) {
                LiveBadge(Modifier.align(Alignment.TopEnd).padding(7.dp))
            } else {
                Text(
                    if (badge == ProgrammeBadge.Next) remaining ?: "À SUIVRE" else timeLabel,
                    color = Ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(RadiusPill))
                        .background(Night.copy(alpha = 0.82f))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Night.copy(alpha = 0.9f))))
                    .padding(start = 8.dp, end = 8.dp, top = 28.dp, bottom = if (progress != null) 9.dp else 7.dp),
            ) {
                Text(title, color = Ink, fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Row {
                    val meta = when (badge) {
                        ProgrammeBadge.None -> cleanChannelName(channel.displayName)
                        else -> timeLabel
                    }
                    Text(meta, color = MutedInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (badge == ProgrammeBadge.Live && remaining != null) {
                        Text(" · $remaining", color = AccentPinkText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Night.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxWidth(progress).height(4.dp).background(FocusBlueBright))
                }
            }
        }
    }
}

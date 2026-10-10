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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
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

/** Carte commune aux programmes (en direct, ce soir, beIN, UK). */
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
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.width(220.dp).height(215.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(9.dp)) {
            Box(Modifier.fillMaxWidth().height(92.dp)) {
                if (!imageUrl.isNullOrBlank()) {
                    MediaArtwork(imageUrl, title, Modifier.fillMaxSize())
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MutedInk.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        StreamiaIcon(StreamiaIconGlyph.Live, tint = MutedInk.copy(alpha = 0.55f), size = 32.dp)
                    }
                }
                Text(
                    timeLabel,
                    color = Ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Night.copy(alpha = 0.9f))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                )
                when (badge) {
                    ProgrammeBadge.Live -> LiveBadge(Modifier.align(Alignment.TopEnd).padding(7.dp))
                    ProgrammeBadge.Next -> Text(
                        "À SUIVRE",
                        color = Night,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(FocusBlueBright)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                    ProgrammeBadge.None -> Unit
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                title,
                color = Ink,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            category?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, color = MutedInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (progress != null) {
                Spacer(Modifier.height(7.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MutedInk.copy(alpha = 0.24f)),
                ) {
                    Box(Modifier.fillMaxWidth(progress).height(5.dp).background(FocusBlueBright))
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel.iconUrl, channel.displayName, Modifier.width(44.dp).height(44.dp), imagePadding = 2)
                Spacer(Modifier.width(7.dp))
                Text(channel.displayName, color = MutedInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// --- Rangée unique « Programme TV » : Maintenant | Ce soir × Tout | France | beIN Sports | UK ---

private enum class GuideMode(val label: String) { Now("Maintenant"), Next("Ce soir") }

private enum class GuideSource(val label: String) { All("Tout"), Fr("France"), Bein("beIN Sports"), Uk("UK") }

private class GuideEntry(
    val key: String,
    val source: GuideSource,
    val title: String,
    val timeLabel: String,
    val imageUrl: String?,
    val category: String?,
    val badge: ProgrammeBadge,
    val progressAt: ((Long) -> Float?)?,
    val channel: MediaEntry,
)

/**
 * Les six rangées de guides (FR, beIN, UK × en direct / ce soir) en une seule. La clé de retour porte la bascule
 * (« Now|Bein~~empreinte ») pour que l'accueil rouvre la même vue ; [onOpen] reçoit la chaîne et cette clé.
 */
@Composable
internal fun TvGuideRow(
    tvNow: List<ResolvedTvProgrammeNowItem>,
    tvTonight: List<ResolvedTvProgrammeItem>,
    beinNow: List<ResolvedBeinProgrammeItem>,
    beinNext: List<ResolvedBeinProgrammeItem>,
    ukNow: List<ResolvedUkProgrammeItem>,
    ukNext: List<ResolvedUkProgrammeItem>,
    nowMillis: () -> Long,
    ukTime: (Long) -> LocalTime,
    restoreItemKey: String?,
    onOpen: (MediaEntry, String) -> Unit,
) {
    val byMode = remember(tvNow, tvTonight, beinNow, beinNext, ukNow, ukNext) {
        mapOf(
            GuideMode.Now to buildList {
                tvNow.mapTo(this) { GuideEntry("fr-" + it.fingerprint, GuideSource.Fr, it.programme.title, it.programme.timeRangeLabel, it.programme.imageUrl, null, ProgrammeBadge.Live, { ms -> it.programme.progressAt(ms) }, it.channel) }
                beinNow.mapTo(this) { GuideEntry("bein-" + it.fingerprint, GuideSource.Bein, it.programme.title, it.programme.timeRangeLabel, it.programme.imageUrl, it.programme.category, ProgrammeBadge.Live, { ms -> it.programme.progressAt(ms) }, it.channel) }
                ukNow.mapTo(this) { GuideEntry("uk-" + it.fingerprint, GuideSource.Uk, it.programme.title, it.programme.timeRangeLabel, it.programme.imageUrl, null, ProgrammeBadge.Live, { ms -> it.programme.progressAt(ukTime(ms)) }, it.channel) }
            },
            GuideMode.Next to buildList {
                tvTonight.mapTo(this) { GuideEntry("fr-" + it.fingerprint, GuideSource.Fr, it.programme.title, it.programme.time, it.programme.imageUrl, null, ProgrammeBadge.None, null, it.channel) }
                beinNext.mapTo(this) { GuideEntry("bein-" + it.fingerprint, GuideSource.Bein, it.programme.title, it.programme.timeRangeLabel, it.programme.imageUrl, it.programme.category, ProgrammeBadge.Next, null, it.channel) }
                ukNext.mapTo(this) { GuideEntry("uk-" + it.fingerprint, GuideSource.Uk, it.programme.title, it.programme.timeRangeLabel, it.programme.imageUrl, null, ProgrammeBadge.Next, null, it.channel) }
            },
        )
    }
    val modes = GuideMode.entries.filter { byMode.getValue(it).isNotEmpty() }
    if (modes.isEmpty()) return

    val restoreTab = restoreItemKey?.tabPart()?.split('|')
    var mode by remember {
        mutableStateOf(restoreTab?.getOrNull(0)?.let { n -> GuideMode.entries.firstOrNull { it.name == n } } ?: modes.first())
    }
    var source by remember {
        mutableStateOf(restoreTab?.getOrNull(1)?.let { n -> GuideSource.entries.firstOrNull { it.name == n } } ?: GuideSource.All)
    }
    val effectiveMode = if (mode in modes) mode else modes.first()
    val modeEntries = byMode.getValue(effectiveMode)
    val sources = buildList {
        if (modeEntries.map { it.source }.distinct().size > 1) add(GuideSource.All)
        GuideSource.entries.filter { s -> s != GuideSource.All && modeEntries.any { it.source == s } }.forEach(::add)
    }
    val effectiveSource = if (source in sources) source else sources.first()
    val shown = if (effectiveSource == GuideSource.All) modeEntries else modeEntries.filter { it.source == effectiveSource }
    val tab = effectiveMode.name + "|" + effectiveSource.name

    val rowState = rememberLazyListState()
    val restoreFocus = remember { FocusRequester() }
    val restoreIndex = rememberRowFocusRestore(rowState, restoreItemKey?.keyPart(), shown.map { it.key }, restoreFocus)
    // Nouvelle vue : on revient au début de la rangée.
    androidx.compose.runtime.LaunchedEffect(tab) { if (restoreItemKey == null) rowState.scrollToItem(0) }

    Column(Modifier.fillMaxWidth()) {
        TabbedRowHeader(
            title = "Programme TV",
            tabs = modes.map { it.label },
            selected = modes.indexOf(effectiveMode),
            onSelect = { mode = modes[it] },
            secondTabs = sources.map { it.label },
            secondSelected = sources.indexOf(effectiveSource),
            onSelectSecond = { source = sources[it] },
        )
        LazyRow(state = rowState, modifier = Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(shown, key = { _, entry -> entry.key }) { index, entry ->
                ProgrammeCard(
                    title = entry.title,
                    timeLabel = entry.timeLabel,
                    imageUrl = entry.imageUrl,
                    badge = entry.badge,
                    channel = entry.channel,
                    category = entry.category,
                    progress = entry.progressAt?.invoke(nowMillis()),
                    onClick = { onOpen(entry.channel, tabItemKey(tab, entry.key)) },
                    modifier = if (index == restoreIndex) Modifier.focusRequester(restoreFocus) else Modifier,
                )
            }
        }
    }
}

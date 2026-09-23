package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.streamia.tv.ui.theme.RaisedSurface
import androidx.compose.runtime.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgGuide
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RadiusPill
import fr.streamia.tv.ui.theme.TypeLabel
import fr.streamia.tv.ui.theme.TypeSectionTitle
import fr.streamia.tv.ui.theme.TypeScreenTitle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private const val CLOCK_REFRESH_MS = 30_000L

private val ChannelLabelWidth = 224.dp
private val RowContentHeight = 84.dp
private val TimeRulerHeight = 30.dp

/**
 * Fenêtre horaire commune à toutes les chaînes (grille alignée façon décodeur) : 2 h visibles,
 * décalée d'une heure quand le focus atteint un bord.
 */
private const val WINDOW_SECONDS = 2 * 3_600L
private const val WINDOW_STEP_SECONDS = 3_600L
private const val HALF_HOUR = 1_800L

private val DayLabelFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

@Composable
fun EpgScreen(
    catalog: Catalog,
    guide: EpgGuide?,
    hiddenCategories: Set<String>,
    hiddenEntries: Set<String>,
    lockedCategories: Set<String>,
    parentalControlEnabled: Boolean,
    parentalUnlocked: Boolean,
    availableDates: List<LocalDate>,
    selectedDate: LocalDate?,
    loading: Boolean,
    message: String?,
    onOpenChannel: (MediaEntry) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var categoryId by remember { mutableStateOf(Catalog.ALL_CATEGORY_ID) }
    var selected by remember { mutableStateOf<SelectedProgram?>(null) }
    var nowEpoch by remember { mutableStateOf(System.currentTimeMillis() / 1000) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_REFRESH_MS)
            nowEpoch = System.currentTimeMillis() / 1000
        }
    }

    // Un guide TV ne propose pas de saisir un code par chaîne : une catégorie verrouillée et pas
    // encore déverrouillée cette session est donc traitée comme masquée ici, pas juste vidée de
    // son contenu (contrairement au navigateur, qui affiche la catégorie et gate sa sélection).
    val effectivelyHiddenCategories = remember(hiddenCategories, lockedCategories, parentalControlEnabled, parentalUnlocked) {
        if (!parentalControlEnabled || parentalUnlocked) hiddenCategories else hiddenCategories + lockedCategories
    }
    val hiddenCategoryIds = remember(catalog, effectivelyHiddenCategories) {
        catalog.categoriesFor(MediaType.Live)
            .filter { it.key in effectivelyHiddenCategories }
            .mapTo(mutableSetOf(), MediaCategory::id)
    }
    val categories = remember(catalog, effectivelyHiddenCategories) {
        listOf(Catalog.allCategory(MediaType.Live)) +
            catalog.categoriesFor(MediaType.Live).filterNot { it.key in effectivelyHiddenCategories }
    }
    val channels = remember(catalog, categoryId, hiddenEntries, hiddenCategoryIds) {
        catalog.entriesIn(MediaType.Live, categoryId).filterNot {
            it.key in hiddenEntries || it.categoryId in hiddenCategoryIds
        }
    }

    val effectiveDates = remember(availableDates, zone) {
        availableDates.takeIf { it.isNotEmpty() } ?: listOf(LocalDate.now(zone))
    }
    val displayDate = selectedDate?.takeIf { it in effectiveDates }
        ?: effectiveDates.firstOrNull { it == LocalDate.now(zone) }
        ?: effectiveDates.first()
    if (selected != null &&
        (channels.none { it.key == selected!!.channel.key } || guide?.forEntry(selected!!.channel)?.contains(selected!!.program) != true)
    ) {
        selected = null
    }

    val dayIndex = effectiveDates.indexOf(displayDate).coerceAtLeast(0)
    val canGoPrev = dayIndex > 0
    val canGoNext = dayIndex < effectiveDates.lastIndex
    val isToday = displayDate == LocalDate.now(zone)
    val dayStart = displayDate.atStartOfDay(zone).toEpochSecond()
    val dayEnd = displayDate.plusDays(1).atStartOfDay(zone).toEpochSecond()
    // Ouvre la fenêtre sur l'heure courante (même heure pour un autre jour), une demi-heure avant
    // pour voir ce qui vient de commencer.
    var windowStart by remember(dayStart) {
        val secondsIntoDay = (System.currentTimeMillis() / 1000 - LocalDate.now(zone).atStartOfDay(zone).toEpochSecond())
        mutableStateOf(clampWindow(dayStart + secondsIntoDay / HALF_HOUR * HALF_HOUR - HALF_HOUR, dayStart, dayEnd))
    }

    BackHandler {
        if (selected != null) selected = null else onBack()
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(RadiusPill)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FocusableSurface(
                    onClick = { if (selected != null) selected = null else onBack() },
                    modifier = Modifier.width(115.dp).height(50.dp),
                ) {
                    Text("← Retour", color = Ink, fontSize = TypeLabel, modifier = Modifier.padding(horizontal = 14.dp))
                }
                Spacer(Modifier.width(16.dp))
                Text("Guide TV · Grille horaire", color = Ink, fontSize = TypeScreenTitle, fontWeight = HeadingWeight)
                Spacer(Modifier.weight(1f))
                Text(if (guide == null) "XMLTV / fournisseur" else "${guide.channels.size} chaînes EPG", color = MutedInk, fontSize = TypeLabel)
                Spacer(Modifier.width(12.dp))
                FocusableSurface(onClick = onReload, enabled = !loading, modifier = Modifier.width(130.dp).height(50.dp)) {
                    Text(if (loading) "Chargement…" else "↻ Recharger", color = Ink, fontSize = TypeLabel, modifier = Modifier.padding(horizontal = 14.dp))
                }
            }
        }
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, color = MutedInk, fontSize = TypeLabel)
        }
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxSize()) {
            GlassSurface(modifier = Modifier.width(250.dp).fillMaxHeight()) {
              Column(Modifier.fillMaxSize().padding(16.dp)) {
                SectionLabel("Catégories")
                Spacer(Modifier.height(9.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(categories, key = { it.key }) { category ->
                        FocusableSurface(
                            onClick = { categoryId = category.id },
                            selected = categoryId == category.id,
                            accent = categoryId == category.id,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                        ) {
                            Text(category.name, color = Ink, fontSize = TypeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 13.dp))
                        }
                    }
                }
              }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                DayNavigator(
                    availableDates = effectiveDates,
                    dayIndex = dayIndex,
                    canGoPrev = canGoPrev,
                    canGoNext = canGoNext,
                    isToday = isToday,
                    nowEpoch = nowEpoch,
                    onSelectDate = onSelectDate,
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        loading && guide == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Chargement du guide XMLTV…", color = MutedInk, fontSize = TypeSectionTitle)
                        }
                        channels.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Aucune chaîne dans cette catégorie.", color = MutedInk, fontSize = TypeSectionTitle)
                        }
                        else -> Column(Modifier.fillMaxSize()) {
                            TimeRuler(windowStart = windowStart, nowEpoch = nowEpoch)
                            Spacer(Modifier.height(6.dp))
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(channels, key = MediaEntry::key) { channel ->
                                    ChannelGridRow(
                                        channel = channel,
                                        programs = guide?.forEntry(channel).orEmpty(),
                                        windowStart = windowStart,
                                        nowEpoch = nowEpoch,
                                        selectedKey = selected?.let { if (it.channel.key == channel.key) it.program.blockKey(channel) else null },
                                        onSelectProgram = { program -> selected = SelectedProgram(channel, program) },
                                        onShiftWindow = { direction ->
                                            val next = clampWindow(windowStart + direction * WINDOW_STEP_SECONDS, dayStart, dayEnd)
                                            val moved = next != windowStart
                                            windowStart = next
                                            moved
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (selected != null) {
                    Spacer(Modifier.height(12.dp))
                    ProgramDetailsPanel(
                        selected = selected!!,
                        onWatch = { onOpenChannel(selected!!.channel) },
                        onClose = { selected = null },
                    )
                }
            }
        }
    }
}

private data class SelectedProgram(val channel: MediaEntry, val program: EpgProgram)

@Composable
private fun DayNavigator(
    availableDates: List<LocalDate>,
    dayIndex: Int,
    canGoPrev: Boolean,
    canGoNext: Boolean,
    isToday: Boolean,
    nowEpoch: Long,
    onSelectDate: (LocalDate) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FocusableSurface(
            onClick = { onSelectDate(availableDates[dayIndex - 1]) },
            enabled = canGoPrev,
            modifier = Modifier.width(190.dp).height(48.dp),
        ) {
            Text(
                "← Jour précédent",
                color = if (canGoPrev) Ink else MutedInk,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(dayLabel(availableDates[dayIndex]), color = Ink, fontSize = 16.sp, fontWeight = HeadingWeight)
            Text(
                if (isToday) "Aujourd'hui · il est ${formatClock(nowEpoch)}" else "Jour ${dayIndex + 1} sur ${availableDates.size}",
                color = MutedInk,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        FocusableSurface(
            onClick = { onSelectDate(availableDates[dayIndex + 1]) },
            enabled = canGoNext,
            modifier = Modifier.width(160.dp).height(48.dp),
        ) {
            Text(
                "Jour suivant →",
                color = if (canGoNext) Ink else MutedInk,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/** Réglette des heures au-dessus de la grille, alignée sur la colonne des programmes. */
@Composable
private fun TimeRuler(windowStart: Long, nowEpoch: Long) {
    Row(Modifier.fillMaxWidth().height(TimeRulerHeight)) {
        Spacer(Modifier.width(ChannelLabelWidth + 10.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val perSecond = maxWidth / WINDOW_SECONDS.toFloat()
            var tick = (windowStart + HALF_HOUR - 1) / HALF_HOUR * HALF_HOUR
            while (tick < windowStart + WINDOW_SECONDS) {
                Text(
                    formatClock(tick),
                    color = MutedInk,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.offset(x = perSecond * (tick - windowStart).toFloat()).align(Alignment.CenterStart),
                )
                tick += HALF_HOUR
            }
            if (nowEpoch in windowStart until windowStart + WINDOW_SECONDS) {
                Box(
                    Modifier
                        .offset(x = perSecond * (nowEpoch - windowStart).toFloat())
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(FocusBlueBright),
                )
            }
        }
    }
}

@Composable
private fun ChannelGridRow(
    channel: MediaEntry,
    programs: List<EpgProgram>,
    windowStart: Long,
    nowEpoch: Long,
    selectedKey: String?,
    onSelectProgram: (EpgProgram) -> Unit,
    /** Décale la fenêtre d'un pas (-1 / +1) ; `false` si elle est déjà en butée du jour. */
    onShiftWindow: (Int) -> Boolean,
) {
    val windowEnd = windowStart + WINDOW_SECONDS
    val blocks = remember(programs, windowStart) { blocksInWindow(programs, windowStart, windowEnd) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(ChannelLabelWidth)
                .height(RowContentHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(DeepSurface)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel.iconUrl, channel.displayName, Modifier.size(40.dp))
                Spacer(Modifier.width(9.dp))
                Column {
                    Text(channel.number.toString(), color = MutedInk, fontSize = 12.sp)
                    Text(
                        channel.displayName,
                        color = Ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        BoxWithConstraints(Modifier.weight(1f).height(RowContentHeight)) {
            val perSecond = maxWidth / WINDOW_SECONDS.toFloat()
            if (blocks.isEmpty()) {
                // Rangée sans programme dans la fenêtre : reste focalisable pour continuer à naviguer
                // (↑ ↓ entre chaînes, ← → pour déplacer la fenêtre).
                FocusableSurface(
                    onClick = {},
                    idleBackground = Night.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxSize().windowEdgeKeys(isFirst = true, isLast = true, onShiftWindow),
                ) {
                    Text("Aucun programme sur ce créneau", color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
            blocks.forEachIndexed { index, block ->
                key(block.program.blockKey(channel)) {
                    val isLive = block.program.isLiveAt(nowEpoch)
                    ProgramBlock(
                        program = block.program,
                        isLive = isLive,
                        liveFraction = if (isLive) block.program.elapsedFraction(nowEpoch) else 0f,
                        selected = selectedKey == block.program.blockKey(channel),
                        onClick = { onSelectProgram(block.program) },
                        modifier = Modifier
                            .offset(x = perSecond * (block.clippedStart - windowStart).toFloat())
                            .width((perSecond * (block.clippedEnd - block.clippedStart).toFloat() - 4.dp).coerceAtLeast(12.dp))
                            .windowEdgeKeys(isFirst = index == 0, isLast = index == blocks.lastIndex, onShiftWindow),
                    )
                }
            }
            if (nowEpoch in windowStart until windowEnd) {
                Box(
                    Modifier
                        .offset(x = perSecond * (nowEpoch - windowStart).toFloat())
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(FocusBlueBright.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

/**
 * ← sur le premier programme visible / → sur le dernier : décale la fenêtre au lieu de laisser le
 * focus sortir de la grille. Le focus reste sur le programme, et l'appui suivant atteint les
 * programmes devenus visibles.
 */
private fun Modifier.windowEdgeKeys(isFirst: Boolean, isLast: Boolean, onShiftWindow: (Int) -> Boolean): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.nativeKeyEvent.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> isLast && onShiftWindow(1)
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> isFirst && onShiftWindow(-1)
            else -> false
        }
    }

@Composable
private fun ProgramBlock(
    program: EpgProgram,
    isLive: Boolean,
    liveFraction: Float,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        onClick = onClick,
        selected = selected,
        idleBackground = if (isLive) RaisedSurface else DeepSurface,
        modifier = modifier.height(RowContentHeight),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(program.timeRange(), color = if (isLive) FocusBlueBright else MutedInk, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(
                program.title,
                color = Ink,
                fontSize = 14.sp,
                fontWeight = if (isLive) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isLive) {
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).background(DeepSurface)) {
                    Box(Modifier.fillMaxWidth(liveFraction.coerceIn(0f, 1f)).fillMaxHeight().background(FocusBlueBright))
                }
            }
        }
    }
}

@Composable
private fun ProgramDetailsPanel(
    selected: SelectedProgram,
    onWatch: () -> Unit,
    onClose: () -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.fillMaxWidth().padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(selected.program.title, color = Ink, fontSize = 20.sp, fontWeight = HeadingWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${selected.channel.number} · ${selected.channel.displayName} · ${selected.program.timeRange()}",
                    color = FocusBlueBright,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            FocusableSurface(onClick = onWatch, accent = true, modifier = Modifier.width(190.dp).height(50.dp)) {
                Text("▶ Regarder la chaîne", color = Ink, fontSize = TypeLabel, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp))
            }
            Spacer(Modifier.width(10.dp))
            FocusableSurface(onClick = onClose, modifier = Modifier.width(110.dp).height(50.dp)) {
                Text("Fermer", color = Ink, fontSize = TypeLabel, modifier = Modifier.padding(horizontal = 14.dp))
            }
        }
        if (!selected.program.description.isNullOrBlank()) {
            Spacer(Modifier.height(9.dp))
            Text(selected.program.description, color = MutedInk, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        if (!selected.program.category.isNullOrBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(selected.program.category, color = FocusBlueBright, fontSize = 12.sp)
        }
      }
    }
}

internal data class WindowBlock(val program: EpgProgram, val clippedStart: Long, val clippedEnd: Long)

/** Programmes visibles dans [windowStart, windowEnd), rognés à la fenêtre et triés par heure. */
internal fun blocksInWindow(programs: List<EpgProgram>, windowStart: Long, windowEnd: Long): List<WindowBlock> =
    programs.asSequence()
        .mapNotNull { program ->
            val start = program.startEpochSeconds ?: return@mapNotNull null
            val end = program.endEpochSeconds ?: return@mapNotNull null
            if (end <= start || end <= windowStart || start >= windowEnd) return@mapNotNull null
            WindowBlock(program, maxOf(start, windowStart), minOf(end, windowEnd))
        }
        .sortedBy { it.clippedStart }
        .toList()

/** Garde la fenêtre de 2 h à l'intérieur de la journée affichée. */
internal fun clampWindow(start: Long, dayStart: Long, dayEnd: Long): Long =
    start.coerceIn(dayStart, (dayEnd - WINDOW_SECONDS).coerceAtLeast(dayStart))

private fun EpgProgram.blockKey(channel: MediaEntry): String = "${channel.key}:${startEpochSeconds}:$title"

private fun EpgProgram.isLiveAt(epoch: Long): Boolean {
    val start = startEpochSeconds ?: return false
    val end = endEpochSeconds ?: return false
    return epoch in start until end
}

private fun EpgProgram.elapsedFraction(epoch: Long): Float {
    val start = startEpochSeconds ?: return 0f
    val end = endEpochSeconds ?: return 0f
    if (end <= start) return 0f
    return ((epoch - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
}

private fun EpgProgram.timeRange(): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    fun format(epoch: Long?): String = epoch?.let { formatter.format(Date(it * 1000)) } ?: "--:--"
    return "${format(startEpochSeconds)} – ${format(endEpochSeconds)}"
}

private fun formatClock(epochSeconds: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))

private fun dayLabel(date: LocalDate): String =
    date.format(DayLabelFormatter).replaceFirstChar { it.titlecase(Locale.FRENCH) }

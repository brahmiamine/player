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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import fr.streamia.tv.ui.theme.TypeLabel
import fr.streamia.tv.ui.theme.TypeSectionTitle
import fr.streamia.tv.ui.theme.TypeScreenTitle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/** Nombre maximum de jours affichables par la navigation, en garde-fou contre un flux XMLTV malformé. */
private const val MAX_DAY_SPAN = 30L
private const val CLOCK_REFRESH_MS = 30_000L

private val ChannelLabelWidth = 224.dp
private val RowContentHeight = 92.dp
private val MinBlockWidth = 150.dp

/** Largeur d'une minute de programme dans la grille : 1h ≈ 190dp, un film de 2h ≈ 380dp. */
private val ProgramMinuteWidth = 3.15.dp

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

    BackHandler {
        if (selected != null) selected = null else onBack()
    }

    Column(Modifier.fillMaxSize().background(Night).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, color = MutedInk, fontSize = TypeLabel)
        }
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(250.dp).fillMaxHeight()) {
                SectionLabel("Catégories")
                Spacer(Modifier.height(9.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(categories, key = { it.key }) { category ->
                        FocusableSurface(
                            onClick = { categoryId = category.id },
                            selected = categoryId == category.id,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                        ) {
                            Text(category.name, color = Ink, fontSize = TypeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 13.dp))
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
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(channels, key = MediaEntry::key) { channel ->
                                ChannelGridRow(
                                    channel = channel,
                                    programs = guide?.forEntry(channel).orEmpty(),
                                    dayStart = dayStart,
                                    dayEnd = dayEnd,
                                    isToday = isToday,
                                    nowEpoch = nowEpoch,
                                    selectedKey = selected?.let { if (it.channel.key == channel.key) it.program.blockKey(channel) else null },
                                    onSelectProgram = { program -> selected = SelectedProgram(channel, program) },
                                )
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

@Composable
private fun ChannelGridRow(
    channel: MediaEntry,
    programs: List<EpgProgram>,
    dayStart: Long,
    dayEnd: Long,
    isToday: Boolean,
    nowEpoch: Long,
    selectedKey: String?,
    onSelectProgram: (EpgProgram) -> Unit,
) {
    val dayBlocks = remember(programs, dayStart, dayEnd) { dayBlocksFor(programs, dayStart, dayEnd) }
    val liveIndex = remember(dayBlocks, isToday, nowEpoch) {
        if (isToday) dayBlocks.indexOfFirst { it.program.isLiveAt(nowEpoch) } else -1
    }

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
                    Text(channel.number.toString(), color = MutedInk, fontSize = 11.sp)
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
        if (dayBlocks.isEmpty()) {
            Box(
                Modifier
                    .width(280.dp)
                    .height(RowContentHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Night.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Aucun programme ce jour-là", color = MutedInk, fontSize = 12.sp)
            }
        } else {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = liveIndex.coerceAtLeast(0))
            // rememberLazyListState() ne relit initialFirstVisibleItemIndex qu'à la création : sans
            // ce recalage explicite, changer de jour garde le défilement du jour précédent alors que
            // dayBlocks (et donc liveIndex) ont changé, laissant le programme en cours hors écran.
            LaunchedEffect(dayStart) { listState.scrollToItem(liveIndex.coerceAtLeast(0)) }
            LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(dayBlocks, key = { _, block -> block.program.blockKey(channel) }) { _, block ->
                    val isLive = isToday && block.program.isLiveAt(nowEpoch)
                    ProgramBlock(
                        program = block.program,
                        width = block.widthDp,
                        isLive = isLive,
                        liveFraction = if (isLive) block.program.elapsedFraction(nowEpoch) else 0f,
                        selected = selectedKey == block.program.blockKey(channel),
                        onClick = { onSelectProgram(block.program) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgramBlock(
    program: EpgProgram,
    width: Dp,
    isLive: Boolean,
    liveFraction: Float,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FocusableSurface(
        onClick = onClick,
        selected = selected,
        modifier = Modifier.width(width).height(RowContentHeight),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(program.timeRange(), color = if (isLive) FocusBlueBright else MutedInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (isLive) {
                    Spacer(Modifier.width(6.dp))
                    Text("EN DIRECT", color = FocusBlueBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
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
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DeepSurface)
            .padding(18.dp),
    ) {
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
            FocusableSurface(onClick = onWatch, modifier = Modifier.width(190.dp).height(50.dp)) {
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
            Text(selected.program.category, color = FocusBlueBright, fontSize = 11.sp)
        }
    }
}

private data class DayBlock(val program: EpgProgram, val clippedStart: Long, val clippedEnd: Long) {
    val widthDp: Dp
        get() {
            val minutes = (clippedEnd - clippedStart) / 60f
            return (ProgramMinuteWidth * minutes).coerceAtLeast(MinBlockWidth)
        }
}

/** Découpe et ordonne les programmes d'une chaîne sur la fenêtre [dayStart, dayEnd), en ignorant les entrées sans horaires exploitables. */
private fun dayBlocksFor(programs: List<EpgProgram>, dayStart: Long, dayEnd: Long): List<DayBlock> =
    programs.asSequence()
        .mapNotNull { program ->
            val start = program.startEpochSeconds ?: return@mapNotNull null
            val end = program.endEpochSeconds ?: return@mapNotNull null
            if (end <= start || end <= dayStart || start >= dayEnd) return@mapNotNull null
            DayBlock(program, maxOf(start, dayStart), minOf(end, dayEnd))
        }
        .sortedBy { it.clippedStart }
        .toList()

/**
 * Journées couvertes par le guide chargé, calculées à partir des horaires réellement présents dans le
 * flux XMLTV (le fournisseur peut en servir un seul jour comme plusieurs semaines) : on ne fabrique jamais
 * de jour qui n'aurait aucune donnée dans le flux d'origine, on borne juste un flux malformé.
 */
private fun EpgGuide.availableDates(zone: ZoneId): List<LocalDate> {
    var minEpoch = Long.MAX_VALUE
    var maxEpoch = Long.MIN_VALUE
    channels.values.forEach { channel ->
        channel.programs.forEach { program ->
            program.startEpochSeconds?.let { if (it < minEpoch) minEpoch = it }
            program.endEpochSeconds?.let { if (it > maxEpoch) maxEpoch = it }
        }
    }
    if (minEpoch == Long.MAX_VALUE || maxEpoch == Long.MIN_VALUE || maxEpoch < minEpoch) return emptyList()
    val minDate = Instant.ofEpochSecond(minEpoch).atZone(zone).toLocalDate()
    // maxEpoch est une fin de programme, donc exclusive : un dernier programme se terminant pile à
    // minuit ne doit pas ajouter le jour suivant, sous peine d'une journée finale entièrement vide
    // (dayBlocksFor exclut déjà ce programme de ce jour-là via end <= dayStart).
    val maxDate = Instant.ofEpochSecond((maxEpoch - 1).coerceAtLeast(minEpoch)).atZone(zone).toLocalDate()
    val span = ChronoUnit.DAYS.between(minDate, maxDate).coerceIn(0L, MAX_DAY_SPAN)
    return (0..span).map { minDate.plusDays(it) }
}

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

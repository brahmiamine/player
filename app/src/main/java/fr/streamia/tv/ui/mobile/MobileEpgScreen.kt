package fr.streamia.tv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import fr.streamia.tv.ui.ChannelLogo
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.FocusBlue
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ChannelColumnWidth = 64.dp
private val HourWidth = 240.dp
private val RowHeight = 58.dp

private data class PickedProgram(val channel: MediaEntry, val program: EpgProgram)

/**
 * Guide TV au doigt : grille horaire d'une journée dont toutes les lignes défilent ensemble
 * horizontalement (un doigt suffit), colonne des chaînes fixe, repère « maintenant » et bouton
 * pour y revenir. Toucher un programme ouvre sa fiche (horaires, description, regarder).
 */
@Composable
fun MobileEpgScreen(
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
    loadDescription: suspend (EpgProgram) -> String?,
    onBack: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var picked by remember { mutableStateOf<PickedProgram?>(null) }
    BackHandler { if (picked != null) picked = null else onBack() }

    val effectivelyHidden = remember(hiddenCategories, lockedCategories, parentalControlEnabled, parentalUnlocked) {
        if (!parentalControlEnabled || parentalUnlocked) hiddenCategories else hiddenCategories + lockedCategories
    }
    val hiddenIds = remember(catalog, effectivelyHidden) {
        catalog.categoriesFor(MediaType.Live).filter { it.key in effectivelyHidden }.mapTo(mutableSetOf(), MediaCategory::id)
    }
    var categoryId by remember { mutableStateOf(Catalog.ALL_CATEGORY_ID) }
    val categories = remember(catalog, effectivelyHidden) {
        listOf(Catalog.allCategory(MediaType.Live)) + catalog.categoriesFor(MediaType.Live).filterNot { it.key in effectivelyHidden }
    }
    val channels = remember(catalog, categoryId, hiddenEntries, hiddenIds) {
        catalog.entriesIn(MediaType.Live, categoryId).filterNot { it.key in hiddenEntries || it.categoryId in hiddenIds }
    }

    val dates = remember(availableDates, zone) { availableDates.takeIf { it.isNotEmpty() } ?: listOf(LocalDate.now(zone)) }
    val date = selectedDate?.takeIf { it in dates } ?: dates.firstOrNull { it == LocalDate.now(zone) } ?: dates.first()
    val dayIndex = dates.indexOf(date).coerceAtLeast(0)
    val dayStart = date.atStartOfDay(zone).toEpochSecond()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val isToday = date == LocalDate.now(zone)

    val nowSeconds by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis() / 1000
        }
    }
    val hourPx = with(LocalDensity.current) { HourWidth.toPx() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    fun scrollToNow() {
        val target = ((nowSeconds - dayStart) / 3600f - 0.5f).coerceAtLeast(0f) * hourPx
        scope.launch { scroll.animateScrollTo(target.toInt()) }
    }
    LaunchedEffect(dayStart) { scroll.scrollTo((((if (isToday) nowSeconds - dayStart else 8 * 3600L) / 3600f - 0.5f).coerceAtLeast(0f) * hourPx).toInt()) }

    val totalWidth: Dp = HourWidth * 24
    fun xOf(epoch: Long): Dp = HourWidth * ((epoch - dayStart) / 3600f)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MobileIconButton(StreamiaIconGlyph.ArrowBack, onClick = onBack)
                Text("Guide TV", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
                Text(
                    "Maintenant",
                    color = Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(AccentPink).clickable(enabled = isToday) { scrollToNow() }.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                MobileIconButton(StreamiaIconGlyph.Refresh, onClick = onReload)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = MobileGutter).height(44.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.09f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(48.dp).fillMaxHeight().clickable(enabled = dayIndex > 0) { onSelectDate(dates[dayIndex - 1]) }, contentAlignment = Alignment.Center) {
                    fr.streamia.tv.ui.StreamiaIcon(StreamiaIconGlyph.ArrowBack, tint = if (dayIndex > 0) Ink else MutedInk, size = 18.dp)
                }
                Text(DayFormatter.format(date).replaceFirstChar { it.uppercase() }, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.width(48.dp).fillMaxHeight().clickable(enabled = dayIndex < dates.lastIndex) { onSelectDate(dates[dayIndex + 1]) }, contentAlignment = Alignment.Center) {
                    fr.streamia.tv.ui.StreamiaIcon(StreamiaIconGlyph.ArrowForward, tint = if (dayIndex < dates.lastIndex) Ink else MutedInk, size = 18.dp)
                }
            }
            Spacer(Modifier.height(10.dp))
            MobileChipRow {
                categories.forEach { category ->
                    MobileChip(category.name, category.id == categoryId, onClick = { categoryId = category.id })
                }
            }
            Spacer(Modifier.height(8.dp))
            message?.let { MobileMessage(it, onDismiss = {}) }

            when {
                guide == null && loading -> MobileEmptyState("Chargement du guide…")
                guide == null -> MobileEmptyState("Aucun guide disponible pour cette liste.", "Recharger", onReload)
                else -> Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.05f)),
                ) {
                    // Règle des heures, calée sur le même défilement que les lignes.
                    Row(Modifier.fillMaxWidth().height(34.dp).background(Color(0xF20E0E12))) {
                        Box(Modifier.width(ChannelColumnWidth).fillMaxHeight())
                        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                            Row(Modifier.width(totalWidth)) {
                                repeat(24) { hour ->
                                    Text(
                                        "%02d:00".format(hour),
                                        color = Ink,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(HourWidth).padding(start = 8.dp, top = 9.dp),
                                    )
                                }
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(channels, key = { it.key }) { channel ->
                            val programs = remember(guide, channel.key, dayStart) {
                                guide.forEntry(channel).filter { p ->
                                    val s = p.startEpochSeconds
                                    val e = p.endEpochSeconds
                                    s != null && e != null && e > dayStart && s < dayEnd && e > s
                                }
                            }
                            Row(Modifier.fillMaxWidth().height(RowHeight)) {
                                Column(
                                    Modifier.width(ChannelColumnWidth).fillMaxHeight().background(Color(0xFA0E0E12)).clickable { onOpenChannel(channel) },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    ChannelLogo(channel.iconUrl, channel.displayName, Modifier.width(40.dp).height(28.dp), imagePadding = 2)
                                    Text("${channel.number}", color = MutedInk, fontSize = 10.sp)
                                }
                                Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                                    Box(Modifier.width(totalWidth).fillMaxHeight()) {
                                        programs.forEach { program ->
                                            val s = maxOf(program.startEpochSeconds!!, dayStart)
                                            val e = minOf(program.endEpochSeconds!!, dayEnd)
                                            val current = nowSeconds in s until e
                                            Box(
                                                Modifier
                                                    .offset(x = xOf(s))
                                                    .width((xOf(e) - xOf(s)).coerceAtLeast(2.dp))
                                                    .fillMaxHeight()
                                                    .padding(horizontal = 2.dp, vertical = 4.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(if (current) FocusBlue else Color.White.copy(alpha = 0.06f))
                                                    .clickable { picked = PickedProgram(channel, program) }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                            ) {
                                                Column {
                                                    Text(program.title, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text("${clock(s)}–${clock(e)}", color = MutedInk, fontSize = 11.sp, maxLines = 1)
                                                }
                                            }
                                        }
                                        if (isToday && nowSeconds in dayStart until dayEnd) {
                                            Box(Modifier.offset(x = xOf(nowSeconds)).width(2.dp).fillMaxHeight().background(AccentPink))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        picked?.let { (channel, program) ->
            val description by produceState<String?>(program.description, program) { value = loadDescription(program) ?: program.description }
            MobileBottomSheet(
                title = program.title,
                subtitle = "${channel.displayName} · ${program.startEpochSeconds?.let { clock(it) } ?: ""}–${program.endEpochSeconds?.let { clock(it) } ?: ""}",
                onDismiss = { picked = null },
            ) {
                description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Ink, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                MobileSheetAction("Regarder ${channel.displayName}", StreamiaIconGlyph.Movie, onClick = { picked = null; onOpenChannel(channel) })
            }
        }
    }
}

private val DayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

private fun clock(epochSeconds: Long): String = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(ClockFormatter)

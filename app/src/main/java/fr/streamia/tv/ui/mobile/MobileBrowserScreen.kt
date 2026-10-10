package fr.streamia.tv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.border
import fr.streamia.tv.ui.LiveBrowserReturnState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.LiveChannelSortOrder
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.data.VodSortOrder
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.epgNowContextAt
import fr.streamia.tv.ui.ChannelLogo
import fr.streamia.tv.ui.FAVORITES_CATEGORY_ID
import fr.streamia.tv.ui.HISTORY_CATEGORY_ID
import fr.streamia.tv.ui.LOAD_MORE_THRESHOLD
import fr.streamia.tv.ui.MediaArtwork
import fr.streamia.tv.ui.StreamiaIcon
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.buildBrowserCategories
import fr.streamia.tv.ui.sortedForLiveDisplay
import fr.streamia.tv.ui.sortedForVodDisplay
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusTile
import fr.streamia.tv.ui.theme.WarmSignal
import fr.streamia.tv.ui.vodPageKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private sealed interface BrowserSheet {
    data class EntryActions(val entry: MediaEntry) : BrowserSheet
    data object Sort : BrowserSheet
    data object Categories : BrowserSheet
    data object Numpad : BrowserSheet
    data class Pin(val category: MediaCategory) : BrowserSheet
}

/**
 * Direct / Films / Séries au doigt : pastilles de catégories défilantes, liste de chaînes ou grille
 * d'affiches, tirer pour actualiser, appui long pour les actions (favori, masquer), pavé numérique
 * pour aller à une chaîne. Mêmes règles que le navigateur TV (masquage, verrouillage parental,
 * pagination SQLite des films et séries).
 */
@Composable
fun MobileBrowserScreen(
    catalog: Catalog,
    epgPrograms: Map<String, List<EpgProgram>>,
    library: UserLibrarySnapshot,
    appSettings: AppSettings,
    loadingCategoryKeys: Set<String>,
    vodPages: Map<String, List<MediaEntry>>,
    categoryLoadErrors: Set<String>,
    parentalUnlocked: Boolean,
    offline: Boolean,
    busy: Boolean,
    message: String?,
    type: MediaType,
    initialCategoryId: String?,
    onEntrySelected: (MediaEntry) -> Unit,
    onLiveEntrySelected: (MediaEntry, List<MediaEntry>) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    onToggleEntryHidden: (MediaEntry) -> Unit,
    onVerifyParentalPin: suspend (String) -> Boolean,
    onLocationChanged: (MediaType, String?) -> Unit,
    onEnsureCategoryLoaded: (MediaType, String, VodSortOrder) -> Unit,
    onLoadMoreInCategory: (MediaType, String, VodSortOrder) -> Unit,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val isLive = type == MediaType.Live
    // Retour d'une chaîne jouée (liste, recherche…) : on rouvre sa catégorie et on la met en évidence dans la liste.
    val returnEntry = remember(type) {
        if (!isLive) null else LiveBrowserReturnState.consume()?.let(catalog::entry)?.takeIf { entry ->
            !(appSettings.parentalControlEnabled && !parentalUnlocked && Catalog.categoryKey(type, entry.categoryId) in library.lockedCategories)
        }
    }
    var selectedCategoryId by remember(type) {
        mutableStateOf(returnEntry?.categoryId ?: initialCategoryId ?: defaultCategoryId(catalog, type))
    }
    var vodSort by remember(type, selectedCategoryId, appSettings.vodSortOrder) { mutableStateOf(appSettings.vodSortOrder) }
    var liveSort by remember(appSettings.liveChannelSortOrder) { mutableStateOf(appSettings.liveChannelSortOrder) }
    var sheet by remember { mutableStateOf<BrowserSheet?>(null) }

    // Catégories masquées ou (verrouillées et pas encore déverrouillées) : leur contenu n'apparaît
    // pas dans les vues agrégées. Une catégorie verrouillée reste visible dans les pastilles.
    val hiddenIds = remember(catalog, type, library.hiddenCategories) {
        catalog.categoriesFor(type).filter { it.key in library.hiddenCategories }.mapTo(mutableSetOf(), MediaCategory::id)
    }
    val lockedIds = remember(catalog, type, library.lockedCategories, appSettings.parentalControlEnabled, parentalUnlocked) {
        if (!appSettings.parentalControlEnabled || parentalUnlocked) emptySet()
        else catalog.categoriesFor(type).filter { it.key in library.lockedCategories }.mapTo(mutableSetOf(), MediaCategory::id)
    }
    val excludedIds = remember(hiddenIds, lockedIds) { hiddenIds + lockedIds }
    val favoriteEntries = remember(catalog, type, library.favoriteEntries, library.hiddenEntries, excludedIds) {
        library.favoriteEntries.asSequence()
            .mapNotNull(catalog::entry)
            .filter { it.type == type && it.key !in library.hiddenEntries && it.categoryId !in excludedIds }
            .toList()
    }
    val historyEntries = remember(catalog, type, library.history, library.hiddenEntries, excludedIds) {
        library.history.asSequence()
            .map { item -> catalog.entry(item.entry.key) ?: item.entry }
            .filter { it.type == type && it.key !in library.hiddenEntries && it.categoryId !in excludedIds }
            .toList()
    }
    val categories = remember(catalog, type, library.hiddenCategories, library.favoriteCategories, favoriteEntries.isNotEmpty(), historyEntries.isNotEmpty()) {
        buildBrowserCategories(
            type = type,
            providerCategories = catalog.categoriesFor(type).filterNot { it.key in library.hiddenCategories },
            favoriteCategoryKeys = if (isLive) library.favoriteCategories else emptySet(),
            hasFavoriteEntries = favoriteEntries.isNotEmpty(),
            hasHistory = historyEntries.isNotEmpty(),
        )
    }
    LaunchedEffect(type, categories) {
        if (categories.none { it.id == selectedCategoryId }) selectedCategoryId = defaultCategoryId(catalog, type)
    }

    val paged = !isLive && catalog.isPaged
    val pagesMissing = paged && vodPageKey(type, selectedCategoryId, vodSort) !in vodPages
    LaunchedEffect(type, selectedCategoryId, pagesMissing, vodSort) {
        onLocationChanged(type, selectedCategoryId)
        if (selectedCategoryId != FAVORITES_CATEGORY_ID && selectedCategoryId != HISTORY_CATEGORY_ID) {
            onEnsureCategoryLoaded(type, selectedCategoryId, vodSort)
        }
    }

    val entries by produceState<List<MediaEntry>>(
        emptyList(), catalog, type, selectedCategoryId, favoriteEntries, historyEntries, excludedIds,
        library.hiddenEntries, vodPages, vodSort, liveSort,
    ) {
        value = withContext(Dispatchers.Default) {
            when (selectedCategoryId) {
                FAVORITES_CATEGORY_ID -> favoriteEntries
                HISTORY_CATEGORY_ID -> historyEntries
                else -> {
                    val source = when {
                        !paged -> catalog.entriesIn(type, selectedCategoryId)
                        else -> vodPages[vodPageKey(type, selectedCategoryId, vodSort)].orEmpty().filter {
                            selectedCategoryId == Catalog.ALL_CATEGORY_ID || it.categoryId == selectedCategoryId
                        }
                    }
                    val visible = source.filterNot { it.key in library.hiddenEntries || it.categoryId in excludedIds }
                    when {
                        isLive -> sortedForLiveDisplay(visible, liveSort)
                        paged -> visible
                        else -> sortedForVodDisplay(visible, vodSort)
                    }
                }
            }
        }
    }

    val categoryKey = Catalog.categoryKey(type, selectedCategoryId)
    val loading = categoryKey in loadingCategoryKeys || pagesMissing
    val loadFailed = categoryKey in categoryLoadErrors

    fun selectCategory(category: MediaCategory) {
        val locked = appSettings.parentalControlEnabled && !parentalUnlocked && category.key in library.lockedCategories
        if (locked) sheet = BrowserSheet.Pin(category) else selectedCategoryId = category.id
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            MobileHeader(if (isLive) "Direct" else type.displayName) {
                if (offline) OfflineTag()
                if (isLive) MobileIconButton(StreamiaIconGlyph.Menu, onClick = { sheet = BrowserSheet.Numpad })
                MobileIconButton(StreamiaIconGlyph.Reorder, onClick = { sheet = BrowserSheet.Sort })
                MobileIconButton(StreamiaIconGlyph.Search, onClick = onSearch)
            }
            MobileChipRow {
                categories.forEach { category ->
                    MobileChip(
                        label = category.name,
                        selected = category.id == selectedCategoryId,
                        locked = appSettings.parentalControlEnabled && !parentalUnlocked && category.key in library.lockedCategories,
                        onClick = { selectCategory(category) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            message?.let { MobileMessage(it, onDismissMessage) }

            MobilePullToRefresh(refreshing = busy, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
                when {
                    entries.isEmpty() && loading -> MobileEmptyState("Chargement…")
                    entries.isEmpty() && loadFailed -> MobileEmptyState(
                        "Impossible de charger cette catégorie.",
                        actionLabel = "Réessayer",
                        onAction = { onEnsureCategoryLoaded(type, selectedCategoryId, vodSort) },
                    )
                    entries.isEmpty() -> MobileEmptyState("Aucun contenu dans cette catégorie.")
                    isLive -> LiveList(
                        entries = entries,
                        epgPrograms = epgPrograms,
                        favorites = library.favoriteEntries,
                        lockedIds = lockedIds,
                        paged = false,
                        highlightKey = returnEntry?.key,
                        onOpen = { onLiveEntrySelected(it, entries) },
                        onLongPress = { sheet = BrowserSheet.EntryActions(it) },
                        onNearEnd = {},
                    )
                    else -> PosterGrid(
                        entries = entries,
                        favorites = library.favoriteEntries,
                        watched = library.watchedEntries,
                        onOpen = onEntrySelected,
                        onLongPress = { sheet = BrowserSheet.EntryActions(it) },
                        onNearEnd = {
                            if (paged && selectedCategoryId != FAVORITES_CATEGORY_ID && selectedCategoryId != HISTORY_CATEGORY_ID) {
                                onLoadMoreInCategory(type, selectedCategoryId, vodSort)
                            }
                        },
                    )
                }
            }
        }

        // Direct : bouton flottant pour changer de catégorie depuis la liste des chaînes, sans remonter aux pastilles.
        if (isLive && sheet == null) {
            fr.streamia.tv.ui.AccentPill(
                Modifier.align(Alignment.BottomEnd).padding(end = MobileGutter, bottom = 16.dp).height(52.dp)
                    .clickable(onClickLabel = "Catégories") { sheet = BrowserSheet.Categories },
            ) {
                Row(Modifier.height(52.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamiaIcon(StreamiaIconGlyph.Reorder, tint = Ink, size = 20.dp)
                    Text("Catégories", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        when (val current = sheet) {
            null -> Unit
            BrowserSheet.Categories -> MobileBottomSheet("Catégories", "${categories.size} au total", onDismiss = { sheet = null }) {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    categories.forEach { category ->
                        val locked = appSettings.parentalControlEnabled && !parentalUnlocked && category.key in library.lockedCategories
                        val on = category.id == selectedCategoryId
                        Row(
                            Modifier.fillMaxWidth().height(52.dp)
                                .clickable { sheet = null; selectCategory(category) }
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (locked) StreamiaIcon(StreamiaIconGlyph.Lock, tint = WarmSignal, size = 16.dp)
                            Text(
                                category.name, color = if (on) AccentPink else Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, modifier = Modifier.weight(1f),
                            )
                            if (on) Text("✓", color = AccentPink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            is BrowserSheet.EntryActions -> {
                val entry = current.entry
                val favorite = entry.key in library.favoriteEntries
                MobileBottomSheet(entry.displayName, subtitle = if (isLive) "Chaîne ${entry.number}" else type.displayName, onDismiss = { sheet = null }) {
                    MobileSheetAction(if (isLive) "Regarder" else "Ouvrir la fiche", StreamiaIconGlyph.Movie, onClick = {
                        sheet = null
                        if (isLive) onLiveEntrySelected(entry, entries) else onEntrySelected(entry)
                    })
                    MobileSheetAction(
                        if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
                        if (favorite) StreamiaIconGlyph.StarOutline else StreamiaIconGlyph.Star,
                        onClick = { sheet = null; onToggleEntryFavorite(entry) },
                    )
                    MobileSheetAction("Masquer", StreamiaIconGlyph.EyeOff, onClick = { sheet = null; onToggleEntryHidden(entry) })
                }
            }
            BrowserSheet.Sort -> MobileBottomSheet("Trier", onDismiss = { sheet = null }) {
                if (isLive) {
                    listOf(
                        LiveChannelSortOrder.Provider to "Ordre du fournisseur",
                        LiveChannelSortOrder.Number to "Par numéro",
                        LiveChannelSortOrder.Alphabetical to "Alphabétique",
                    ).forEach { (order, label) ->
                        SortRow(label, selected = liveSort == order) { liveSort = order; sheet = null }
                    }
                } else {
                    listOf(
                        VodSortOrder.Provider to "Ordre du fournisseur",
                        VodSortOrder.RecentlyAdded to "Récemment ajoutés",
                        VodSortOrder.Rating to "Mieux notés",
                        VodSortOrder.Alphabetical to "Alphabétique",
                    ).forEach { (order, label) ->
                        SortRow(label, selected = vodSort == order) { vodSort = order; sheet = null }
                    }
                }
            }
            BrowserSheet.Numpad -> MobileKeypadSheet(
                title = "Aller à la chaîne",
                subtitle = "Saisissez un numéro de chaîne",
                maxDigits = 4,
                masked = false,
                autoSubmit = false,
                confirmLabel = "Aller",
                onSubmit = { typed ->
                    val number = typed.toIntOrNull()
                    val target = number?.let { n -> catalog.entriesFor(MediaType.Live).firstOrNull { it.number == n } }
                    if (target == null) {
                        "Aucune chaîne $typed"
                    } else {
                        sheet = null
                        onLiveEntrySelected(target, catalog.entriesFor(MediaType.Live))
                        null
                    }
                },
                onDismiss = { sheet = null },
            )
            is BrowserSheet.Pin -> MobileKeypadSheet(
                title = "Code parental",
                subtitle = "Catégorie « ${current.category.name} » verrouillée",
                maxDigits = 4,
                masked = true,
                autoSubmit = true,
                confirmLabel = "Valider",
                onSubmit = { typed ->
                    if (onVerifyParentalPin(typed)) {
                        selectedCategoryId = current.category.id
                        sheet = null
                        null
                    } else {
                        "Code incorrect"
                    }
                },
                onDismiss = { sheet = null },
            )
        }
    }
}

@Composable
private fun LiveList(
    entries: List<MediaEntry>,
    epgPrograms: Map<String, List<EpgProgram>>,
    favorites: Set<String>,
    lockedIds: Set<String>,
    paged: Boolean,
    highlightKey: String?,
    onOpen: (MediaEntry) -> Unit,
    onLongPress: (MediaEntry) -> Unit,
    onNearEnd: () -> Unit,
) {
    val state = rememberLazyListState()
    NearEndEffect(state, entries.size, paged, onNearEnd)
    // Défilement jusqu'à la chaîne de retour, une seule fois, dès qu'elle est dans la liste affichée.
    var scrolled by remember(highlightKey) { mutableStateOf(false) }
    LaunchedEffect(highlightKey, entries) {
        if (highlightKey == null || scrolled) return@LaunchedEffect
        val index = entries.indexOfFirst { it.key == highlightKey }
        if (index >= 0) {
            state.scrollToItem((index - 1).coerceAtLeast(0))
            scrolled = true
        }
    }
    // Programme en cours recalculé toutes les 30 s.
    val nowSeconds by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis() / 1000
        }
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = MobileGutter, end = MobileGutter, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries, key = { it.key }) { entry ->
            val program = remember(epgPrograms, entry.key, nowSeconds / 30) {
                epgPrograms[entry.key]?.epgNowContextAt(nowSeconds)?.current
            }
            val progress = program?.let {
                val start = it.startEpochSeconds
                val end = it.endEpochSeconds
                if (start != null && end != null && end > start) ((nowSeconds - start).toFloat() / (end - start)).coerceIn(0f, 1f) else null
            }
            MobileCard(
                Modifier.then(if (entry.key == highlightKey) Modifier.border(2.dp, AccentPink, RoundedCornerShape(RadiusTile)) else Modifier),
                onClick = { onOpen(entry) }, onLongClick = { onLongPress(entry) },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ChannelLogo(entry.iconUrl, entry.displayName, Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)), imagePadding = 6)
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${entry.number}  ${entry.displayName}",
                            color = Ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (program != null) {
                            Text(program.title, color = MutedInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (progress != null) {
                            Spacer(Modifier.height(6.dp))
                            ProgressBar(progress, Modifier.fillMaxWidth().height(3.dp))
                        }
                    }
                    if (entry.key in favorites) StreamiaIcon(StreamiaIconGlyph.Star, size = 16.dp)
                    if (entry.categoryId in lockedIds) StreamiaIcon(StreamiaIconGlyph.Lock, tint = WarmSignal, size = 16.dp)
                }
            }
        }
    }
}

@Composable
private fun PosterGrid(
    entries: List<MediaEntry>,
    favorites: Set<String>,
    watched: Set<String>,
    onOpen: (MediaEntry) -> Unit,
    onLongPress: (MediaEntry) -> Unit,
    onNearEnd: () -> Unit,
) {
    val state = rememberLazyGridState()
    NearEndEffect(state, entries.size, true, onNearEnd)
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = MobileGutter, end = MobileGutter, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(entries, key = { it.key }) { entry ->
            MobileCard(onClick = { onOpen(entry) }, onLongClick = { onLongPress(entry) }, radius = RadiusTile) {
                Column {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                        MediaArtwork(entry.iconUrl, entry.displayName, Modifier.fillMaxSize())
                        if (entry.key in favorites) {
                            StreamiaIcon(StreamiaIconGlyph.Star, size = 16.dp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
                        }
                        if (entry.key in watched) {
                            StreamiaIcon(StreamiaIconGlyph.Eye, tint = Ink, size = 16.dp, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
                        }
                    }
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(entry.displayName, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        entry.rating?.takeIf { it > 0.0 }?.let {
                            Text("★ ${"%.1f".format(java.util.Locale.FRENCH, it)}", color = MutedInk, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Demande la page suivante quand les derniers éléments approchent de l'écran. */
@Composable
private fun NearEndEffect(state: LazyListState, count: Int, enabled: Boolean, onNearEnd: () -> Unit) {
    LaunchedEffect(state, count, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= count - LOAD_MORE_THRESHOLD) onNearEnd() }
    }
}

@Composable
private fun NearEndEffect(state: LazyGridState, count: Int, enabled: Boolean, onNearEnd: () -> Unit) {
    LaunchedEffect(state, count, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= count - LOAD_MORE_THRESHOLD) onNearEnd() }
    }
}

@Composable
private fun SortRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        if (selected) Text("✓", color = AccentPink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxSize().background(AccentPink))
    }
}

@Composable
internal fun OfflineTag() {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(WarmSignal.copy(alpha = 0.2f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) { Text("Mode cache", color = WarmSignal, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
internal fun MobileMessage(text: String, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MobileGutter)
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { Text(text, color = Ink, fontSize = 13.sp) }
}

@Composable
internal fun MobileEmptyState(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = MutedInk, fontSize = 14.sp)
        if (actionLabel != null) {
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(AccentPink)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) { Text(actionLabel, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

private fun defaultCategoryId(catalog: Catalog, type: MediaType): String =
    catalog.categoriesFor(type).firstOrNull { catalog.countIn(type, it.id) > 0 }?.id ?: Catalog.ALL_CATEGORY_ID

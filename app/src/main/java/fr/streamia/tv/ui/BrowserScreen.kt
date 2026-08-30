package fr.streamia.tv.ui

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import fr.streamia.tv.data.PlaybackHistoryItem
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.WarmSignal
import kotlinx.coroutines.yield

private const val FAVORITES_CATEGORY_ID = "__favorites__"
private const val HISTORY_CATEGORY_ID = "__history__"

@Composable
fun BrowserScreen(
    catalog: Catalog,
    library: UserLibrarySnapshot,
    offline: Boolean,
    busy: Boolean,
    message: String?,
    initialType: MediaType? = null,
    initialCategoryId: String? = null,
    onEntrySelected: (MediaEntry) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    onToggleCategoryFavorite: (MediaCategory) -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onOrganizer: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val defaultType = initialType ?: MediaType.entries.firstOrNull { catalog.count(it) > 0 } ?: MediaType.Live
    var selectedType by remember(catalog, initialType) { mutableStateOf(defaultType) }
    var selectedCategoryId by remember(catalog, initialCategoryId, selectedType) {
        mutableStateOf(initialCategoryId ?: Catalog.ALL_CATEGORY_ID)
    }

    val baseCategories = remember(catalog, selectedType, library.favoriteCategories) {
        val raw = catalog.categoriesFor(selectedType)
        val (favorite, other) = raw.partition { it.key in library.favoriteCategories }
        favorite + other
    }
    val favoriteEntriesForType = remember(catalog, selectedType, library.favoriteEntries) {
        catalog.entriesFor(selectedType).filter { it.key in library.favoriteEntries }
    }
    val historyForType = remember(catalog, selectedType, library.history) {
        library.history.asSequence()
            .map { item -> item to (catalog.entry(item.entry.key) ?: item.entry) }
            .filter { (_, entry) -> entry.type == selectedType }
            .toList()
    }
    val categories = remember(baseCategories, favoriteEntriesForType.size, historyForType.size, selectedType) {
        buildList {
            add(Catalog.allCategory(selectedType))
            if (favoriteEntriesForType.isNotEmpty()) add(MediaCategory(FAVORITES_CATEGORY_ID, "★ Favoris", selectedType))
            if (historyForType.isNotEmpty()) add(MediaCategory(HISTORY_CATEGORY_ID, "↺ Historique", selectedType))
            addAll(baseCategories)
        }
    }
    val entries = remember(catalog, selectedType, selectedCategoryId, favoriteEntriesForType, historyForType) {
        when (selectedCategoryId) {
            FAVORITES_CATEGORY_ID -> favoriteEntriesForType
            HISTORY_CATEGORY_ID -> historyForType.map { it.second }
            else -> catalog.entriesIn(selectedType, selectedCategoryId)
        }
    }
    val historyByKey = remember(historyForType) { historyForType.associate { it.second.key to it.first } }

    LaunchedEffect(selectedType) {
        if (initialType != selectedType || categories.none { it.id == selectedCategoryId }) {
            selectedCategoryId = Catalog.ALL_CATEGORY_ID
        }
    }

    Column(Modifier.fillMaxSize().background(Night)) {
        BrowserHeader(
            catalog = catalog,
            selectedType = selectedType,
            offline = offline,
            busy = busy,
            onTypeSelected = { selectedType = it; selectedCategoryId = Catalog.ALL_CATEGORY_ID },
            onSearch = onSearch,
            onEpg = onEpg,
            onOrganizer = onOrganizer,
            onRefresh = onRefresh,
            onLogout = onLogout,
        )
        if (message != null) {
            FocusableSurface(
                onClick = onDismissMessage,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp).height(48.dp),
            ) {
                Text(
                    "$message  ·  OK pour fermer",
                    color = if (message.contains("média", ignoreCase = true) || message.contains("import", ignoreCase = true)) FocusBlueBright else MaterialTheme.colorScheme.error,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        }
        Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 22.dp)) {
            CategoryRail(
                type = selectedType,
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                favoriteCategories = library.favoriteCategories,
                countFor = { category ->
                    when (category.id) {
                        FAVORITES_CATEGORY_ID -> favoriteEntriesForType.size
                        HISTORY_CATEGORY_ID -> historyForType.size
                        else -> catalog.entriesIn(selectedType, category.id).size
                    }
                },
                onSelected = { selectedCategoryId = it.id },
                onToggleFavorite = onToggleCategoryFavorite,
                modifier = Modifier.width(330.dp).fillMaxHeight(),
            )
            Spacer(Modifier.width(22.dp))
            MediaGrid(
                type = selectedType,
                categoryName = categories.firstOrNull { it.id == selectedCategoryId }?.name.orEmpty(),
                entries = entries,
                favoriteEntries = library.favoriteEntries,
                historyByKey = historyByKey,
                onEntrySelected = onEntrySelected,
                onToggleFavorite = onToggleEntryFavorite,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun BrowserHeader(
    catalog: Catalog,
    selectedType: MediaType,
    offline: Boolean,
    busy: Boolean,
    onTypeSelected: (MediaType) -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onOrganizer: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(92.dp).padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreamiaLogo(compact = true)
        Spacer(Modifier.width(20.dp))
        for (type in MediaType.entries) {
            FocusableSurface(
                onClick = { onTypeSelected(type) },
                selected = selectedType == type,
                enabled = catalog.count(type) > 0,
                modifier = Modifier.width(112.dp).height(50.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Text(type.displayName, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(catalog.count(type).toString(), color = MutedInk, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.width(7.dp))
        }
        Spacer(Modifier.weight(1f))
        HeaderAction("⌕ Rechercher", 126.dp, onSearch)
        Spacer(Modifier.width(7.dp))
        HeaderAction("EPG", 82.dp, onEpg)
        Spacer(Modifier.width(7.dp))
        HeaderAction("Organiser", 104.dp, onOrganizer)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(7.dp).background(if (offline) WarmSignal else Color(0xFF6CCB91)))
        Spacer(Modifier.width(7.dp))
        Text(if (offline) "Cache" else "En ligne", color = if (offline) WarmSignal else MutedInk, fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        HeaderAction(if (busy) "…" else "↻", 58.dp, onRefresh, !busy)
        Spacer(Modifier.width(7.dp))
        HeaderAction("Compte", 86.dp, onLogout)
    }
}

@Composable
private fun HeaderAction(label: String, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit, enabled: Boolean = true) {
    FocusableSurface(onClick = onClick, enabled = enabled, modifier = Modifier.width(width).height(50.dp)) {
        Text(label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
private fun CategoryRail(
    type: MediaType,
    categories: List<MediaCategory>,
    selectedCategoryId: String,
    favoriteCategories: Set<String>,
    countFor: (MediaCategory) -> Int,
    onSelected: (MediaCategory) -> Unit,
    onToggleFavorite: (MediaCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            "Catégories · ${type.displayName}",
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(categories, key = MediaCategory::key) { category ->
                val virtual = category.id in setOf(Catalog.ALL_CATEGORY_ID, FAVORITES_CATEGORY_ID, HISTORY_CATEGORY_ID)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FocusableSurface(
                        onClick = { onSelected(category) },
                        selected = selectedCategoryId == category.id,
                        modifier = Modifier.weight(1f).height(58.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                category.name,
                                color = Ink,
                                fontSize = 15.sp,
                                fontWeight = if (selectedCategoryId == category.id) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(countFor(category).toString(), color = MutedInk, fontSize = 12.sp)
                        }
                    }
                    if (!virtual) {
                        FocusableSurface(
                            onClick = { onToggleFavorite(category) },
                            selected = category.key in favoriteCategories,
                            modifier = Modifier.width(54.dp).height(58.dp),
                        ) {
                            Text(if (category.key in favoriteCategories) "★" else "☆", color = FocusBlueBright, fontSize = 20.sp, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaGrid(
    type: MediaType,
    categoryName: String,
    entries: List<MediaEntry>,
    favoriteEntries: Set<String>,
    historyByKey: Map<String, PlaybackHistoryItem>,
    onEntrySelected: (MediaEntry) -> Unit,
    onToggleFavorite: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstEntryFocus = remember(type, categoryName) { FocusRequester() }
    LaunchedEffect(type, categoryName, entries.size) {
        if (entries.isNotEmpty()) {
            yield()
            runCatching { firstEntryFocus.requestFocus() }
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            Text(categoryName, style = MaterialTheme.typography.headlineSmall, color = Ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Text("${entries.size} ${type.pluralName}", color = MutedInk, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text("↑ ↓ ← → naviguer · OK ouvrir · ☆ favori", color = MutedInk, fontSize = 12.sp)
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().background(DeepSurface), contentAlignment = Alignment.Center) {
                Text("Aucun contenu dans cette catégorie", color = MutedInk, fontSize = 19.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (type == MediaType.Live) 260.dp else 285.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(entries, key = MediaEntry::key) { entry ->
                    MediaCard(
                        entry = entry,
                        favorite = entry.key in favoriteEntries,
                        history = historyByKey[entry.key],
                        onClick = { onEntrySelected(entry) },
                        onToggleFavorite = { onToggleFavorite(entry) },
                        modifier = if (entry.key == entries.first().key) Modifier.focusRequester(firstEntryFocus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaCard(
    entry: MediaEntry,
    favorite: Boolean,
    history: PlaybackHistoryItem?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusableSurface(
            onClick = onClick,
            modifier = Modifier.weight(1f).height(if (entry.type == MediaType.Live) 112.dp else 142.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(entry.iconUrl, entry.displayName, Modifier.size(if (entry.type == MediaType.Live) 78.dp else 105.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.displayName,
                        color = Ink,
                        fontSize = 15.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    val detail = when (entry.type) {
                        MediaType.Live -> "CH ${entry.number}"
                        MediaType.Movie -> entry.rating?.let { "★ ${"%.1f".format(it)}" } ?: "Film"
                        MediaType.Series -> entry.rating?.let { "★ ${"%.1f".format(it)}" } ?: if (entry.playable) "Épisode" else "Série"
                    }
                    Text(detail, color = FocusBlueBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (history != null && history.progress > 0.02f) {
                        Spacer(Modifier.height(4.dp))
                        Text("Reprise ${history.progressPercent()}%", color = MutedInk, fontSize = 11.sp)
                    }
                }
            }
        }
        FocusableSurface(
            onClick = onToggleFavorite,
            selected = favorite,
            modifier = Modifier.width(52.dp).height(if (entry.type == MediaType.Live) 112.dp else 142.dp),
        ) {
            Text(if (favorite) "★" else "☆", color = FocusBlueBright, fontSize = 20.sp, modifier = Modifier.padding(start = 15.dp))
        }
    }
}

private fun PlaybackHistoryItem.progressPercent(): Int = (progress * 100).toInt().coerceIn(0, 100)

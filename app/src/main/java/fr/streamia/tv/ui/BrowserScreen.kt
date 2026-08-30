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

@Composable
fun BrowserScreen(
    catalog: Catalog,
    offline: Boolean,
    busy: Boolean,
    message: String?,
    onEntrySelected: (MediaEntry) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val initialType = remember(catalog) {
        MediaType.entries.firstOrNull { catalog.count(it) > 0 } ?: MediaType.Live
    }
    var selectedType by remember(catalog) { mutableStateOf(initialType) }
    var selectedCategoryId by remember(catalog) { mutableStateOf(Catalog.ALL_CATEGORY_ID) }
    val categories = remember(catalog, selectedType) {
        listOf(Catalog.allCategory(selectedType)) + catalog.categoriesFor(selectedType)
    }
    val entries = remember(catalog, selectedType, selectedCategoryId) {
        catalog.entriesIn(selectedType, selectedCategoryId)
    }

    LaunchedEffect(selectedType) { selectedCategoryId = Catalog.ALL_CATEGORY_ID }

    Column(Modifier.fillMaxSize().background(Night)) {
        BrowserHeader(
            catalog = catalog,
            selectedType = selectedType,
            offline = offline,
            busy = busy,
            onTypeSelected = { selectedType = it },
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
                    color = if (message.contains("import", ignoreCase = true)) FocusBlueBright else MaterialTheme.colorScheme.error,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        }
        Row(Modifier.fillMaxSize().padding(start = 26.dp, end = 26.dp, bottom = 24.dp)) {
            CategoryRail(
                type = selectedType,
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                countFor = { catalog.entriesIn(selectedType, it.id).size },
                onSelected = { selectedCategoryId = it.id },
                modifier = Modifier.width(310.dp).fillMaxHeight(),
            )
            Spacer(Modifier.width(26.dp))
            MediaGrid(
                type = selectedType,
                categoryName = categories.firstOrNull { it.id == selectedCategoryId }?.name.orEmpty(),
                entries = entries,
                onEntrySelected = onEntrySelected,
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
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(98.dp).padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreamiaLogo(compact = true)
        Spacer(Modifier.width(26.dp))
        for (type in MediaType.entries) {
            FocusableSurface(
                onClick = { onTypeSelected(type) },
                selected = selectedType == type,
                enabled = catalog.count(type) > 0,
                modifier = Modifier.width(128.dp).height(52.dp),
                contentDescription = "${type.displayName}, ${catalog.count(type)} ${type.pluralName}",
            ) {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    Text(type.displayName, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(catalog.count(type).toString(), color = MutedInk, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(9.dp))
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(7.dp).background(if (offline) WarmSignal else Color(0xFF6CCB91)))
        Spacer(Modifier.width(9.dp))
        Text(if (offline) "Hors ligne" else "Connecté", color = if (offline) WarmSignal else MutedInk, fontSize = 14.sp)
        Spacer(Modifier.width(16.dp))
        FocusableSurface(onClick = onRefresh, enabled = !busy, modifier = Modifier.width(138.dp).height(52.dp)) {
            Text(if (busy) "Chargement…" else "↻ Actualiser", color = Ink, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 15.dp))
        }
        Spacer(Modifier.width(9.dp))
        FocusableSurface(onClick = onLogout, modifier = Modifier.width(120.dp).height(52.dp)) {
            Text("Compte", color = Ink, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 15.dp))
        }
    }
}

@Composable
private fun CategoryRail(
    type: MediaType,
    categories: List<MediaCategory>,
    selectedCategoryId: String,
    countFor: (MediaCategory) -> Int,
    onSelected: (MediaCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            "Catégories · ${type.displayName}",
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 14.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(categories, key = MediaCategory::key) { category ->
                FocusableSurface(
                    onClick = { onSelected(category) },
                    selected = selectedCategoryId == category.id,
                    modifier = Modifier.fillMaxWidth().height(62.dp),
                    contentDescription = "Catégorie ${category.name}, ${countFor(category)} éléments",
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            category.name,
                            color = Ink,
                            fontSize = 17.sp,
                            fontWeight = if (selectedCategoryId == category.id) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(countFor(category).toString(), color = MutedInk, fontSize = 14.sp)
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
    onEntrySelected: (MediaEntry) -> Unit,
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
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
            Text(categoryName, style = MaterialTheme.typography.headlineSmall, color = Ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(14.dp))
            Text("${entries.size} ${type.pluralName}", color = MutedInk, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text("↑ ↓ ← → naviguer    OK ouvrir", color = MutedInk, fontSize = 14.sp)
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().background(DeepSurface), contentAlignment = Alignment.Center) {
                Text("Aucun contenu dans cette catégorie", color = MutedInk, fontSize = 19.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (type == MediaType.Live) 238.dp else 260.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(entries, key = MediaEntry::key) { entry ->
                    MediaCard(
                        entry = entry,
                        onClick = { onEntrySelected(entry) },
                        modifier = if (entry.key == entries.first().key) Modifier.focusRequester(firstEntryFocus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaCard(entry: MediaEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(if (entry.type == MediaType.Live) 116.dp else 140.dp),
        contentDescription = "Ouvrir ${entry.displayName}",
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(entry.iconUrl, entry.displayName, Modifier.size(if (entry.type == MediaType.Live) 82.dp else 108.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.displayName,
                    color = Ink,
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                val detail = when (entry.type) {
                    MediaType.Live -> "CH ${entry.number}"
                    MediaType.Movie -> entry.rating?.let { "★ ${"%.1f".format(it)}" } ?: "Film"
                    MediaType.Series -> entry.rating?.let { "★ ${"%.1f".format(it)}" } ?: "Voir les épisodes"
                }
                Text(detail, color = FocusBlueBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

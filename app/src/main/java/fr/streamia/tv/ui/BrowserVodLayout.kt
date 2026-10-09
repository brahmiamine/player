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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.PlaybackHistoryItem
import fr.streamia.tv.data.VodSortOrder
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusCard
import fr.streamia.tv.ui.theme.TypeBody
import fr.streamia.tv.ui.theme.TypeSectionTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

// Disposition Films/Séries : grille d'affiches paginée.

@Composable
internal fun VodCatalogLayout(
    type: MediaType,
    catalog: Catalog,
    categories: List<MediaCategory>,
    selectedCategoryId: String,
    entries: List<MediaEntry>,
    loading: Boolean,
    loadError: Boolean,
    favoriteCategories: Set<String>,
    favoriteEntries: Set<String>,
    lockedCategories: Set<String>,
    historyCount: Int,
    historyByKey: Map<String, PlaybackHistoryItem>,
    restoreEntryKey: String?,
    onRestoreConsumed: () -> Unit,
    onCategorySelected: (MediaCategory) -> Unit,
    onToggleCategoryFavorite: (MediaCategory) -> Unit,
    onEntrySelected: (MediaEntry) -> Unit,
    onEntryFocused: (MediaEntry) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    onLoadMore: () -> Unit,
    sortOrder: VodSortOrder?,
    onSortSelected: (VodSortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Retour depuis la grille : remonte d'abord au rail des catégories, comme en Direct.
    var gridFocused by remember { mutableStateOf(false) }
    var railFocusRequest by remember { mutableIntStateOf(0) }
    BackHandler(enabled = gridFocused) { railFocusRequest++ }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CategoryRail(
            type = type,
            categories = categories,
            selectedCategoryId = selectedCategoryId,
            favoriteCategories = favoriteCategories,
            lockedCategories = lockedCategories,
            countFor = { category ->
                when (category.id) {
                    FAVORITES_CATEGORY_ID -> favoriteEntries.count { it.startsWith("${type.name}:") }
                    HISTORY_CATEGORY_ID -> historyCount
                    else -> catalog.countIn(type, category.id)
                }
            },
            onSelected = onCategorySelected,
            onToggleFavorite = onToggleCategoryFavorite,
            focusSelectedRequest = railFocusRequest,
            modifier = Modifier.width(250.dp).fillMaxHeight(),
        )

        // Une grille neuve par catégorie : la nouvelle liste repart en haut au lieu de garder le
        // défilement de la précédente (le retour depuis une fiche repositionne via restoreEntryKey).
        key(type, selectedCategoryId) { PosterGrid(
            type = type,
            categoryName = categories.firstOrNull { it.id == selectedCategoryId }?.name.orEmpty(),
            // Favoris/Historique sont déjà entièrement matérialisés (dérivés de library.*), à la
            // différence d'une vraie catégorie fournisseur dont le compte vient des métadonnées SQL.
            totalCount = when (selectedCategoryId) {
                FAVORITES_CATEGORY_ID, HISTORY_CATEGORY_ID -> entries.size
                else -> catalog.countIn(type, selectedCategoryId)
            },
            entries = entries,
            favoriteEntries = favoriteEntries,
            historyByKey = historyByKey,
            loading = loading,
            loadError = loadError,
            restoreEntryKey = restoreEntryKey,
            onRestoreConsumed = onRestoreConsumed,
            onEntrySelected = onEntrySelected,
            onEntryFocused = onEntryFocused,
            onToggleFavorite = onToggleEntryFavorite,
            onLoadMore = onLoadMore,
            sortOrder = sortOrder,
            onSortSelected = onSortSelected,
            // Gauche depuis la première colonne : retour sur la catégorie sélectionnée (comme Retour),
            // et non sur la catégorie voisine à l'écran que choisirait la recherche de focus.
            onLeftEdge = { railFocusRequest++ },
            modifier = Modifier.weight(1f).fillMaxHeight().onFocusChanged { gridFocused = it.hasFocus },
        ) }
    }
}

@Composable
private fun PosterGrid(
    type: MediaType,
    categoryName: String,
    totalCount: Int,
    entries: List<MediaEntry>,
    loading: Boolean,
    loadError: Boolean,
    favoriteEntries: Set<String>,
    historyByKey: Map<String, PlaybackHistoryItem>,
    restoreEntryKey: String?,
    onRestoreConsumed: () -> Unit,
    onEntrySelected: (MediaEntry) -> Unit,
    onEntryFocused: (MediaEntry) -> Unit,
    onToggleFavorite: (MediaEntry) -> Unit,
    onLoadMore: () -> Unit,
    sortOrder: VodSortOrder? = null,
    onSortSelected: (VodSortOrder) -> Unit = {},
    onLeftEdge: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Nouveau tri : la grille repart du début de la liste triée.
    val gridState = remember(sortOrder) { androidx.compose.foundation.lazy.grid.LazyGridState() }
    var sortDialogOpen by remember { mutableStateOf(false) }
    val sortButtonFocus = remember { FocusRequester() }
    LaunchedEffect(gridState, entries.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible -> if (lastVisible >= entries.size - LOAD_MORE_THRESHOLD) onLoadMore() }
    }
    val prefetchContext = LocalContext.current.applicationContext
    // Affiches de la rangée suivante préchargées pendant le défilement.
    LaunchedEffect(gridState, entries) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collectLatest { lastVisible ->
                if (lastVisible < 0) return@collectLatest
                val upcoming = entries.subList((lastVisible + 1).coerceAtMost(entries.size), (lastVisible + 1 + ARTWORK_PREFETCH_COUNT).coerceAtMost(entries.size))
                prefetchArtwork(prefetchContext, upcoming.map(MediaEntry::iconUrl), logo = false)
            }
    }

    // Retour depuis une fiche : on refait défiler jusqu'au contenu ouvert et on y repose le focus,
    // pour ne pas renvoyer l'utilisateur sur une liste qui repart du début.
    val restoreFocus = remember { FocusRequester() }
    LaunchedEffect(restoreEntryKey, entries) {
        if (restoreEntryKey == null || entries.isEmpty()) return@LaunchedEffect
        val index = entries.indexOfFirst { it.key == restoreEntryKey }
        if (index < 0) {
            onRestoreConsumed()
            return@LaunchedEffect
        }
        gridState.scrollToItem(index)
        delay(BROWSER_RESTORE_FOCUS_DELAY_MS)
        runCatching { restoreFocus.requestFocus() }
        onRestoreConsumed()
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(categoryName.ifBlank { type.displayName }, color = Ink, fontSize = TypeSectionTitle, fontWeight = HeadingWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(10.dp))
            Text("$totalCount ${type.pluralName}", color = MutedInk, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text("OK ouvrir · OK long ajouter/retirer favori", color = MutedInk, fontSize = 14.sp)
            if (sortOrder != null) {
                Spacer(Modifier.width(14.dp))
                FocusableSurface(onClick = { sortDialogOpen = true }, modifier = Modifier.width(290.dp).height(44.dp).focusRequester(sortButtonFocus)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Trier : " + vodSortLabel(sortOrder),
                            color = Ink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (sortDialogOpen && sortOrder != null) {
            val orders = VodSortOrder.entries
            ChoiceDialog(
                title = "Trier « " + categoryName.ifBlank { type.displayName } + " »",
                options = orders.map(::vodSortLabel),
                selectedIndex = orders.indexOf(sortOrder),
                onSelect = { index ->
                    sortDialogOpen = false
                    onSortSelected(orders[index])
                    runCatching { sortButtonFocus.requestFocus() }
                },
                onDismiss = {
                    sortDialogOpen = false
                    runCatching { sortButtonFocus.requestFocus() }
                },
            )
        }

        if (entries.isEmpty()) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(RadiusCard)).background(DeepSurface),
                contentAlignment = Alignment.Center,
            ) {
                if (loadError) {
                    PageLoadRetry(onRetry = onLoadMore)
                } else {
                    Text(
                        // Distinguer « la page arrive » de « la catégorie est vide » : une lecture SQLite
                        // sur une catégorie jamais ouverte n'est pas un catalogue vide.
                        if (loading) "Chargement…" else "Aucun contenu dans cette catégorie",
                        color = MutedInk,
                        fontSize = TypeBody,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(155.dp),
                // Marge pour la carte focalisée (agrandie + bordure) : sinon rognée en haut, en bas et sur les côtés.
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = MediaEntry::key) { entry ->
                    PosterCard(
                        entry = entry,
                        favorite = entry.key in favoriteEntries,
                        history = historyByKey[entry.key],
                        onClick = { onEntrySelected(entry) },
                        onFocused = { onEntryFocused(entry) },
                        onLongClick = { onToggleFavorite(entry) },
                        modifier = Modifier
                            .onPreviewKeyEvent { event ->
                                if (
                                    event.type == KeyEventType.KeyDown &&
                                    event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
                                    gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.key }?.column == 0
                                ) {
                                    onLeftEdge()
                                    true
                                } else false
                            }
                            .then(if (entry.key == restoreEntryKey) Modifier.focusRequester(restoreFocus) else Modifier),
                    )
                }
                // Pied de grille : page suivante en cours, ou en échec avec nouvel essai.
                if (loadError || loading) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "page-status") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            if (loadError) PageLoadRetry(onRetry = onLoadMore)
                            else Text("Chargement…", color = MutedInk, fontSize = TypeBody)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageLoadRetry(onRetry: () -> Unit) {
    FocusableSurface(onClick = onRetry, modifier = Modifier.width(420.dp).height(54.dp)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Chargement impossible · OK pour réessayer", color = Ink, fontSize = TypeBody)
        }
    }
}

@Composable
private fun PosterCard(
    entry: MediaEntry,
    favorite: Boolean,
    history: PlaybackHistoryItem?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        onClick = onClick,
        onFocused = onFocused,
        onLongClick = onLongClick,
        // Pas d'état « sélectionné » pour un favori : il ressemblait au focus. L'étoile suffit.
        modifier = modifier.fillMaxWidth().height(252.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Box(Modifier.fillMaxWidth().height(175.dp)) {
                MediaArtwork(entry.iconUrl, entry.displayName, Modifier.fillMaxSize())
                // Favori : étoile en coin d'affiche, visible même quand la progression occupe le bas.
                if (favorite) {
                    StreamiaIcon(StreamiaIconGlyph.Star, size = 20.dp, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp))
                }
                if (history != null && history.progress > 0.02f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.45f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(history.progress).background(FocusBlueBright))
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                aiTitle(entry),
                color = Ink,
                fontSize = 15.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Note en « x/10 », sans étoile : l'étoile est réservée aux favoris.
                val ratingText = entry.rating?.let(::formatRating)
                if (ratingText != null) {
                    Text(ratingText, color = FocusBlueBright, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                } else {
                    val label = if (entry.type == MediaType.Series && entry.playable) "Épisode" else entry.type.displayName
                    Text(label, color = FocusBlueBright, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                if (history != null && history.progress > 0.02f) {
                    Text("${history.progressPercent()}%", color = MutedInk, fontSize = 14.sp)
                }
            }
        }
    }
}

/** Note affichée partout sous la forme « 7.5/10 » (l'étoile désigne les favoris) ; null si hors échelle. */
internal fun formatRating(rating: Double): String? = rating.takeIf { it in 0.0..10.0 }?.let { "%.1f/10".format(it) }

private fun PlaybackHistoryItem.progressPercent(): Int = (progress * 100).toInt().coerceIn(0, 100)

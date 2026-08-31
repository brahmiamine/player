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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import fr.streamia.tv.data.PlaybackHistoryItem
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.XtreamUrlBuilder
import fr.streamia.tv.player.StreamiaPlayerFactory
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.WarmSignal
import kotlinx.coroutines.delay

private const val FAVORITES_CATEGORY_ID = "__favorites__"
private const val HISTORY_CATEGORY_ID = "__history__"

/**
 * Le preview Live doit libérer sa connexion AVANT de démarrer le plein écran.
 * Beaucoup de fournisseurs Xtream limitent le compte à une seule connexion simultanée.
 */
private class LivePreviewHandle {
    var stop: () -> Unit = {}
}

@Composable
fun BrowserScreen(
    catalog: Catalog,
    credentials: ServerCredentials,
    library: UserLibrarySnapshot,
    offline: Boolean,
    busy: Boolean,
    message: String?,
    initialType: MediaType? = null,
    initialCategoryId: String? = null,
    onEntrySelected: (MediaEntry) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    onToggleCategoryFavorite: (MediaCategory) -> Unit,
    onLocationChanged: (MediaType, String?) -> Unit,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onSettings: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    BackHandler(onBack = onHome)

    val defaultType = initialType
        ?.takeIf { catalog.count(it) > 0 }
        ?: MediaType.entries.firstOrNull { catalog.count(it) > 0 }
        ?: MediaType.Live
    var selectedType by remember(catalog, initialType) { mutableStateOf(defaultType) }
    var selectedCategoryId by remember(catalog, initialCategoryId, selectedType) {
        mutableStateOf(initialCategoryId ?: defaultCategoryId(catalog, selectedType))
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
            if (favoriteEntriesForType.isNotEmpty()) {
                add(MediaCategory(FAVORITES_CATEGORY_ID, "★ Favoris", selectedType))
            }
            if (historyForType.isNotEmpty()) {
                add(MediaCategory(HISTORY_CATEGORY_ID, "↺ Historique", selectedType))
            }
            addAll(baseCategories)
            add(Catalog.allCategory(selectedType))
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

    LaunchedEffect(selectedType, categories) {
        if (categories.none { it.id == selectedCategoryId }) {
            selectedCategoryId = defaultCategoryId(catalog, selectedType)
        }
    }
    LaunchedEffect(selectedType, selectedCategoryId) {
        onLocationChanged(selectedType, selectedCategoryId)
    }

    Column(Modifier.fillMaxSize().background(Night)) {
        BrowserHeader(
            catalog = catalog,
            selectedType = selectedType,
            offline = offline,
            busy = busy,
            onHome = onHome,
            onTypeSelected = {
                selectedType = it
                selectedCategoryId = defaultCategoryId(catalog, it)
            },
            onSearch = onSearch,
            onEpg = onEpg,
            onSettings = onSettings,
        )

        if (message != null) {
            FocusableSurface(
                onClick = onDismissMessage,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 3.dp).height(40.dp),
            ) {
                Text(
                    "$message  ·  OK fermer",
                    color = if (
                        message.contains("média", ignoreCase = true) ||
                        message.contains("import", ignoreCase = true)
                    ) FocusBlueBright else MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (selectedType == MediaType.Live) {
            LiveCatalogLayout(
                catalog = catalog,
                credentials = credentials,
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                entries = entries,
                favoriteCategories = library.favoriteCategories,
                favoriteEntries = library.favoriteEntries,
                historyCount = historyForType.size,
                onCategorySelected = { selectedCategoryId = it.id },
                onToggleCategoryFavorite = onToggleCategoryFavorite,
                onEntrySelected = onEntrySelected,
                onToggleEntryFavorite = onToggleEntryFavorite,
                modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            )
        } else {
            VodCatalogLayout(
                type = selectedType,
                catalog = catalog,
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                entries = entries,
                favoriteCategories = library.favoriteCategories,
                favoriteEntries = library.favoriteEntries,
                historyCount = historyForType.size,
                historyByKey = historyByKey,
                onCategorySelected = { selectedCategoryId = it.id },
                onToggleCategoryFavorite = onToggleCategoryFavorite,
                onEntrySelected = onEntrySelected,
                onToggleEntryFavorite = onToggleEntryFavorite,
                modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
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
    onHome: () -> Unit,
    onTypeSelected: (MediaType) -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(74.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreamiaLogo(compact = true)
        Spacer(Modifier.width(16.dp))
        HeaderAction("Accueil", 90.dp, onHome)
        Spacer(Modifier.width(6.dp))

        for (type in MediaType.entries) {
            FocusableSurface(
                onClick = { onTypeSelected(type) },
                selected = selectedType == type,
                enabled = catalog.count(type) > 0,
                modifier = Modifier.width(104.dp).height(44.dp),
            ) {
                Column(Modifier.padding(horizontal = 10.dp)) {
                    Text(type.displayName, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(catalog.count(type).toString(), color = MutedInk, fontSize = 9.sp)
                }
            }
            Spacer(Modifier.width(6.dp))
        }

        Spacer(Modifier.weight(1f))
        HeaderAction("⌕", 52.dp, onSearch)
        Spacer(Modifier.width(6.dp))
        HeaderAction("EPG", 68.dp, onEpg, catalog.count(MediaType.Live) > 0)
        Spacer(Modifier.width(6.dp))
        HeaderAction("⚙", 52.dp, onSettings)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(6.dp).background(if (offline) WarmSignal else Color(0xFF6CCB91)))
        Spacer(Modifier.width(5.dp))
        Text(
            when {
                busy -> "Chargement…"
                offline -> "Cache"
                else -> "Local / en ligne"
            },
            color = if (offline) WarmSignal else MutedInk,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun HeaderAction(
    label: String,
    width: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    FocusableSurface(onClick = onClick, enabled = enabled, modifier = Modifier.width(width).height(44.dp)) {
        Text(label, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

@Composable
private fun LiveCatalogLayout(
    catalog: Catalog,
    credentials: ServerCredentials,
    categories: List<MediaCategory>,
    selectedCategoryId: String,
    entries: List<MediaEntry>,
    favoriteCategories: Set<String>,
    favoriteEntries: Set<String>,
    historyCount: Int,
    onCategorySelected: (MediaCategory) -> Unit,
    onToggleCategoryFavorite: (MediaCategory) -> Unit,
    onEntrySelected: (MediaEntry) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewKey by remember(catalog) { mutableStateOf<String?>(null) }
    var pendingFullscreen by remember { mutableStateOf<MediaEntry?>(null) }
    val previewHandle = remember { LivePreviewHandle() }

    LaunchedEffect(selectedCategoryId, entries) {
        if (entries.none { it.key == previewKey }) previewKey = entries.firstOrNull()?.key
    }

    LaunchedEffect(pendingFullscreen) {
        val target = pendingFullscreen ?: return@LaunchedEffect
        // Ferme immédiatement le socket du preview. Un très court délai laisse au serveur Xtream
        // le temps de libérer le slot de connexion avant que le Player plein écran se connecte.
        previewHandle.stop()
        delay(140)
        onEntrySelected(target)
    }

    val previewEntry = entries.firstOrNull { it.key == previewKey } ?: entries.firstOrNull()

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CategoryRail(
            type = MediaType.Live,
            categories = categories,
            selectedCategoryId = selectedCategoryId,
            favoriteCategories = favoriteCategories,
            countFor = { category ->
                when (category.id) {
                    FAVORITES_CATEGORY_ID -> favoriteEntries.count { it.startsWith("${MediaType.Live.name}:") }
                    HISTORY_CATEGORY_ID -> historyCount
                    else -> catalog.entriesIn(MediaType.Live, category.id).size
                }
            },
            onSelected = onCategorySelected,
            onToggleFavorite = onToggleCategoryFavorite,
            modifier = Modifier.width(250.dp).fillMaxHeight(),
        )

        LiveChannelList(
            entries = entries,
            previewKey = previewEntry?.key,
            favoriteEntries = favoriteEntries,
            onPreview = { previewKey = it.key },
            onOpen = { pendingFullscreen = it },
            onToggleFavorite = onToggleEntryFavorite,
            modifier = Modifier.width(340.dp).fillMaxHeight(),
        )

        LivePreview(
            credentials = credentials,
            entry = previewEntry,
            favorite = previewEntry?.key in favoriteEntries,
            handle = previewHandle,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun LiveChannelList(
    entries: List<MediaEntry>,
    previewKey: String?,
    favoriteEntries: Set<String>,
    onPreview: (MediaEntry) -> Unit,
    onOpen: (MediaEntry) -> Unit,
    onToggleFavorite: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 3.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Chaînes", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(entries.size.toString(), color = MutedInk, fontSize = 10.sp)
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().background(DeepSurface), contentAlignment = Alignment.Center) {
                Text("Aucune chaîne", color = MutedInk, fontSize = 14.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(entries, key = MediaEntry::key) { entry ->
                    FocusableSurface(
                        onClick = { onOpen(entry) },
                        onLongClick = { onToggleFavorite(entry) },
                        onFocused = { onPreview(entry) },
                        selected = previewKey == entry.key,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(entry.number.toString(), color = MutedInk, fontSize = 9.sp, modifier = Modifier.width(38.dp))
                            ChannelLogo(entry.iconUrl, entry.displayName, Modifier.size(31.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.displayName,
                                color = Ink,
                                fontSize = 12.sp,
                                fontWeight = if (previewKey == entry.key) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (entry.key in favoriteEntries) {
                                Spacer(Modifier.width(5.dp))
                                Text("★", color = FocusBlueBright, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun stopPreviewPlayer(player: ExoPlayer) {
    player.pause()
    player.stop()
    player.clearMediaItems()
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun LivePreview(
    credentials: ServerCredentials,
    entry: MediaEntry?,
    favorite: Boolean,
    handle: LivePreviewHandle,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Même pile réseau/buffer que le plein écran : cela évite deux comportements de lecture
    // différents et permet au pool HTTP d'être réutilisé après la fermeture du preview.
    val player = remember { StreamiaPlayerFactory.create(context.applicationContext, MediaType.Live) }
    var buffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var activeUrl by remember { mutableStateOf("") }
    var fallbackAttempted by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        handle.stop = { stopPreviewPlayer(player) }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(playbackException: PlaybackException) {
                if (!fallbackAttempted) {
                    val alternate = XtreamUrlBuilder.alternateTransportUrl(activeUrl)
                    if (alternate != null) {
                        fallbackAttempted = true
                        activeUrl = alternate
                        error = false
                        buffering = true
                        stopPreviewPlayer(player)
                        player.setMediaItem(MediaItem.fromUri(alternate))
                        player.prepare()
                        player.play()
                        return
                    }
                }
                error = true
                buffering = false
            }
        }
        player.addListener(listener)
        onDispose {
            handle.stop = {}
            player.removeListener(listener)
            stopPreviewPlayer(player)
            player.release()
        }
    }

    LaunchedEffect(entry?.key, credentials) {
        val target = entry
        if (target == null) {
            stopPreviewPlayer(player)
            return@LaunchedEffect
        }
        delay(240)
        error = false
        buffering = true
        fallbackAttempted = false
        activeUrl = XtreamUrlBuilder(credentials).stream(target)
        stopPreviewPlayer(player)
        player.setMediaItem(MediaItem.fromUri(activeUrl))
        player.prepare()
        player.play()
    }

    Column(modifier) {
        Text(
            "Aperçu en direct",
            color = Ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 3.dp, bottom = 7.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            if (entry != null) {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = false
                            keepScreenOn = true
                            this.player = player
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize(),
                )
                if (buffering) {
                    Text("Chargement…", color = Ink, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
                }
                if (error) {
                    Text("Aperçu indisponible", color = MutedInk, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
                }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Night.copy(alpha = 0.90f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        entry.displayName,
                        color = Ink,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "CH ${entry.number}${if (favorite) " · ★ Favori" else ""}  ·  OK plein écran · OK long favori",
                        color = FocusBlueBright,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            } else {
                Text("Sélectionnez une chaîne", color = MutedInk, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun VodCatalogLayout(
    type: MediaType,
    catalog: Catalog,
    categories: List<MediaCategory>,
    selectedCategoryId: String,
    entries: List<MediaEntry>,
    favoriteCategories: Set<String>,
    favoriteEntries: Set<String>,
    historyCount: Int,
    historyByKey: Map<String, PlaybackHistoryItem>,
    onCategorySelected: (MediaCategory) -> Unit,
    onToggleCategoryFavorite: (MediaCategory) -> Unit,
    onEntrySelected: (MediaEntry) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CategoryRail(
            type = type,
            categories = categories,
            selectedCategoryId = selectedCategoryId,
            favoriteCategories = favoriteCategories,
            countFor = { category ->
                when (category.id) {
                    FAVORITES_CATEGORY_ID -> favoriteEntries.count { it.startsWith("${type.name}:") }
                    HISTORY_CATEGORY_ID -> historyCount
                    else -> catalog.entriesIn(type, category.id).size
                }
            },
            onSelected = onCategorySelected,
            onToggleFavorite = onToggleCategoryFavorite,
            modifier = Modifier.width(250.dp).fillMaxHeight(),
        )

        PosterGrid(
            type = type,
            categoryName = categories.firstOrNull { it.id == selectedCategoryId }?.name.orEmpty(),
            entries = entries,
            favoriteEntries = favoriteEntries,
            historyByKey = historyByKey,
            onEntrySelected = onEntrySelected,
            onToggleFavorite = onToggleEntryFavorite,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
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
    val firstFocus = remember(type) { FocusRequester() }
    LaunchedEffect(type, categories.size) {
        if (categories.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 3.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Catégories", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("OK long = favori", color = MutedInk, fontSize = 9.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(categories, key = MediaCategory::key) { category ->
                val virtual = category.id in setOf(Catalog.ALL_CATEGORY_ID, FAVORITES_CATEGORY_ID, HISTORY_CATEGORY_ID)
                FocusableSurface(
                    onClick = { onSelected(category) },
                    onFocused = { onSelected(category) },
                    onLongClick = if (virtual) null else ({ onToggleFavorite(category) }),
                    selected = selectedCategoryId == category.id,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .then(if (category == categories.first()) Modifier.focusRequester(firstFocus) else Modifier),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!virtual && category.key in favoriteCategories) {
                            Text("★", color = FocusBlueBright, fontSize = 10.sp)
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            category.name,
                            color = Ink,
                            fontSize = 12.sp,
                            fontWeight = if (selectedCategoryId == category.id) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(countFor(category).toString(), color = MutedInk, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterGrid(
    type: MediaType,
    categoryName: String,
    entries: List<MediaEntry>,
    favoriteEntries: Set<String>,
    historyByKey: Map<String, PlaybackHistoryItem>,
    onEntrySelected: (MediaEntry) -> Unit,
    onToggleFavorite: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                categoryName.ifBlank { type.displayName },
                color = Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(10.dp))
            Text("${entries.size} ${type.pluralName}", color = MutedInk, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("OK ouvrir · OK long ajouter/retirer favori", color = MutedInk, fontSize = 9.sp)
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().background(DeepSurface), contentAlignment = Alignment.Center) {
                Text("Aucun contenu dans cette catégorie", color = MutedInk, fontSize = 16.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(155.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = MediaEntry::key) { entry ->
                    PosterCard(
                        entry = entry,
                        favorite = entry.key in favoriteEntries,
                        history = historyByKey[entry.key],
                        onClick = { onEntrySelected(entry) },
                        onLongClick = { onToggleFavorite(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterCard(
    entry: MediaEntry,
    favorite: Boolean,
    history: PlaybackHistoryItem?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    FocusableSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        selected = favorite,
        modifier = Modifier.fillMaxWidth().height(245.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            MediaArtwork(entry.iconUrl, entry.displayName, Modifier.fillMaxWidth().height(175.dp))
            Spacer(Modifier.height(7.dp))
            Text(
                entry.displayName,
                color = Ink,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val detail = entry.rating?.let { "★ ${"%.1f".format(it)}" }
                    ?: if (entry.type == MediaType.Series && entry.playable) "Épisode" else entry.type.displayName
                Text(detail, color = FocusBlueBright, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.weight(1f))
                if (history != null && history.progress > 0.02f) {
                    Text("${history.progressPercent()}%", color = MutedInk, fontSize = 9.sp)
                } else if (favorite) {
                    Text("★", color = FocusBlueBright, fontSize = 10.sp)
                }
            }
        }
    }
}

private fun defaultCategoryId(catalog: Catalog, type: MediaType): String =
    catalog.categoriesFor(type).firstOrNull { catalog.entriesIn(type, it.id).isNotEmpty() }?.id
        ?: Catalog.ALL_CATEGORY_ID

private fun PlaybackHistoryItem.progressPercent(): Int = (progress * 100).toInt().coerceIn(0, 100)

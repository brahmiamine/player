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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import fr.streamia.tv.data.PlaybackHistoryItem
import fr.streamia.tv.data.BrowserNavigationStore
import fr.streamia.tv.data.NavigationListPosition
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.XtreamUrlBuilder
import fr.streamia.tv.player.LivePlaybackSession
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.WarmSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.yield

private const val FAVORITES_CATEGORY_ID = "__favorites__"
private const val HISTORY_CATEGORY_ID = "__history__"

@Composable
fun BrowserScreen(
    catalog: Catalog,
    credentials: ServerCredentials,
    livePlaybackSession: LivePlaybackSession,
    liveVideoSurface: @Composable (LiveVideoSurfacePlacement) -> Unit,
    library: UserLibrarySnapshot,
    offline: Boolean,
    busy: Boolean,
    message: String?,
    initialType: MediaType? = null,
    initialCategoryId: String? = null,
    onEntrySelected: (MediaEntry) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    onToggleCategoryFavorite: (MediaCategory) -> Unit,
    onRememberContent: (MediaEntry) -> Unit,
    onLocationChanged: (MediaType, String?) -> Unit,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onSettings: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    fun leaveBrowserForHome() {
        livePlaybackSession.stop(clearSession = true)
        onHome()
    }
    BackHandler(onBack = ::leaveBrowserForHome)
    val context = LocalContext.current.applicationContext
    val navigationStore = remember(credentials) { BrowserNavigationStore(context, credentials) }
    val restoredLiveSelection = remember(catalog, credentials) {
        val stored = navigationStore.liveSelection()
        val returnedEntryKey = LiveBrowserReturnState.consume()
        val entryKey = returnedEntryKey ?: stored?.entryKey
        val categoryId = when {
            entryKey == null -> stored?.categoryId
            returnedEntryKey == null || returnedEntryKey == stored?.entryKey -> stored?.categoryId
            else -> catalog.entry(entryKey)?.categoryId
        }
        categoryId to entryKey
    }

    val defaultType = initialType
        ?.takeIf { catalog.count(it) > 0 }
        ?: MediaType.entries.firstOrNull { catalog.count(it) > 0 }
        ?: MediaType.Live
    // initialType/initialCategoryId ne servent qu'à l'entrée dans l'écran. Les inclure dans
    // les clés recréait l'état après onLocationChanged et annulait le clic suivant.
    var selectedType by remember(catalog, credentials) { mutableStateOf(defaultType) }
    var lastLiveEntryKey by remember(catalog, credentials) { mutableStateOf(restoredLiveSelection.second) }
    var selectedCategoryId by remember(catalog, credentials) {
        mutableStateOf(
            if (defaultType == MediaType.Live) {
                restoredLiveSelection.first ?: initialCategoryId ?: defaultCategoryId(catalog, MediaType.Live)
            } else {
                initialCategoryId ?: navigationStore.category(defaultType) ?: defaultCategoryId(catalog, defaultType)
            },
        )
    }

    val baseCategories = remember(catalog, selectedType) { catalog.categoriesFor(selectedType) }
    val favoriteEntriesForType = remember(catalog, selectedType, library.favoriteEntries) {
        library.favoriteEntries.asSequence()
            .mapNotNull(catalog::entry)
            .filter { it.type == selectedType }
            .toList()
    }
    val historyForType = remember(catalog, selectedType, library.history) {
        library.history.asSequence()
            .map { item -> item to (catalog.entry(item.entry.key) ?: item.entry) }
            .filter { (_, entry) -> entry.type == selectedType }
            .toList()
    }
    val categories = remember(baseCategories, favoriteEntriesForType.size, historyForType.size, selectedType) {
        buildBrowserCategories(
            type = selectedType,
            providerCategories = baseCategories,
            favoriteCategoryKeys = library.favoriteCategories,
            hasFavoriteEntries = favoriteEntriesForType.isNotEmpty(),
            hasHistory = historyForType.isNotEmpty(),
        )
    }
    val entries = remember(catalog, selectedType, selectedCategoryId, favoriteEntriesForType, historyForType) {
        when (selectedCategoryId) {
            FAVORITES_CATEGORY_ID -> favoriteEntriesForType
            HISTORY_CATEGORY_ID -> historyForType.map { it.second }
            else -> catalog.entriesIn(selectedType, selectedCategoryId)
        }
    }
    val historyByKey = remember(historyForType) { historyForType.associate { it.second.key to it.first } }

    androidx.compose.runtime.LaunchedEffect(selectedType, categories) {
        if (categories.none { it.id == selectedCategoryId }) {
            selectedCategoryId = defaultCategoryId(catalog, selectedType)
        }
    }
    androidx.compose.runtime.LaunchedEffect(selectedType, selectedCategoryId) {
        onLocationChanged(selectedType, selectedCategoryId)
        if (selectedType != MediaType.Live) {
            navigationStore.saveCategory(selectedType, selectedCategoryId)
        }
    }

    Column(Modifier.fillMaxSize().background(Night)) {
        BrowserHeader(
            catalog = catalog,
            selectedType = selectedType,
            offline = offline,
            busy = busy,
            onHome = ::leaveBrowserForHome,
            onTypeSelected = {
                if (it != MediaType.Live) livePlaybackSession.stop(clearSession = true)
                selectedType = it
                selectedCategoryId = navigationStore.category(it) ?: defaultCategoryId(catalog, it)
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
                    color = if (message.contains("média", ignoreCase = true) || message.contains("import", ignoreCase = true)) FocusBlueBright else MaterialTheme.colorScheme.error,
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
                livePlaybackSession = livePlaybackSession,
                liveVideoSurface = liveVideoSurface,
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                entries = entries,
                initialPreviewKey = lastLiveEntryKey,
                initialListPosition = navigationStore.listPosition(MediaType.Live, selectedCategoryId),
                favoriteCategories = library.favoriteCategories,
                favoriteEntries = library.favoriteEntries,
                historyCount = historyForType.size,
                onCategorySelected = { selectedCategoryId = it.id },
                onPreviewChanged = {
                    lastLiveEntryKey = it.key
                    navigationStore.saveLiveSelection(selectedCategoryId, it.key)
                    onRememberContent(it)
                },
                onListPositionChanged = {
                    navigationStore.saveListPosition(MediaType.Live, selectedCategoryId, it)
                },
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
                onEntryFocused = { navigationStore.saveEntry(selectedType, it.key) },
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
private fun HeaderAction(label: String, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit, enabled: Boolean = true) {
    FocusableSurface(onClick = onClick, enabled = enabled, modifier = Modifier.width(width).height(44.dp)) {
        Text(label, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

@Composable
private fun LiveCatalogLayout(
    catalog: Catalog,
    credentials: ServerCredentials,
    livePlaybackSession: LivePlaybackSession,
    liveVideoSurface: @Composable (LiveVideoSurfacePlacement) -> Unit,
    categories: List<MediaCategory>,
    selectedCategoryId: String,
    entries: List<MediaEntry>,
    initialPreviewKey: String?,
    initialListPosition: NavigationListPosition,
    favoriteCategories: Set<String>,
    favoriteEntries: Set<String>,
    historyCount: Int,
    onCategorySelected: (MediaCategory) -> Unit,
    onPreviewChanged: (MediaEntry) -> Unit,
    onListPositionChanged: (NavigationListPosition) -> Unit,
    onToggleCategoryFavorite: (MediaCategory) -> Unit,
    onEntrySelected: (MediaEntry) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewEntry by remember(catalog, initialPreviewKey) {
        mutableStateOf(entries.firstOrNull { it.key == initialPreviewKey } ?: entries.firstOrNull())
    }
    var controlsVisible by remember { mutableStateOf(false) }
    var fullscreenTarget by remember { mutableStateOf<MediaEntry?>(null) }
    var focusCategoryAfterChange by remember { mutableStateOf(false) }
    var initialChannelFocusPending by remember { mutableStateOf(true) }
    val categoryFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    val hiddenOffset = with(LocalDensity.current) { (-620).dp.toPx() }
    val controlsOffset by animateFloatAsState(
        if (controlsVisible) 0f else hiddenOffset,
        animationSpec = tween(220),
        label = "live-controls-offset",
    )
    val controlsAlpha by animateFloatAsState(
        if (controlsVisible) 1f else 0f,
        animationSpec = tween(160),
        label = "live-controls-alpha",
    )

    LaunchedEffect(Unit) {
        yield()
        controlsVisible = true
    }
    LaunchedEffect(previewEntry?.key) { previewEntry?.let(onPreviewChanged) }
    LaunchedEffect(fullscreenTarget) {
        val target = fullscreenTarget ?: return@LaunchedEffect
        controlsVisible = false
        delay(220)
        onEntrySelected(target)
    }
    LaunchedEffect(selectedCategoryId, focusCategoryAfterChange) {
        if (focusCategoryAfterChange) {
            yield()
            runCatching { categoryFocus.requestFocus() }
            focusCategoryAfterChange = false
        }
    }
    if (previewEntry != null && catalog.entry(previewEntry!!.key) == null) {
        previewEntry = entries.firstOrNull()
    }

    Box(modifier) {
        LivePreview(
            credentials = credentials,
            livePlaybackSession = livePlaybackSession,
            liveVideoSurface = liveVideoSurface,
            entry = previewEntry,
            favorite = previewEntry?.key in favoriteEntries,
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            Modifier.fillMaxHeight().graphicsLayer {
                translationX = controlsOffset
                alpha = controlsAlpha
            },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
            requestInitialFocus = false,
            selectedFocusRequester = categoryFocus,
            onRight = { runCatching { channelFocus.requestFocus() } },
            modifier = Modifier.width(250.dp).fillMaxHeight(),
        )

        key(selectedCategoryId) {
            LiveChannelList(
                entries = entries,
                previewKey = previewEntry?.key,
                favoriteEntries = favoriteEntries,
                fullscreenPending = fullscreenTarget != null,
                initialListPosition = initialListPosition,
                selectedFocusRequester = channelFocus,
                autoFocus = initialChannelFocusPending,
                onAutoFocusConsumed = { initialChannelFocusPending = false },
                onLeft = { channel ->
                    val exactCategory = categories.firstOrNull { it.id == channel.categoryId }
                    if (exactCategory != null && exactCategory.id != selectedCategoryId) {
                        focusCategoryAfterChange = true
                        onCategorySelected(exactCategory)
                    } else {
                        runCatching { categoryFocus.requestFocus() }
                    }
                },
                onListPositionChanged = onListPositionChanged,
                onConfirm = { channel ->
                    when (
                        liveChannelConfirmAction(
                            previewKey = previewEntry?.key,
                            channelKey = channel.key,
                            fullscreenPending = fullscreenTarget != null,
                        )
                    ) {
                        LiveChannelConfirmAction.Preview -> previewEntry = channel
                        LiveChannelConfirmAction.Fullscreen -> fullscreenTarget = channel
                        LiveChannelConfirmAction.Ignore -> Unit
                    }
                },
                onToggleFavorite = onToggleEntryFavorite,
                modifier = Modifier.width(340.dp).fillMaxHeight(),
            )
        }
        }
    }
}

@Composable
private fun LiveChannelList(
    entries: List<MediaEntry>,
    previewKey: String?,
    favoriteEntries: Set<String>,
    fullscreenPending: Boolean,
    initialListPosition: NavigationListPosition,
    selectedFocusRequester: FocusRequester,
    autoFocus: Boolean,
    onAutoFocusConsumed: () -> Unit,
    onLeft: (MediaEntry) -> Unit,
    onListPositionChanged: (NavigationListPosition) -> Unit,
    onConfirm: (MediaEntry) -> Unit,
    onToggleFavorite: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastIndex = entries.lastIndex.coerceAtLeast(0)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialListPosition.index.coerceIn(0, lastIndex),
        initialFirstVisibleItemScrollOffset = initialListPosition.offset.coerceAtLeast(0),
    )
    val channelFocus = selectedFocusRequester
    val entryIndexByKey = remember(entries) { entries.withIndex().associate { (index, entry) -> entry.key to index } }
    val previewIndex = previewKey?.let { entryIndexByKey[it] } ?: -1
    val focusTargetIndex = previewIndex.takeIf { it >= 0 }
        ?: listState.firstVisibleItemIndex.coerceIn(0, lastIndex)
    val focusTargetKey = entries.getOrNull(focusTargetIndex)?.key

    LaunchedEffect(listState) {
        snapshotFlow {
            NavigationListPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.debounce(300).distinctUntilChanged().collect(onListPositionChanged)
    }

    androidx.compose.runtime.LaunchedEffect(entries, focusTargetKey, fullscreenPending, autoFocus) {
        if (fullscreenPending) return@LaunchedEffect
        val index = focusTargetIndex
        if (index >= 0) {
            yield()
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                listState.scrollToItem(index)
                yield()
            }
            if (autoFocus) {
                runCatching { channelFocus.requestFocus() }
                onAutoFocusConsumed()
            }
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 3.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chaînes", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("OK aperçu · OK encore plein écran", color = MutedInk, fontSize = 9.sp)
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().background(DeepSurface), contentAlignment = Alignment.Center) {
                Text("Aucune chaîne", color = MutedInk, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(entries, key = MediaEntry::key) { entry ->
                    FocusableSurface(
                        onClick = { onConfirm(entry) },
                        onLongClick = { onToggleFavorite(entry) },
                        selected = previewKey == entry.key,
                        enabled = !fullscreenPending,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .onPreviewKeyEvent { event ->
                                if (
                                    event.type == KeyEventType.KeyDown &&
                                    event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                                ) {
                                    onLeft(entry)
                                    true
                                } else false
                            }
                            .then(if (focusTargetKey == entry.key) Modifier.focusRequester(channelFocus) else Modifier),
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

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun LivePreview(
    credentials: ServerCredentials,
    livePlaybackSession: LivePlaybackSession,
    liveVideoSurface: @Composable (LiveVideoSurfacePlacement) -> Unit,
    entry: MediaEntry?,
    favorite: Boolean,
    modifier: Modifier = Modifier,
) {
    val player = livePlaybackSession.player
    var buffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var activeUrl by remember { mutableStateOf("") }
    var fallbackAttempted by remember { mutableStateOf(false) }

    DisposableEffect(player, entry?.key) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (entry?.key != livePlaybackSession.entryKey) return
                buffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onRenderedFirstFrame() {
                if (entry?.key == livePlaybackSession.entryKey) {
                    buffering = false
                    error = false
                }
            }

            override fun onPlayerError(playbackException: PlaybackException) {
                if (entry?.key != livePlaybackSession.entryKey) return
                if (!fallbackAttempted) {
                    val alternate = XtreamUrlBuilder.alternateTransportUrl(activeUrl)
                    if (alternate != null) {
                        fallbackAttempted = true
                        activeUrl = alternate
                        error = false
                        buffering = true
                        entry?.let { livePlaybackSession.playUrl(it.key, alternate) }
                        return
                    }
                }
                error = true
                buffering = false
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                livePlaybackSession.recoverAudio(tracks)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    androidx.compose.runtime.LaunchedEffect(entry?.key, credentials) {
        val target = entry
        if (target == null) return@LaunchedEffect
        delay(240)
        error = false
        buffering = true
        fallbackAttempted = false
        livePlaybackSession.play(target, credentials)
        activeUrl = livePlaybackSession.activeUrl
    }

    LaunchedEffect(entry?.key, buffering) {
        if (!buffering) return@LaunchedEffect
        delay(12_000)
        if (player.isPlaying || player.playbackState == Player.STATE_READY) buffering = false
    }

    Box(modifier.background(Color.Black)) {
            if (entry != null) {
                liveVideoSurface(LiveVideoSurfacePlacement(Modifier.fillMaxSize()))
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
                        .background(Night.copy(alpha = 0.72f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(entry.displayName, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "CH ${entry.number}${if (favorite) " · ★ Favori" else ""} · OK encore = plein écran · OK long = favori",
                        color = FocusBlueBright,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            } else {
                Text("Sélectionnez une chaîne puis appuyez sur OK", color = MutedInk, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
            }
        Text(
            "Aperçu en direct",
            color = Ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(Night.copy(alpha = 0.82f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
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
    onEntryFocused: (MediaEntry) -> Unit,
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
            onEntryFocused = onEntryFocused,
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
    requestInitialFocus: Boolean = true,
    selectedFocusRequester: FocusRequester? = null,
    onRight: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val internalSelectedFocus = remember(selectedCategoryId, type) { FocusRequester() }
    val selectedFocus = selectedFocusRequester ?: internalSelectedFocus

    androidx.compose.runtime.LaunchedEffect(type, categories.size, selectedCategoryId, requestInitialFocus) {
        val index = categories.indexOfFirst { it.id == selectedCategoryId }
        if (index >= 0) {
            yield()
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                listState.scrollToItem(index)
                yield()
            }
            if (requestInitialFocus) {
                runCatching { selectedFocus.requestFocus() }
            }
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 3.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Catégories", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("OK choisir · OK long favori", color = MutedInk, fontSize = 9.sp)
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(categories, key = MediaCategory::key) { category ->
                val virtual = category.id in setOf(Catalog.ALL_CATEGORY_ID, FAVORITES_CATEGORY_ID, HISTORY_CATEGORY_ID)
                FocusableSurface(
                    onClick = { onSelected(category) },
                    onLongClick = if (virtual) null else ({ onToggleFavorite(category) }),
                    selected = selectedCategoryId == category.id,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .onPreviewKeyEvent { event ->
                            if (
                                onRight != null &&
                                event.type == KeyEventType.KeyDown &&
                                event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                            ) {
                                onRight()
                                true
                            } else false
                        }
                        .then(if (category.id == selectedCategoryId) Modifier.focusRequester(selectedFocus) else Modifier),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
    onEntryFocused: (MediaEntry) -> Unit,
    onToggleFavorite: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(categoryName.ifBlank { type.displayName }, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        onFocused = { onEntryFocused(entry) },
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
    onFocused: () -> Unit,
    onLongClick: () -> Unit,
) {
    FocusableSurface(
        onClick = onClick,
        onFocused = onFocused,
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

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
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.LiveChannelSortOrder
import fr.streamia.tv.data.LiveStreamFormat
import fr.streamia.tv.data.PlaybackHistoryItem
import fr.streamia.tv.data.VodSortOrder
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
import fr.streamia.tv.player.PlaybackTransportStore
import fr.streamia.tv.player.PlaybackUrlStrategy
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.TypeBody
import fr.streamia.tv.ui.theme.TypeSectionTitle
import fr.streamia.tv.ui.theme.WarmSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.yield

private const val FAVORITES_CATEGORY_ID = "__favorites__"
private const val HISTORY_CATEGORY_ID = "__history__"

/** Distance (en éléments) à la fin de la liste/grille matérialisée à partir de laquelle la page suivante est demandée. */
private const val LOAD_MORE_THRESHOLD = 20

/**
 * Hauteur du bandeau haut, partagée entre [BrowserHeader] (qui l'utilise comme hauteur réelle) et
 * [LiveCatalogLayout] (qui décale ses panneaux catégories/chaînes de cette même valeur quand le
 * bandeau flotte en transparence par-dessus la vidéo plein écran, pour ne pas se faire recouvrir).
 */
private val BROWSER_HEADER_HEIGHT = 74.dp

@Composable
fun BrowserScreen(
    catalog: Catalog,
    credentials: ServerCredentials,
    livePlaybackSession: LivePlaybackSession,
    liveVideoSurface: @Composable (LiveVideoSurfacePlacement) -> Unit,
    library: UserLibrarySnapshot,
    appSettings: AppSettings,
    parentalUnlocked: Boolean,
    offline: Boolean,
    busy: Boolean,
    message: String?,
    initialType: MediaType? = null,
    initialCategoryId: String? = null,
    onEntrySelected: (MediaEntry) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    onToggleCategoryFavorite: (MediaCategory) -> Unit,
    onVerifyParentalPin: (String) -> Boolean,
    onRememberContent: (MediaEntry) -> Unit,
    onLocationChanged: (MediaType, String?) -> Unit,
    onEnsureCategoryLoaded: (MediaType, String) -> Unit,
    onLoadMoreInCategory: (MediaType, String) -> Unit,
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

    val hiddenCategoryIds = remember(catalog, selectedType, library.hiddenCategories) {
        catalog.categoriesFor(selectedType)
            .filter { it.key in library.hiddenCategories }
            .mapTo(mutableSetOf(), MediaCategory::id)
    }
    // Une catégorie verrouillée reste visible dans le rail (l'utilisateur doit voir qu'elle
    // existe pour pouvoir la déverrouiller) : seul son contenu disparaît des vues agrégées
    // (Tout, favoris, historique) tant que le code n'a pas été saisi cette session.
    val lockedCategoryIds = remember(catalog, selectedType, library.lockedCategories, appSettings.parentalControlEnabled, parentalUnlocked) {
        if (!appSettings.parentalControlEnabled || parentalUnlocked) emptySet()
        else catalog.categoriesFor(selectedType)
            .filter { it.key in library.lockedCategories }
            .mapTo(mutableSetOf(), MediaCategory::id)
    }
    val excludedCategoryIds = remember(hiddenCategoryIds, lockedCategoryIds) { hiddenCategoryIds + lockedCategoryIds }
    val baseCategories = remember(catalog, selectedType, library.hiddenCategories) {
        catalog.categoriesFor(selectedType).filterNot { it.key in library.hiddenCategories }
    }
    val favoriteEntriesForType = remember(catalog, selectedType, library.favoriteEntries, library.hiddenEntries, excludedCategoryIds) {
        library.favoriteEntries.asSequence()
            .mapNotNull(catalog::entry)
            .filter {
                it.type == selectedType &&
                    it.key !in library.hiddenEntries &&
                    it.categoryId !in excludedCategoryIds
            }
            .toList()
    }
    val historyForType = remember(catalog, selectedType, library.history, library.hiddenEntries, excludedCategoryIds) {
        library.history.asSequence()
            .map { item -> item to (catalog.entry(item.entry.key) ?: item.entry) }
            .filter { (_, entry) ->
                entry.type == selectedType &&
                    entry.key !in library.hiddenEntries &&
                    entry.categoryId !in excludedCategoryIds
            }
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
    val entries = remember(
        catalog, selectedType, selectedCategoryId, favoriteEntriesForType, historyForType,
        excludedCategoryIds, library.hiddenEntries, appSettings.liveChannelSortOrder, appSettings.vodSortOrder,
    ) {
        when (selectedCategoryId) {
            FAVORITES_CATEGORY_ID -> favoriteEntriesForType
            HISTORY_CATEGORY_ID -> historyForType.map { it.second }
            else -> {
                val filtered = catalog.entriesIn(selectedType, selectedCategoryId).filterNot {
                    it.key in library.hiddenEntries || it.categoryId in excludedCategoryIds
                }
                // « Favoris »/« Historique » gardent leur propre ordre (ajout / dernière lecture),
                // qui perdrait son sens sous un tri alphabétique ou par numéro : seule une vraie
                // catégorie suit la préférence de tri de son type.
                when (selectedType) {
                    MediaType.Live -> sortedForLiveDisplay(filtered, appSettings.liveChannelSortOrder)
                    else -> sortedForVodDisplay(filtered, appSettings.vodSortOrder)
                }
            }
        }
    }
    val historyByKey = remember(historyForType) { historyForType.associate { it.second.key to it.first } }

    var pendingLockedCategory by remember { mutableStateOf<MediaCategory?>(null) }
    fun selectCategory(category: MediaCategory) {
        val locked = appSettings.parentalControlEnabled && !parentalUnlocked && category.key in library.lockedCategories
        if (locked) pendingLockedCategory = category else selectedCategoryId = category.id
    }

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
        if (selectedCategoryId != FAVORITES_CATEGORY_ID && selectedCategoryId != HISTORY_CATEGORY_ID) {
            onEnsureCategoryLoaded(selectedType, selectedCategoryId)
        }
    }

    val isLive = selectedType == MediaType.Live

    // En Direct, la vidéo doit remplir tout l'écran, bandeau du haut compris : LiveCatalogLayout
    // est donc posé en premier (plein écran) dans ce Box, et le bandeau + le message flottent
    // ensuite par-dessus, translucides, plutôt que de réserver leur propre bande opaque en haut
    // comme le fait la disposition Column classique utilisée par les autres écrans (VOD compris).
    Box(Modifier.fillMaxSize().background(Night)) {
        if (isLive) {
            LiveCatalogLayout(
                catalog = catalog,
                credentials = credentials,
                livePlaybackSession = livePlaybackSession,
                liveVideoSurface = liveVideoSurface,
                appSettings = appSettings,
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                entries = entries,
                initialPreviewKey = lastLiveEntryKey,
                initialListPosition = navigationStore.listPosition(MediaType.Live, selectedCategoryId),
                favoriteCategories = library.favoriteCategories,
                favoriteEntries = library.favoriteEntries,
                lockedCategories = library.lockedCategories,
                historyCount = historyForType.size,
                onCategorySelected = ::selectCategory,
                onPreviewChanged = {
                    lastLiveEntryKey = it.key
                    navigationStore.saveLiveSelection(selectedCategoryId, it.key)
                    onRememberContent(it)
                },
                onListPositionChanged = { categoryId, position ->
                    navigationStore.saveListPosition(MediaType.Live, categoryId, position)
                },
                onToggleCategoryFavorite = onToggleCategoryFavorite,
                onEntrySelected = onEntrySelected,
                onToggleEntryFavorite = onToggleEntryFavorite,
                onLoadMore = { onLoadMoreInCategory(MediaType.Live, selectedCategoryId) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(Modifier.fillMaxSize()) {
            BrowserHeader(
                catalog = catalog,
                selectedType = selectedType,
                offline = offline,
                busy = busy,
                translucent = isLive,
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
                        "$message  ·  OK pour fermer",
                        color = if (message.contains("média", ignoreCase = true) || message.contains("import", ignoreCase = true)) FocusBlueBright else MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!isLive) {
                VodCatalogLayout(
                    type = selectedType,
                    catalog = catalog,
                    categories = categories,
                    selectedCategoryId = selectedCategoryId,
                    entries = entries,
                    favoriteCategories = library.favoriteCategories,
                    favoriteEntries = library.favoriteEntries,
                    lockedCategories = library.lockedCategories,
                    historyCount = historyForType.size,
                    historyByKey = historyByKey,
                    onCategorySelected = ::selectCategory,
                    onToggleCategoryFavorite = onToggleCategoryFavorite,
                    onEntrySelected = onEntrySelected,
                    onEntryFocused = { navigationStore.saveEntry(selectedType, it.key) },
                    onToggleEntryFavorite = onToggleEntryFavorite,
                    onLoadMore = { onLoadMoreInCategory(selectedType, selectedCategoryId) },
                    modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                )
            }
        }

        pendingLockedCategory?.let { category ->
            ParentalPinDialog(
                title = "Contenu verrouillé",
                subtitle = "Entrez le code parental pour accéder à « ${category.name} »",
                onSubmit = { pin ->
                    val correct = onVerifyParentalPin(pin)
                    if (correct) {
                        selectedCategoryId = category.id
                        pendingLockedCategory = null
                    }
                    correct
                },
                onCancel = { pendingLockedCategory = null },
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
    // Le Direct affiche ce bandeau flottant par-dessus la vidéo plein écran plutôt que dans sa
    // propre bande opaque : il porte alors son propre fond assombri (même valeur que les panneaux
    // catégories/chaînes) et ses boutons au repos deviennent transparents. VOD garde le bandeau
    // opaque habituel, posé sur le fond plein de son écran.
    translucent: Boolean = false,
    onHome: () -> Unit,
    onTypeSelected: (MediaType) -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onSettings: () -> Unit,
) {
    val idleBackground = if (translucent) Color.Transparent else DeepSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BROWSER_HEADER_HEIGHT)
            .then(if (translucent) Modifier.background(Night.copy(alpha = 0.72f)) else Modifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreamiaLogo(compact = true)
        Spacer(Modifier.width(16.dp))
        HeaderAction("Accueil", 90.dp, onHome, idleBackground = idleBackground)
        Spacer(Modifier.width(6.dp))

        for (type in MediaType.entries) {
            FocusableSurface(
                onClick = { onTypeSelected(type) },
                selected = selectedType == type,
                enabled = catalog.count(type) > 0,
                idleBackground = idleBackground,
                modifier = Modifier.width(104.dp).height(44.dp),
            ) {
                Column(Modifier.padding(horizontal = 10.dp)) {
                    Text(type.displayName, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(catalog.count(type).toString(), color = MutedInk, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.width(6.dp))
        }

        Spacer(Modifier.weight(1f))
        HeaderAction("Recherche", 52.dp, onSearch, glyph = StreamiaIconGlyph.Search, idleBackground = idleBackground)
        Spacer(Modifier.width(6.dp))
        HeaderAction("EPG", 68.dp, onEpg, catalog.count(MediaType.Live) > 0, idleBackground = idleBackground)
        Spacer(Modifier.width(6.dp))
        HeaderAction("Paramètres", 52.dp, onSettings, glyph = StreamiaIconGlyph.Settings, idleBackground = idleBackground)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(6.dp).background(if (offline) WarmSignal else Color(0xFF8FBFA0)))
        Spacer(Modifier.width(5.dp))
        Text(
            when {
                busy -> "Chargement…"
                offline -> "Cache"
                else -> "Local / en ligne"
            },
            color = if (offline) WarmSignal else MutedInk,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun HeaderAction(
    label: String,
    width: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
    glyph: StreamiaIconGlyph? = null,
    idleBackground: Color = DeepSurface,
) {
    FocusableSurface(
        onClick = onClick,
        enabled = enabled,
        idleBackground = idleBackground,
        modifier = Modifier.width(width).height(44.dp),
        contentDescription = if (glyph != null) label else null,
    ) {
        if (glyph != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StreamiaIcon(glyph, size = 22.dp) }
        } else {
            Text(label, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
        }
    }
}

@Composable
private fun LiveCatalogLayout(
    catalog: Catalog,
    credentials: ServerCredentials,
    livePlaybackSession: LivePlaybackSession,
    liveVideoSurface: @Composable (LiveVideoSurfacePlacement) -> Unit,
    appSettings: AppSettings,
    categories: List<MediaCategory>,
    selectedCategoryId: String,
    entries: List<MediaEntry>,
    initialPreviewKey: String?,
    initialListPosition: NavigationListPosition,
    favoriteCategories: Set<String>,
    favoriteEntries: Set<String>,
    lockedCategories: Set<String>,
    historyCount: Int,
    onCategorySelected: (MediaCategory) -> Unit,
    onPreviewChanged: (MediaEntry) -> Unit,
    onListPositionChanged: (String, NavigationListPosition) -> Unit,
    onToggleCategoryFavorite: (MediaCategory) -> Unit,
    onEntrySelected: (MediaEntry) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    onLoadMore: () -> Unit,
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
            enabled = appSettings.livePreviewEnabled,
            previewDelayMs = appSettings.livePreviewDelayMs,
            liveStreamFormat = appSettings.liveStreamFormat,
            // Bord à bord, y compris sous le bandeau du haut (qui flotte par-dessus, translucide) :
            // seuls les panneaux catégories/chaînes ci-dessous en tiennent compte, via leur propre
            // padding, pour ne pas se faire recouvrir par ce bandeau.
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            Modifier
                .fillMaxHeight()
                .padding(start = 18.dp, end = 18.dp, top = BROWSER_HEADER_HEIGHT + 8.dp, bottom = 18.dp)
                .graphicsLayer {
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
            lockedCategories = lockedCategories,
            countFor = { category ->
                when (category.id) {
                    FAVORITES_CATEGORY_ID -> favoriteEntries.count { it.startsWith("${MediaType.Live.name}:") }
                    HISTORY_CATEGORY_ID -> historyCount
                    else -> catalog.countIn(MediaType.Live, category.id)
                }
            },
            onSelected = onCategorySelected,
            onToggleFavorite = onToggleCategoryFavorite,
            requestInitialFocus = false,
            selectedFocusRequester = categoryFocus,
            onRight = { runCatching { channelFocus.requestFocus() } },
            translucent = true,
            modifier = Modifier.width(250.dp).fillMaxHeight(),
        )

        key(selectedCategoryId) {
            // Fige la catégorie associée à cette instance de LiveChannelList : le flush de
            // position en fin de debounce peut s'exécuter après que selectedCategoryId ait déjà
            // changé (catégorie suivante sélectionnée pendant la fenêtre de 300 ms), et lire l'état
            // mutable à ce moment-là sauverait la position de l'ancienne catégorie sous la nouvelle.
            val categoryIdForPosition = selectedCategoryId
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
                onListPositionChanged = { onListPositionChanged(categoryIdForPosition, it) },
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
                onLoadMore = onLoadMore,
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
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastIndex = entries.lastIndex.coerceAtLeast(0)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialListPosition.index.coerceIn(0, lastIndex),
        initialFirstVisibleItemScrollOffset = initialListPosition.offset.coerceAtLeast(0),
    )

    LaunchedEffect(listState, entries.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible -> if (lastVisible >= entries.size - LOAD_MORE_THRESHOLD) onLoadMore() }
    }
    val channelFocus = selectedFocusRequester
    val entryIndexByKey = remember(entries) { entries.withIndex().associate { (index, entry) -> entry.key to index } }
    val previewIndex = previewKey?.let { entryIndexByKey[it] } ?: -1
    val focusTargetIndex = previewIndex.takeIf { it >= 0 }
        ?: listState.firstVisibleItemIndex.coerceIn(0, lastIndex)
    val focusTargetKey = entries.getOrNull(focusTargetIndex)?.key

    LaunchedEffect(listState) {
        // Le debounce évite d'écrire en préférences à chaque frame pendant un défilement rapide,
        // mais ne doit jamais faire perdre la position atteinte si l'écran est quitté avant la fin
        // de la fenêtre de 300 ms : on garde la dernière valeur brute non encore sauvegardée et on
        // la vide explicitement si la coroutine est annulée pendant qu'elle est en attente.
        var pendingPosition: NavigationListPosition? = null
        try {
            snapshotFlow {
                NavigationListPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }
                .onEach { pendingPosition = it }
                .debounce(300)
                .distinctUntilChanged()
                .collect { pendingPosition = null; onListPositionChanged(it) }
        } finally {
            pendingPosition?.let(onListPositionChanged)
        }
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

    Column(modifier.background(Night.copy(alpha = 0.72f))) {
        Row(Modifier.fillMaxWidth().padding(start = 3.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Chaînes")
            Spacer(Modifier.weight(1f))
            Text("OK aperçu · OK encore plein écran", color = MutedInk, fontSize = 12.sp)
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune chaîne", color = MutedInk, fontSize = TypeBody)
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
                        idleBackground = Color.Transparent,
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
                            Text(entry.number.toString(), color = MutedInk, fontSize = 11.sp, modifier = Modifier.width(38.dp))
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
                                StreamiaIcon(StreamiaIconGlyph.Star, size = 12.dp)
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
    enabled: Boolean,
    previewDelayMs: Int,
    liveStreamFormat: LiveStreamFormat,
    modifier: Modifier = Modifier,
) {
    val player = livePlaybackSession.player
    val context = LocalContext.current.applicationContext
    val transportStore = remember { PlaybackTransportStore(context) }
    var buffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var activeUrl by remember { mutableStateOf("") }
    var streamCandidates by remember { mutableStateOf(emptyList<String>()) }
    var candidateIndex by remember { mutableStateOf(0) }

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
                    transportStore.recordSuccess(activeUrl, MediaType.Live)
                }
            }

            override fun onPlayerError(playbackException: PlaybackException) {
                if (entry?.key != livePlaybackSession.entryKey) return
                val next = candidateIndex + 1
                if (next < streamCandidates.size) {
                    candidateIndex = next
                    activeUrl = streamCandidates[next]
                    error = false
                    buffering = true
                    entry?.let { livePlaybackSession.playUrl(it.key, activeUrl) }
                    return
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

    androidx.compose.runtime.LaunchedEffect(entry?.key, credentials, enabled, previewDelayMs, liveStreamFormat) {
        val target = entry
        if (!enabled || target == null) {
            livePlaybackSession.stop(clearSession = true)
            buffering = false
            error = false
            return@LaunchedEffect
        }
        if (previewDelayMs > 0) delay(previewDelayMs.toLong())
        error = false
        buffering = true
        val baseUrl = XtreamUrlBuilder(credentials).stream(target)
        val storedPreference = transportStore.preferenceFor(baseUrl)
        val preferredExtension = when (liveStreamFormat) {
            LiveStreamFormat.Auto -> storedPreference.liveExtension
            LiveStreamFormat.Ts -> "ts"
            LiveStreamFormat.Hls -> "m3u8"
        }
        streamCandidates = PlaybackUrlStrategy.candidates(
            initialUrl = baseUrl,
            type = MediaType.Live,
            preference = storedPreference.copy(liveExtension = preferredExtension),
        )
        candidateIndex = 0
        activeUrl = streamCandidates.firstOrNull() ?: baseUrl
        if (
            shouldRestartLivePreview(
                currentEntryKey = livePlaybackSession.entryKey,
                currentMediaItemCount = player.mediaItemCount,
                targetEntryKey = target.key,
            )
        ) {
            livePlaybackSession.playUrl(target.key, activeUrl)
        } else {
            activeUrl = livePlaybackSession.activeUrl
            buffering = player.playbackState != Player.STATE_READY
            player.play()
        }
    }

    LaunchedEffect(entry?.key, buffering) {
        if (!buffering) return@LaunchedEffect
        delay(12_000)
        if (player.isPlaying || player.playbackState == Player.STATE_READY) buffering = false
    }

    Box(modifier.background(Color.Black)) {
            if (entry != null && enabled) {
                liveVideoSurface(LiveVideoSurfacePlacement(Modifier.fillMaxSize()))
                if (buffering) {
                    Text("Chargement…", color = Ink, fontSize = TypeBody, modifier = Modifier.align(Alignment.Center))
                }
                if (error) {
                    Text("Aperçu indisponible", color = MutedInk, fontSize = TypeBody, modifier = Modifier.align(Alignment.Center))
                }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Night.copy(alpha = 0.72f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(entry.displayName, color = Ink, fontSize = TypeBody, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "CH ${entry.number}${if (favorite) " · ★ Favori" else ""} · OK encore = plein écran · OK long = favori",
                        color = FocusBlueBright,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    if (entry != null && !enabled) "Aperçu désactivé dans les paramètres" else "Sélectionnez une chaîne puis appuyez sur OK",
                    color = MutedInk,
                    fontSize = TypeBody,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        Text(
            if (enabled) "Aperçu en direct" else "Aperçu désactivé",
            color = Ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            // Décalé sous le bandeau du haut, qui flotte maintenant par-dessus cette même vidéo
            // plein écran (voir BrowserScreen) et couvrirait sinon ce badge.
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = BROWSER_HEADER_HEIGHT)
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
    lockedCategories: Set<String>,
    historyCount: Int,
    historyByKey: Map<String, PlaybackHistoryItem>,
    onCategorySelected: (MediaCategory) -> Unit,
    onToggleCategoryFavorite: (MediaCategory) -> Unit,
    onEntrySelected: (MediaEntry) -> Unit,
    onEntryFocused: (MediaEntry) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            modifier = Modifier.width(250.dp).fillMaxHeight(),
        )

        PosterGrid(
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
            onEntrySelected = onEntrySelected,
            onEntryFocused = onEntryFocused,
            onToggleFavorite = onToggleEntryFavorite,
            onLoadMore = onLoadMore,
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
    lockedCategories: Set<String>,
    countFor: (MediaCategory) -> Int,
    onSelected: (MediaCategory) -> Unit,
    onToggleFavorite: (MediaCategory) -> Unit,
    requestInitialFocus: Boolean = true,
    selectedFocusRequester: FocusRequester? = null,
    onRight: (() -> Unit)? = null,
    // Le Direct affiche ce panneau par-dessus la vidéo plein écran déjà en cours de lecture : les
    // lignes au repos passent en transparent (le fond assombri du Column suffit à garder le texte
    // lisible) plutôt que de masquer la vidéo derrière un aplat opaque. VOD (l'autre appelant) garde
    // le panneau opaque par défaut, puisqu'il n'y a rien à voir derrière.
    translucent: Boolean = false,
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

    Column(modifier.then(if (translucent) Modifier.background(Night.copy(alpha = 0.72f)) else Modifier)) {
        Row(Modifier.fillMaxWidth().padding(start = 3.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Catégories")
            Spacer(Modifier.weight(1f))
            Text("OK choisir · OK long favori", color = MutedInk, fontSize = 12.sp)
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
                    idleBackground = if (translucent) Color.Transparent else DeepSurface,
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
                            StreamiaIcon(StreamiaIconGlyph.Star, size = 12.dp)
                            Spacer(Modifier.width(5.dp))
                        }
                        if (!virtual && category.key in lockedCategories) {
                            StreamiaIcon(StreamiaIconGlyph.Lock, tint = FocusBlueBright, size = 12.dp)
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
                        Text(countFor(category).toString(), color = MutedInk, fontSize = 11.sp)
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
    totalCount: Int,
    entries: List<MediaEntry>,
    favoriteEntries: Set<String>,
    historyByKey: Map<String, PlaybackHistoryItem>,
    onEntrySelected: (MediaEntry) -> Unit,
    onEntryFocused: (MediaEntry) -> Unit,
    onToggleFavorite: (MediaEntry) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LaunchedEffect(gridState, entries.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible -> if (lastVisible >= entries.size - LOAD_MORE_THRESHOLD) onLoadMore() }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(categoryName.ifBlank { type.displayName }, color = Ink, fontSize = TypeSectionTitle, fontWeight = HeadingWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(10.dp))
            Text("$totalCount ${type.pluralName}", color = MutedInk, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("OK ouvrir · OK long ajouter/retirer favori", color = MutedInk, fontSize = 12.sp)
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().background(DeepSurface), contentAlignment = Alignment.Center) {
                Text("Aucun contenu dans cette catégorie", color = MutedInk, fontSize = TypeBody)
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
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
        modifier = Modifier.fillMaxWidth().height(252.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Box(Modifier.fillMaxWidth().height(175.dp)) {
                MediaArtwork(entry.iconUrl, entry.displayName, Modifier.fillMaxSize())
                if (history != null && history.progress > 0.02f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.45f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(history.progress).background(FocusBlueBright))
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                entry.displayName,
                color = Ink,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val ratingText = entry.rating?.let { "%.1f".format(it) }
                if (ratingText != null) {
                    StreamiaIcon(StreamiaIconGlyph.Star, size = 11.dp)
                    Spacer(Modifier.width(3.dp))
                    Text(ratingText, color = FocusBlueBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                } else {
                    val label = if (entry.type == MediaType.Series && entry.playable) "Épisode" else entry.type.displayName
                    Text(label, color = FocusBlueBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                if (history != null && history.progress > 0.02f) {
                    Text("${history.progressPercent()}%", color = MutedInk, fontSize = 12.sp)
                } else if (favorite) {
                    StreamiaIcon(StreamiaIconGlyph.Star, size = 12.dp)
                }
            }
        }
    }
}

private fun defaultCategoryId(catalog: Catalog, type: MediaType): String =
    catalog.categoriesFor(type).firstOrNull { catalog.countIn(type, it.id) > 0 }?.id
        ?: Catalog.ALL_CATEGORY_ID

private fun PlaybackHistoryItem.progressPercent(): Int = (progress * 100).toInt().coerceIn(0, 100)

/**
 * Ordre d'affichage des chaînes Direct au sein d'une catégorie, choisi dans Paramètres. `Provider`
 * conserve l'ordre déjà renvoyé par le fournisseur/SQLite (aucun tri, coût nul) ; `Alphabetical`
 * réutilise `Collator` (comme le tri de catégories dans l'Organizer) pour rester correct avec les
 * accents français plutôt que trier par point de code Unicode.
 */
internal fun sortedForLiveDisplay(entries: List<MediaEntry>, order: LiveChannelSortOrder): List<MediaEntry> = when (order) {
    LiveChannelSortOrder.Provider -> entries
    LiveChannelSortOrder.Number -> entries.sortedBy(MediaEntry::number)
    LiveChannelSortOrder.Alphabetical -> {
        val collator = java.text.Collator.getInstance(java.util.Locale.FRENCH)
        entries.sortedWith(Comparator { a, b -> collator.compare(a.displayName, b.displayName) })
    }
}

/**
 * `RecentlyAdded`/`Rating` retombent en fin de liste pour une entrée sans date d'ajout / note (le
 * fournisseur ne les fournit pas toujours) plutôt que de les faire remonter en tête par accident :
 * `sortedByDescending` traite `null` comme la plus petite valeur, donc toujours en dernier ici.
 * Pas d'option « année » : cette donnée vient de [fr.streamia.tv.domain.MediaDetails], récupérée
 * à la demande pour un seul contenu, jamais en bloc pour toute une catégorie du catalogue léger.
 */
internal fun sortedForVodDisplay(entries: List<MediaEntry>, order: VodSortOrder): List<MediaEntry> = when (order) {
    VodSortOrder.Provider -> entries
    VodSortOrder.Alphabetical -> {
        val collator = java.text.Collator.getInstance(java.util.Locale.FRENCH)
        entries.sortedWith(Comparator { a, b -> collator.compare(a.displayName, b.displayName) })
    }
    VodSortOrder.RecentlyAdded -> entries.sortedByDescending(MediaEntry::addedAtEpochSeconds)
    VodSortOrder.Rating -> entries.sortedByDescending(MediaEntry::rating)
}

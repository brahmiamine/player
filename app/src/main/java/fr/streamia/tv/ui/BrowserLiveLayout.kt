package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import fr.streamia.tv.player.isDecoderError
import fr.streamia.tv.player.MAX_STREAM_RECOVERY_ATTEMPTS
import fr.streamia.tv.player.StreamRecovery
import fr.streamia.tv.player.isRecoverableStreamError
import fr.streamia.tv.player.streamRecoveryDelayMs
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.LiveStreamFormat
import fr.streamia.tv.data.NavigationListPosition
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.epgNowContextAt
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.XtreamUrlBuilder
import fr.streamia.tv.player.LivePlaybackSession
import fr.streamia.tv.player.PlaybackTransportStore
import fr.streamia.tv.player.PlaybackUrlStrategy
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RadiusCard
import fr.streamia.tv.ui.theme.TypeBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.yield

// Disposition du Direct : liste des chaînes et aperçu vidéo.

@Composable
internal fun LiveCatalogLayout(
    catalog: Catalog,
    epgPrograms: Map<String, List<EpgProgram>>,
    credentials: ServerCredentials,
    livePlaybackSession: LivePlaybackSession,
    liveVideoSurface: @Composable (LiveVideoSurfacePlacement) -> Unit,
    appSettings: AppSettings,
    categories: List<MediaCategory>,
    selectedCategoryId: String,
    entries: List<MediaEntry>,
    entriesPending: Boolean,
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
    onLivePreviewWatched: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewEntry by remember(catalog, initialPreviewKey) {
        mutableStateOf(entries.firstOrNull { it.key == initialPreviewKey } ?: entries.firstOrNull())
    }
    var controlsVisible by remember { mutableStateOf(false) }
    var fullscreenTarget by remember { mutableStateOf<MediaEntry?>(null) }
    var channelsFocused by remember { mutableStateOf(false) }
    var initialChannelFocusPending by remember { mutableStateOf(true) }
    // Chaque incrément demande au rail de ramener le focus sur la catégorie sélectionnée, en la
    // refaisant défiler à l'écran si l'utilisateur a descendu la liste des catégories entre-temps.
    var categoryFocusRequest by remember { mutableIntStateOf(0) }
    // Même principe dans l'autre sens : Droite depuis les catégories ramène le focus sur la chaîne
    // sélectionnée, en refaisant défiler la liste des chaînes si elle a été descendue.
    var channelFocusRequest by remember { mutableIntStateOf(0) }
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
    // Retour depuis la liste des chaînes : remonte d'abord au rail des catégories (convention TV),
    // un second Retour quitte vers l'accueil.
    BackHandler(enabled = channelsFocused) { categoryFocusRequest++ }
    if (previewEntry != null && catalog.entry(previewEntry!!.key) == null) {
        previewEntry = entries.firstOrNull()
    }
    // Liste triée hors du thread principal (grande catégorie) : l'aperçu part dès qu'elle arrive.
    if (previewEntry == null && !entriesPending && entries.isNotEmpty()) {
        previewEntry = entries.firstOrNull { it.key == initialPreviewKey } ?: entries.firstOrNull()
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
            onWatched = onLivePreviewWatched,
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
            focusSelectedRequest = categoryFocusRequest,
            onRight = { channelFocusRequest++ },
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
                entriesPending = entriesPending,
                epgPrograms = epgPrograms,
                previewKey = previewEntry?.key,
                favoriteEntries = favoriteEntries,
                fullscreenPending = fullscreenTarget != null,
                initialListPosition = initialListPosition,
                selectedFocusRequester = channelFocus,
                focusSelectedRequest = channelFocusRequest,
                autoFocus = initialChannelFocusPending,
                onAutoFocusConsumed = { initialChannelFocusPending = false },
                // Gauche : retour au rail sur la catégorie parcourue, sans basculer vers la catégorie
                // d'origine de la chaîne (depuis Favoris/Tout/Historique, la liste était perdue).
                onLeft = { categoryFocusRequest++ },
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
                modifier = Modifier.width(340.dp).fillMaxHeight().onFocusChanged { channelsFocused = it.hasFocus },
            )
        }
        }
    }
}

@Composable
private fun LiveChannelList(
    entries: List<MediaEntry>,
    entriesPending: Boolean,
    epgPrograms: Map<String, List<EpgProgram>>,
    previewKey: String?,
    favoriteEntries: Set<String>,
    fullscreenPending: Boolean,
    initialListPosition: NavigationListPosition,
    selectedFocusRequester: FocusRequester,
    // Incrémenté par l'appelant (Droite depuis les catégories) pour ramener le focus sur la chaîne
    // sélectionnée.
    focusSelectedRequest: Int,
    autoFocus: Boolean,
    onAutoFocusConsumed: () -> Unit,
    onLeft: (MediaEntry) -> Unit,
    onListPositionChanged: (NavigationListPosition) -> Unit,
    onConfirm: (MediaEntry) -> Unit,
    onToggleFavorite: (MediaEntry) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Heure de référence du programme en cours, rafraîchie chaque minute (une seule horloge pour
    // toute la liste, pas une par ligne).
    var nowEpochSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(epgPrograms.isNotEmpty()) {
        if (epgPrograms.isEmpty()) return@LaunchedEffect
        while (true) {
            nowEpochSeconds = System.currentTimeMillis() / 1000
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
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
    val prefetchContext = LocalContext.current.applicationContext
    // Logos des prochaines chaînes préchargés pendant le défilement (collectLatest : un nouveau
    // défilement abandonne le préchargement devenu inutile).
    LaunchedEffect(listState, entries) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collectLatest { lastVisible ->
                if (lastVisible < 0) return@collectLatest
                val upcoming = entries.subList((lastVisible + 1).coerceAtMost(entries.size), (lastVisible + 1 + ARTWORK_PREFETCH_COUNT).coerceAtMost(entries.size))
                prefetchArtwork(prefetchContext, upcoming.map(MediaEntry::iconUrl), logo = true)
            }
    }
    val channelFocus = selectedFocusRequester
    // Un seul parcours pour retrouver la chaîne en aperçu, au lieu d'un index de toute la liste
    // (des dizaines de milliers de chaînes) reconstruit sur le thread principal à chaque liste.
    // Colonne des numéros assez large pour le plus long de la liste : à largeur fixe (38 dp), un
    // numéro à 5 chiffres (17055) passait sur deux lignes.
    val numberColumnWidth = remember(entries) { channelNumberColumnWidth(entries.maxOfOrNull { it.number } ?: 0) }
    val previewIndex = remember(entries, previewKey) { previewKey?.let { key -> entries.indexOfFirst { it.key == key } } ?: -1 }
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

    // Retour depuis les catégories : si la liste a été défilée, la chaîne sélectionnée n'est plus
    // composée et requestFocus() échouait en silence (impossible de revenir sur les chaînes). On la
    // refait d'abord défiler à l'écran. La liste étant recréée à chaque changement de catégorie, la
    // valeur reçue à la création n'est pas une demande (sinon elle volerait le focus au rail).
    val initialFocusRequest = remember { focusSelectedRequest }
    LaunchedEffect(focusSelectedRequest) {
        if (focusSelectedRequest == initialFocusRequest) return@LaunchedEffect
        val index = focusTargetIndex
        if (index < 0 || entries.isEmpty()) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            // Deux chaînes au-dessus restent visibles pour garder le contexte.
            listState.scrollToItem((index - 2).coerceAtLeast(0))
        }
        yield()
        val focused = runCatching { channelFocus.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
        if (!focused) {
            withFrameNanos { }
            runCatching { channelFocus.requestFocus(FocusDirection.Enter) }
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

    Column(
        modifier
            .clip(RoundedCornerShape(RadiusCard))
            .background(Night.copy(alpha = 0.72f))
            .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(RadiusCard))
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 3.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Chaînes")
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (entriesPending) "Chargement…" else "Aucune chaîne", color = MutedInk, fontSize = TypeBody)
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
                            .height(66.dp)
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
                            Text(
                                entry.number.toString(),
                                color = MutedInk,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.width(numberColumnWidth),
                            )
                            ChannelLogo(entry.iconUrl, entry.displayName, Modifier.size(42.dp))
                            Spacer(Modifier.width(8.dp))
                            val nowProgram = remember(epgPrograms, entry.key, nowEpochSeconds) {
                                epgPrograms[entry.key]?.epgNowContextAt(nowEpochSeconds)?.current
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.displayName,
                                    color = Ink,
                                    fontSize = 15.sp,
                                    fontWeight = if (previewKey == entry.key) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (nowProgram != null) {
                                    Text(
                                        nowProgram.title,
                                        color = MutedInk,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    liveProgramProgress(nowProgram, nowEpochSeconds)?.let { progress ->
                                        Spacer(Modifier.height(3.dp))
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(3.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(MutedInk.copy(alpha = 0.24f)),
                                        ) {
                                            Box(Modifier.fillMaxWidth(progress).height(3.dp).background(FocusBlueBright))
                                        }
                                    }
                                }
                            }
                            if (entry.key in favoriteEntries) {
                                Spacer(Modifier.width(5.dp))
                                StreamiaIcon(StreamiaIconGlyph.Star, size = 18.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val LIVE_PREVIEW_WATCHED_MS = 20_000L

/** ≈ 8 dp par chiffre à 13 sp (marge comprise pour l'agrandissement du texte), jamais moins que l'ancienne colonne. */
internal fun channelNumberColumnWidth(maxNumber: Int): Dp =
    maxOf(38, maxNumber.coerceAtLeast(0).toString().length * 8 + 6).dp

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
    onWatched: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = livePlaybackSession.player
    val context = LocalContext.current.applicationContext
    val transportStore = remember { PlaybackTransportStore(context) }
    var buffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var unsupportedFormat by remember { mutableStateOf(false) }
    var activeUrl by remember { mutableStateOf("") }
    var streamCandidates by remember { mutableStateOf(emptyList<String>()) }
    var candidateIndex by remember { mutableStateOf(0) }
    // Aperçu déjà affiché : une coupure réseau ensuite relance la même URL (voir PlayerScreen).
    var streamHasPlayed by remember(entry?.key) { mutableStateOf(false) }
    var recoveryAttempt by remember(entry?.key) { mutableIntStateOf(0) }
    var pendingRecovery by remember(entry?.key) { mutableStateOf<StreamRecovery?>(null) }

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
                    streamHasPlayed = true
                    recoveryAttempt = 0
                    transportStore.recordSuccess(activeUrl, MediaType.Live)
                }
            }

            override fun onPlayerError(playbackException: PlaybackException) {
                if (entry?.key != livePlaybackSession.entryKey) return
                // Format non décodable par le boîtier : inutile d'essayer les autres URL.
                if (isDecoderError(playbackException.errorCode)) {
                    unsupportedFormat = true
                    error = true
                    buffering = false
                    return
                }
                if (streamHasPlayed && isRecoverableStreamError(playbackException.errorCode) && recoveryAttempt < MAX_STREAM_RECOVERY_ATTEMPTS) {
                    recoveryAttempt += 1
                    buffering = true
                    pendingRecovery = StreamRecovery(activeUrl, 0L, recoveryAttempt)
                    return
                }
                val next = candidateIndex + 1
                if (next < streamCandidates.size) {
                    candidateIndex = next
                    activeUrl = streamCandidates[next]
                    error = false
                    buffering = true
                    entry?.let { livePlaybackSession.playUrl(it, activeUrl) }
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
        unsupportedFormat = false
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
            livePlaybackSession.playUrl(target, activeUrl)
        } else {
            activeUrl = livePlaybackSession.activeUrl
            streamCandidates = prioritizeActiveLiveCandidate(streamCandidates, activeUrl)
            candidateIndex = 0
            buffering = player.playbackState != Player.STATE_READY
            livePlaybackSession.continuePlayback()
        }
        // Chaîne restée à l'écran en aperçu : comptée comme regardée (« Dernières chaînes » de
        // l'accueil). Le délai évite d'enregistrer chaque chaîne survolée en zappant.
        delay(LIVE_PREVIEW_WATCHED_MS)
        onWatched(target)
    }

    // Aperçu tombé en erreur pendant une coupure : relancé dès le retour du réseau.
    val networkReconnections = LocalNetworkReconnections.current
    LaunchedEffect(networkReconnections) {
        if (!error || unsupportedFormat || !enabled || entry == null || activeUrl.isBlank()) return@LaunchedEffect
        error = false
        buffering = true
        recoveryAttempt = 0
        livePlaybackSession.playUrl(entry, activeUrl)
    }

    LaunchedEffect(pendingRecovery) {
        val recovery = pendingRecovery ?: return@LaunchedEffect
        delay(streamRecoveryDelayMs(recovery.attempt))
        if (enabled && entry != null && livePlaybackSession.entryKey == entry.key) livePlaybackSession.playUrl(entry, recovery.url)
        pendingRecovery = null
    }

    LaunchedEffect(entry?.key, buffering) {
        if (!buffering) return@LaunchedEffect
        delay(12_000)
        if (player.isPlaying || player.playbackState == Player.STATE_READY) buffering = false
    }

    // Aperçu affiché : la vidéo est posée sous l'écran par StreamiaApp, un fond ici la masquerait.
    val showingVideo = entry != null && enabled
    Box(if (showingVideo) modifier else modifier.background(Color.Black)) {
            if (showingVideo) {
                liveVideoSurface(LiveVideoSurfacePlacement(Modifier.fillMaxSize()))
                if (buffering) {
                    Text("Chargement…", color = Ink, fontSize = TypeBody, modifier = Modifier.align(Alignment.Center))
                }
                if (error) {
                    Text(if (unsupportedFormat) "Format non supporté par ce boîtier" else "Aperçu indisponible", color = MutedInk, fontSize = TypeBody, modifier = Modifier.align(Alignment.Center))
                }
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Night.copy(alpha = 0.72f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelLogo(entry.iconUrl, entry.displayName, Modifier.size(64.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        entry.displayName + if (favorite) " ★" else "",
                        color = Ink,
                        fontSize = TypeBody,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
    }
}

/** Avancement (0..1) d'un programme à l'instant donné, `null` sans horaires exploitables. */
internal fun liveProgramProgress(program: EpgProgram, nowEpochSeconds: Long): Float? {
    val start = program.startEpochSeconds ?: return null
    val end = program.endEpochSeconds ?: return null
    if (end <= start) return null
    return ((nowEpochSeconds - start).toFloat() / (end - start)).coerceIn(0f, 1f)
}

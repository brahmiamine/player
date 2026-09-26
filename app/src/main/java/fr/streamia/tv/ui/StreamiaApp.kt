package fr.streamia.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import fr.streamia.tv.ui.theme.FocusBlueBright
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.movableContentOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import fr.streamia.tv.BuildConfig
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.StreamiaTheme
import fr.streamia.tv.player.LivePlaybackSession
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.recommendation.RecommendedMedia
import fr.streamia.tv.recommendation.RecommendationRowKind
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.HomeBlock
import fr.streamia.tv.data.isResumable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView

@Composable
fun StreamiaApp(viewModel: StreamiaViewModel, livePlaybackSession: LivePlaybackSession) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val liveVideoSurface = remember(livePlaybackSession) {
        movableContentOf<LiveVideoSurfacePlacement> { placement ->
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        keepScreenOn = true
                        player = livePlaybackSession.player
                    }
                },
                update = { view ->
                    view.player = livePlaybackSession.player
                    view.resizeMode = placement.resizeMode
                    view.subtitleView?.applySubtitleStyle(state.appSettings.subtitleSizeScale, state.appSettings.subtitleBackgroundEnabled)
                },
                modifier = placement.modifier,
            )
        }
    }

    // Seuls les masquages comptent : clé sur la bibliothèque entière, le filtrage (sur le thread
    // principal) était refait à chaque sauvegarde de progression ou chaîne ajoutée à l'historique.
    val liveOnSatMatches = remember(
        state.liveOnSatMatches, state.catalog, state.library.hiddenEntries, state.library.hiddenCategories,
        state.library.lockedCategories, state.appSettings.parentalControlEnabled, state.parentalUnlocked,
    ) {
        state.liveOnSatMatches.withoutHiddenChannels(state.catalog, state.library, state.appSettings.parentalControlEnabled && !state.parentalUnlocked)
    }

    // Mêmes instances tant que leurs sources ne changent pas : recréées à chaque émission de l'état,
    // elles faisaient recomposer tout l'accueil (et relancer sa restauration de défilement) à chaque
    // chargement de guide, de page ou d'historique, même sans aucun changement pour l'accueil.
    val homeRecommendationRows = remember(state.homeRecommendationRows, state.homeJustWatchRows, state.appSettings.disabledHomeBlocks) {
        (state.homeRecommendationRows + state.homeJustWatchRows).filter { it.kind.homeBlock !in state.appSettings.disabledHomeBlocks }
    }
    val homePendingBlocks = remember(state.homePendingBlocks, state.appSettings.disabledHomeBlocks) {
        state.homePendingBlocks - state.appSettings.disabledHomeBlocks
    }

    StreamiaTheme {
        ResponsiveTvViewport {
            Box(Modifier.fillMaxSize()) {
              // Lecteur : fond noir plein écran sous la vidéo, les dégradés y seraient dessinés pour rien
              // à chaque rafraîchissement du HUD. Ailleurs, la liste ne change qu'avec le type d'écran.
              val screenKind = state.screen::class
              if (state.screen !is StreamiaScreen.Player) {
                  val blobs = remember(screenKind) { glassBlobsFor(state.screen) }
                  GlassBackdrop(blobs)
              }
              when {
                // Texte selon ce qui charge réellement : l'ancien « Ouverture de votre dernière
                // lecture… » s'affichait aussi à l'ouverture d'une liste, sans aucune reprise.
                shouldShowStartupGate(state) -> BootScreen(if (state.booting) "Démarrage…" else "Chargement de votre liste…")

                state.screen is StreamiaScreen.Login -> LoginScreen(
                    profiles = state.profiles,
                    busy = state.busy,
                    testingConnection = state.testingConnection,
                    testSucceeded = state.testSucceeded,
                    message = state.message,
                    onOpenProfile = viewModel::openProfile,
                    onSignIn = viewModel::signIn,
                    onTestConnection = viewModel::testConnection,
                    onImportM3u = viewModel::importM3u,
                    onImportM3uUrl = viewModel::importM3uUrl,
                    onSaveM3uSettings = viewModel::saveM3uSettings,
                    onRenameProfile = viewModel::renameProfile,
                    onDeleteProfile = viewModel::deleteProfile,
                    onDismissMessage = viewModel::dismissMessage,
                    onReturnToList = if (state.returnProfileId != null) viewModel::returnToPreviousList else null,
                )

                state.screen is StreamiaScreen.Home && state.catalog != null -> HomeScreen(
                    catalog = state.catalog!!,
                    weatherPlace = state.weatherPlace,
                    weather = state.weather,
                    prayerMethod = state.appSettings.prayerMethod,
                    offline = state.offline,
                    busy = state.busy,
                    library = state.library,
                    parentalControlEnabled = state.appSettings.parentalControlEnabled,
                    parentalUnlocked = state.parentalUnlocked,
                    catalogLoading = state.catalogHydrating,
                    resumeRowEnabled = HomeBlock.Resume !in state.appSettings.disabledHomeBlocks,
                    favoritesRowEnabled = HomeBlock.Favorites !in state.appSettings.disabledHomeBlocks,
                    footballScoresEnabled = HomeBlock.FootballScores !in state.appSettings.disabledHomeBlocks,
                    recentChannelsEnabled = HomeBlock.RecentChannels !in state.appSettings.disabledHomeBlocks,
                    recommendationRows = homeRecommendationRows,
                    tvProgrammeNow = state.homeTvProgrammeNow.ifDisabled(HomeBlock.TvProgrammeNow, state.appSettings),
                    tvProgrammeTonight = state.homeTvProgrammeTonight.ifDisabled(HomeBlock.TvProgrammeTonight, state.appSettings),
                    beinSportsNow = state.homeBeinSportsNow.ifDisabled(HomeBlock.BeinSportsNow, state.appSettings),
                    beinSportsNext = state.homeBeinSportsNext.ifDisabled(HomeBlock.BeinSportsNext, state.appSettings),
                    ukGuideNow = state.homeUkGuideNow.ifDisabled(HomeBlock.UkGuideNow, state.appSettings),
                    ukGuideNext = state.homeUkGuideNext.ifDisabled(HomeBlock.UkGuideNext, state.appSettings),
                    liveMatches = liveOnSatMatches.ifDisabled(HomeBlock.LiveMatches, state.appSettings),
                    pendingBlocks = homePendingBlocks,
                    liveMatchesPending = state.liveOnSatPending && HomeBlock.LiveMatches !in state.appSettings.disabledHomeBlocks,
                    liveMatchesResolving = state.liveOnSatResolving,
                    restoreContext = state.contentReturnContext,
                    focusTarget = state.homeFocusTarget,
                    onFocusConsumed = viewModel::consumeHomeFocusTarget,
                    onRestoreConsumed = viewModel::consumeHomeRestore,
                    onOpenSection = viewModel::openSection,
                    onSettings = viewModel::showSettings,
                    onSearch = viewModel::showSearch,
                    onEpg = viewModel::showEpg,
                    onRefresh = viewModel::refresh,
                    onChangePlaylist = viewModel::logout,
                    onResumePlayback = viewModel::resumeHomePlayback,
                    onOpenHomeEntry = viewModel::openHomeEntry,
                    onOpenLiveMatches = viewModel::showLiveMatches,
                    onRefreshTvProgrammeNow = viewModel::refreshTvProgrammeNow,
                    onRefreshBeinSportsGuide = viewModel::refreshBeinSportsGuide,
                    onRefreshUkGuide = viewModel::refreshUkGuide,
                    onRefreshLiveMatches = viewModel::refreshLiveOnSatIfStale,
                    onRefreshWeather = viewModel::refreshWeatherIfStale,
                )

                state.screen is StreamiaScreen.Browser && state.catalog != null && state.credentials != null -> BrowserScreen(
                    catalog = state.catalog!!,
                    credentials = state.credentials!!,
                    livePlaybackSession = livePlaybackSession,
                    liveVideoSurface = liveVideoSurface,
                    todayEpgGuide = state.todayEpgGuide,
                    library = state.library,
                    appSettings = state.appSettings,
                    loadingCategoryKeys = state.loadingCategoryKeys,
                    vodPageKeys = state.vodPageKeys,
                    categoryLoadErrors = state.categoryLoadErrors,
                    parentalUnlocked = state.parentalUnlocked,
                    offline = state.offline,
                    busy = state.busy,
                    message = state.message,
                    initialType = state.browserType,
                    initialCategoryId = state.browserCategoryId,
                    restoreEntryKey = state.contentReturnContext
                        ?.takeIf { it.origin == ContentReturnOrigin.Browser }
                        ?.itemKey,
                    onRestoreConsumed = viewModel::consumeBrowserRestore,
                    onEntrySelected = viewModel::openEntry,
                    onToggleEntryFavorite = viewModel::toggleEntryFavorite,
                    onToggleCategoryFavorite = viewModel::toggleCategoryFavorite,
                    onVerifyParentalPin = viewModel::verifyParentalPin,
                    onRememberContent = viewModel::rememberLastContent,
                    onLivePreviewWatched = { entry -> viewModel.recordPlayback(entry, 0L, 0L) },
                    onLocationChanged = viewModel::rememberBrowserLocation,
                    onEnsureCategoryLoaded = viewModel::ensureCategoryLoaded,
                    onLoadMoreInCategory = viewModel::loadMoreInCategory,
                    onLiveEntrySelected = viewModel::openLiveFromList,
                    onHome = viewModel::showHome,
                    onSearch = {
                        livePlaybackSession.stop(clearSession = true)
                        viewModel.showSearch()
                    },
                    onEpg = {
                        livePlaybackSession.stop(clearSession = true)
                        viewModel.showEpg()
                    },
                    onSettings = {
                        livePlaybackSession.stop(clearSession = true)
                        viewModel.showSettings()
                    },
                    onDismissMessage = viewModel::dismissMessage,
                )

                state.screen is StreamiaScreen.Settings -> SettingsScreen(
                    settings = state.appSettings,
                    playlistName = state.profiles.firstOrNull { it.id == state.activeProfileId }?.name,
                    accountExpiresAtEpochSeconds = state.catalog?.account?.expiresAtEpochSeconds,
                    detectedPlaceName = state.weatherPlace?.name,
                    busy = state.busy,
                    liveHistoryCount = state.library.history.count { it.entry.type == MediaType.Live },
                    movieHistoryCount = state.library.history.count { it.entry.type == MediaType.Movie },
                    seriesHistoryCount = state.library.history.count { it.entry.type == MediaType.Series },
                    currentVersion = BuildConfig.VERSION_NAME,
                    updateChecking = state.updateChecking,
                    updateCheck = state.updateCheck,
                    onToggleLivePreview = viewModel::toggleLivePreview,
                    onCycleLivePreviewDelay = viewModel::cycleLivePreviewDelay,
                    onCycleVodSeekStep = viewModel::cycleVodSeekStep,
                    onCycleVideoAspect = viewModel::cycleVideoAspect,
                    onCycleBufferMode = viewModel::cycleBufferMode,
                    onCycleDisplayModeSwitch = viewModel::cycleDisplayModeSwitch,
                    onToggleTunneling = viewModel::toggleTunneling,
                    onCycleLiveStreamFormat = viewModel::cycleLiveStreamFormat,
                    onCycleLiveChannelSortOrder = viewModel::cycleLiveChannelSortOrder,
                    onCycleVodSortOrder = viewModel::cycleVodSortOrder,
                    onCycleEpgTimeOffset = viewModel::cycleEpgTimeOffset,
                    onToggleAutoPlayNextEpisode = viewModel::toggleAutoPlayNextEpisode,
                    onCycleSubtitleSizeScale = viewModel::cycleSubtitleSizeScale,
                    onToggleSubtitleBackground = viewModel::toggleSubtitleBackground,
                    onToggleHomeBlock = viewModel::toggleHomeBlock,
                    onSearch = viewModel::showSearch,
                    onEpg = viewModel::showEpg,
                    onOrganizer = viewModel::showOrganizer,
                    onRefresh = viewModel::refresh,
                    onClearLiveHistory = { viewModel.clearHistory(MediaType.Live) },
                    onClearMovieHistory = { viewModel.clearHistory(MediaType.Movie) },
                    onClearSeriesHistory = { viewModel.clearHistory(MediaType.Series) },
                    onClearAllHistory = { viewModel.clearHistory() },
                    onChangePlaylist = viewModel::logout,
                    onCheckForUpdate = viewModel::checkForUpdate,
                    onDismissUpdateCheck = viewModel::dismissUpdateCheck,
                    onInstallUpdate = viewModel::installPendingUpdate,
                    onAllowUpdateInstall = viewModel::openUpdateInstallPermission,
                    onExportBackup = viewModel::exportBackup,
                    onImportBackup = viewModel::importBackup,
                    onAbout = viewModel::showAbout,
                    onParentalControl = viewModel::showParentalControl,
                    onSearchCities = viewModel::searchCities,
                    onSetHomePlace = viewModel::setHomePlace,
                    onSetPrayerMethod = viewModel::setPrayerMethod,
                    onBack = viewModel::backFromMenu,
                )

                state.screen is StreamiaScreen.ParentalControl -> ParentalControlScreen(
                    enabled = state.appSettings.parentalControlEnabled,
                    onSetPin = viewModel::setParentalPin,
                    onVerifyPin = viewModel::verifyParentalPin,
                    onDisable = viewModel::disableParentalControl,
                    onBack = viewModel::backFromMenu,
                )

                state.screen is StreamiaScreen.About -> AboutScreen(
                    versionName = BuildConfig.VERSION_NAME,
                    onLoadCacheSize = viewModel::cacheSizeBytes,
                    onLoadEpgCacheSize = viewModel::epgCacheSizeBytes,
                    onBack = viewModel::backFromMenu,
                )

                state.screen is StreamiaScreen.Search && state.catalog != null -> SearchScreen(
                    favoriteEntries = state.library.favoriteEntries,
                    query = state.searchQuery,
                    type = state.searchType,
                    restoreEntryKey = state.contentReturnContext
                        ?.takeIf { it.origin == ContentReturnOrigin.Search }
                        ?.itemKey,
                    search = viewModel::searchCatalog,
                    onQueryChange = viewModel::updateSearchQuery,
                    onTypeChange = viewModel::updateSearchType,
                    onOpenEntry = viewModel::openSearchEntry,
                    onToggleEntryFavorite = viewModel::toggleEntryFavorite,
                    onBack = viewModel::backFromMenu,
                )

                state.screen is StreamiaScreen.LiveMatches -> LiveOnSatScreen(
                    matches = liveOnSatMatches,
                    loading = state.liveOnSatLoading,
                    resolvingChannels = state.liveOnSatResolving,
                    error = state.liveOnSatError,
                    fetchedAtEpochMillis = state.liveOnSatFetchedAtEpochMillis,
                    restoreMatchKey = state.contentReturnContext
                        ?.takeIf { it.origin == ContentReturnOrigin.LiveMatches }
                        ?.liveMatchKey,
                    restoreChannelKey = state.contentReturnContext
                        ?.takeIf { it.origin == ContentReturnOrigin.LiveMatches }
                        ?.itemKey,
                    onOpenChannel = viewModel::openLiveMatchChannel,
                    onRefresh = viewModel::refreshLiveOnSatMatches,
                    onRefreshIfStale = viewModel::refreshLiveOnSatIfStale,
                    onBack = viewModel::backFromMenu,
                )

                state.screen is StreamiaScreen.Epg && state.catalog != null -> EpgScreen(
                    catalog = state.catalog!!,
                    guide = state.epgGuide,
                    hiddenCategories = state.library.hiddenCategories,
                    hiddenEntries = state.library.hiddenEntries,
                    lockedCategories = state.library.lockedCategories,
                    parentalControlEnabled = state.appSettings.parentalControlEnabled,
                    parentalUnlocked = state.parentalUnlocked,
                    availableDates = state.epgAvailableDates,
                    selectedDate = state.epgSelectedDate,
                    loading = state.epgLoading,
                    message = state.message,
                    onOpenChannel = viewModel::openEntry,
                    onSelectDate = viewModel::selectEpgDate,
                    onReload = viewModel::reloadEpg,
                    onBack = viewModel::backFromMenu,
                )

                state.screen is StreamiaScreen.Organizer && state.catalog != null -> OrganizerScreen(
                    catalog = state.catalog!!,
                    hiddenCategories = state.library.hiddenCategories,
                    hiddenEntries = state.library.hiddenEntries,
                    lockedCategories = state.library.lockedCategories,
                    parentalControlEnabled = state.appSettings.parentalControlEnabled,
                    onCategoryOrderChanged = viewModel::setCategoryOrder,
                    onToggleCategoryHidden = viewModel::toggleCategoryHidden,
                    onToggleEntryHidden = viewModel::toggleEntryHidden,
                    onToggleCategoryLocked = viewModel::toggleCategoryLocked,
                    onMoveEntries = viewModel::moveEntries,
                    onResetMoves = viewModel::resetEntryMoves,
                    onBack = viewModel::closeOrganizer,
                )

                state.screen is StreamiaScreen.MovieDetails -> {
                    val movie = (state.screen as StreamiaScreen.MovieDetails).movie
                    // Film terminé (ou presque) : pas de « Reprendre à 1:52:00 », comme la rangée Reprendre.
                    val resume = state.library.history.firstOrNull { it.entry.key == movie.key }?.takeIf { it.isResumable() }?.positionMs ?: 0L
                    MovieDetailsScreen(
                        movie = movie,
                        details = state.mediaDetails,
                        busy = state.busy,
                        message = state.message,
                        favorite = movie.key in state.library.favoriteEntries,
                        watched = movie.key in state.library.watchedEntries,
                        resumePositionMs = resume,
                        similarMedia = state.similarMedia,
                        // Recherche en base (tout le catalogue), pas seulement les catégories déjà chargées.
                        otherVersions = produceState(emptyList<RecommendedMedia>(), movie.key) {
                            val query = versionSearchQuery(movie)
                            if (query.isNotBlank()) value = otherVersionsOf(movie, viewModel.searchCatalog(query, movie.type))
                        }.value,
                        onPlay = { viewModel.playMovie(movie) },
                        onPlayFromStart = { viewModel.playMovie(movie, fromStart = true) },
                        onToggleFavorite = { viewModel.toggleEntryFavorite(movie) },
                        onToggleWatched = { viewModel.toggleEntryWatched(movie) },
                        onOpenSimilar = viewModel::openEntry,
                        onBack = viewModel::closeDetails,
                    )
                }

                state.screen is StreamiaScreen.Series && state.credentials != null -> {
                    val series = (state.screen as StreamiaScreen.Series).series
                    SeriesScreen(
                        series = series,
                        details = state.seriesDetails,
                        busy = state.busy,
                        message = state.message,
                        favorite = series.key in state.library.favoriteEntries,
                        watched = series.key in state.library.watchedEntries,
                        similarMedia = state.similarMedia,
                        episodeHistory = state.library.history,
                        otherVersions = produceState(emptyList<RecommendedMedia>(), series.key) {
                            val query = versionSearchQuery(series)
                            if (query.isNotBlank()) value = otherVersionsOf(series, viewModel.searchCatalog(query, series.type))
                        }.value,
                        onToggleFavorite = { viewModel.toggleEntryFavorite(series) },
                        onToggleWatched = { viewModel.toggleEntryWatched(series) },
                        onEpisodeSelected = { episode -> viewModel.playEpisode(series, episode) },
                        onOpenSimilar = viewModel::openEntry,
                        onBack = viewModel::closeSeries,
                        onRetry = { viewModel.openEntry(series) },
                    )
                }

                state.screen is StreamiaScreen.Player && state.catalog != null && state.credentials != null -> {
                    val playerScreen = state.screen as StreamiaScreen.Player
                    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
                    PlayerScreen(
                        catalog = state.catalog!!,
                        credentials = state.credentials!!,
                        entry = playerScreen.entry,
                        epg = playerState.epg,
                        resumePositionMs = state.resumePositionMs,
                        appSettings = state.appSettings,
                        hiddenEntries = state.library.hiddenEntries,
                        lockedCategories = state.library.lockedCategories,
                        parentalControlEnabled = state.appSettings.parentalControlEnabled,
                        parentalUnlocked = state.parentalUnlocked,
                        nextEpisode = state.seriesDetails?.nextEpisode(playerScreen.entry.id),
                        livePlaybackSession = livePlaybackSession,
                        liveVideoSurface = liveVideoSurface,
                        liveReturnsToSource = livePlayerReturnsToSource(state.contentReturnContext?.origin),
                        onBack = {
                            val origin = state.contentReturnContext?.origin
                            if (origin == null || origin == ContentReturnOrigin.Browser) {
                                LiveBrowserReturnState.remember(playerScreen.entry)
                            } else if (playerScreen.entry.type == MediaType.Live) {
                                // Retour vers un écran sans aperçu (accueil, matchs du jour) : le
                                // lecteur Live est partagé avec l'aperçu du navigateur, mais ces écrans
                                // n'affichent aucune vidéo — sans cette coupure, le flux continuerait
                                // en fond, audio compris.
                                livePlaybackSession.stop(clearSession = true)
                            }
                            viewModel.closePlayer()
                        },
                        onZap = viewModel::zap,
                        onPreviousChannel = viewModel::previousChannel,
                        pendingZapEntry = playerState.pendingZapEntry,
                        onEntrySelected = viewModel::openEntry,
                        onProgress = viewModel::recordPlayback,
                        onCycleVideoAspect = viewModel::cycleVideoAspect,
                        onPlayNextEpisode = viewModel::playNextEpisode,
                        onMovieFinished = { viewModel.finishMovie(playerScreen.entry) },
                    )
                }

                else -> BootScreen("Chargement…")
              }
            }
        }
    }
}

@Composable
private fun BootScreen(label: String) {
    val transition = rememberInfiniteTransition(label = "startup-loader")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart),
        label = "startup-loader-rotation",
    )
    Box(Modifier.fillMaxSize().background(Night), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StreamiaLogo()
            Spacer(Modifier.height(22.dp))
            Canvas(Modifier.size(34.dp)) {
                drawArc(
                    color = FocusBlueBright,
                    startAngle = rotation,
                    sweepAngle = 255f,
                    useCenter = false,
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(label, color = MutedInk, fontSize = 17.sp)
        }
    }
}

/** Bloc de l'accueil désactivable : la liste source disparaît, sans toucher au calcul en amont. */
private fun <T> List<T>.ifDisabled(block: HomeBlock, settings: AppSettings): List<T> =
    if (block in settings.disabledHomeBlocks) emptyList() else this

/** Chaque rangée JustWatch a son propre réglage ; les deux rangées IA partagent « Recommandations ». */
private val RecommendationRowKind.homeBlock: HomeBlock
    get() = when (this) {
        RecommendationRowKind.JustWatchTopMoviesWeek -> HomeBlock.JustWatchTopMoviesWeek
        RecommendationRowKind.JustWatchTopSeriesWeek -> HomeBlock.JustWatchTopSeriesWeek
        RecommendationRowKind.JustWatchPopularMovies -> HomeBlock.JustWatchPopularMovies
        RecommendationRowKind.JustWatchPopularSeries -> HomeBlock.JustWatchPopularSeries
        RecommendationRowKind.JustWatchNewMovies -> HomeBlock.JustWatchNewMovies
        RecommendationRowKind.JustWatchNewSeries -> HomeBlock.JustWatchNewSeries
        else -> HomeBlock.Recommendations
    }

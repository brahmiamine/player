package fr.streamia.tv.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import fr.streamia.tv.ui.mobile.DeviceKind
import fr.streamia.tv.ui.mobile.LocalDeviceKind
import fr.streamia.tv.ui.mobile.MobileAboutScreen
import fr.streamia.tv.ui.mobile.MobileAdvancedSettingsScreen
import fr.streamia.tv.ui.mobile.MobileParentalControlScreen
import fr.streamia.tv.ui.mobile.MobileBrowserScreen
import fr.streamia.tv.ui.mobile.MobileHomeScreen
import fr.streamia.tv.ui.mobile.MobileDetailsScreen
import fr.streamia.tv.ui.mobile.MobileLoginScreen
import fr.streamia.tv.ui.mobile.MobileMatchesScreen
import fr.streamia.tv.ui.mobile.MobileSearchScreen
import fr.streamia.tv.ui.mobile.MobileSettingsScreen
import fr.streamia.tv.ui.mobile.MobileEpgScreen
import fr.streamia.tv.ui.mobile.MobileOrganizerScreen
import fr.streamia.tv.ui.mobile.MobileMoreScreen
import fr.streamia.tv.ui.mobile.MobileScaffold
import fr.streamia.tv.ui.mobile.MobileTab
import fr.streamia.tv.ui.mobile.detectDeviceKind
import fr.streamia.tv.ui.mobile.isMobileNative
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import androidx.media3.ui.AspectRatioFrameLayout
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
import fr.streamia.tv.domain.MediaEntry
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
    // TV (télécommande) ou mobile (tactile), détecté une fois. Les écrans refaits pour le mobile
    // s'affichent en portrait aux dp réels ; les autres gardent la mise en page TV, en paysage.
    val context = LocalContext.current
    val deviceKind = remember(context) { detectDeviceKind(context) }
    val mobile = deviceKind == DeviceKind.Mobile
    // « Réglages avancés » (ville, IA, sauvegarde, mises à jour) : sous-page des Paramètres mobiles.
    var advancedSettings by remember { mutableStateOf(false) }
    LaunchedEffect(state.screen) { if (state.screen !is StreamiaScreen.Settings) advancedSettings = false }
    val mobileNative = mobile && state.screen.isMobileNative()
    val playerOpen = state.screen is StreamiaScreen.Player
    // Mobile : pas d'aperçu vidéo hors du lecteur, le flux Direct (lecteur partagé) s'arrête dès qu'on le quitte,
    // quel que soit le chemin de sortie (Retour, liste des chaînes…). Films et séries s'arrêtent avec le lecteur.
    // Lecture en arrière-plan (écran verrouillé, appli quittée) : service de premier plan tant que le lecteur est ouvert.
    val playingTitle = (state.screen as? StreamiaScreen.Player)?.entry?.displayName
    LaunchedEffect(playingTitle != null, mobile) {
        if (!mobile) return@LaunchedEffect
        if (playingTitle != null) fr.streamia.tv.player.PlaybackForegroundService.start(context, playingTitle)
        else fr.streamia.tv.player.PlaybackForegroundService.stop(context)
    }
    LaunchedEffect(playerOpen, mobile) { if (mobile && !playerOpen) livePlaybackSession.stop(clearSession = true) }
    LaunchedEffect(mobile, mobileNative, playerOpen) {
        val activity = context as? Activity ?: return@LaunchedEffect
        activity.requestedOrientation = when {
            !mobile -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            mobileNative || playerOpen -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT // lecteur : portrait au départ, paysage via le bouton de rotation
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (mobile) {
            // Mobile : barres système visibles, sauf pendant la lecture plein écran.
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            if (playerOpen) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val aiActive by fr.streamia.tv.data.AiGate.active.collectAsStateWithLifecycle()
    // Vidéo du Direct : une seule vue, posée une fois sous tous les écrans. Le lecteur plein écran et
    // la liste catégories/chaînes ne font que la réclamer (liveVideoSurface). Avant, la vue passait
    // d'un écran à l'autre ; ce déplacement la détachait de la fenêtre, sa surface était détruite
    // puis recréée : petite coupure de l'image à chaque ouverture de la liste des chaînes.
    val liveSurfaceClaims = remember { mutableStateListOf<Any>() }
    val liveSurfaceResizeMode = remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    val liveVideoSurface = remember { liveVideoSurfaceClaim(liveSurfaceClaims, liveSurfaceResizeMode) }

    // État de l'accueil (guides, matchs, recommandations, météo) : flux séparé, lu (.value)
    // seulement dans les écrans qui l'affichent — ses mises à jour ne recomposent pas les autres.
    val homeStateHolder = viewModel.homeState.collectAsStateWithLifecycle()
    val liveOnSatMatchesHolder = viewModel.visibleLiveOnSatMatches.collectAsStateWithLifecycle()
    val liveEpgProgramsHolder = viewModel.liveEpgPrograms.collectAsStateWithLifecycle()

    val onMobileTab: (MobileTab) -> Unit = { tab ->
        when (tab) {
            MobileTab.Home -> viewModel.showHome()
            MobileTab.Live -> viewModel.openSection(MediaType.Live)
            MobileTab.Movies -> viewModel.openSection(MediaType.Movie)
            MobileTab.Series -> viewModel.openSection(MediaType.Series)
            MobileTab.More -> viewModel.showMore()
        }
    }

    StreamiaTheme {
      CompositionLocalProvider(LocalDeviceKind provides deviceKind) {
        ResponsiveTvViewport(nativeDensity = mobileNative) {
            Box(Modifier.fillMaxSize()) {
              // Lecteur : fond noir plein écran sous la vidéo, les dégradés y seraient dessinés pour rien
              // à chaque rafraîchissement du HUD. Ailleurs, la liste ne change qu'avec le type d'écran.
              val screenKind = state.screen::class
              if (state.screen !is StreamiaScreen.Player) {
                  val blobs = remember(screenKind) { glassBlobsFor(state.screen) }
                  GlassBackdrop(blobs)
              }
              // Toujours au même endroit de l'arbre tant qu'un écran la réclame : jamais déplacée.
              if (liveSurfaceClaims.isNotEmpty()) {
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
                          view.resizeMode = liveSurfaceResizeMode.intValue
                          view.subtitleView?.applySubtitleStyle(state.appSettings.subtitleSizeScale, state.appSettings.subtitleBackgroundEnabled)
                      },
                      modifier = Modifier.fillMaxSize().background(Color.Black),
                  )
              }
              Box(if (mobile && !playerOpen) Modifier.fillMaxSize().systemBarsPadding() else Modifier.fillMaxSize()) {
              when {
                // Texte selon ce qui charge réellement : l'ancien « Ouverture de votre dernière
                // lecture… » s'affichait aussi à l'ouverture d'une liste, sans aucune reprise.
                shouldShowStartupGate(state) -> BootScreen(if (state.booting) "Démarrage…" else "Chargement de votre liste…")


                // ---- Mobile : écrans refaits pour le tactile (portrait, barre d'onglets) ----
                mobileNative && state.screen is StreamiaScreen.Home && state.catalog != null -> {
                    val homeState = homeStateHolder.value
                    val settings = state.appSettings
                    MobileScaffold(MobileTab.Home, onSelect = onMobileTab) {
                        MobileHomeScreen(
                            catalog = state.catalog!!,
                            library = state.library,
                            weatherPlace = homeState.weatherPlace,
                            weather = homeState.weather,
                            liveMatches = liveOnSatMatchesHolder.value.ifDisabled(HomeBlock.LiveMatches, settings),
                            liveMatchesResolving = homeState.liveOnSatResolving,
                            offline = state.offline,
                            busy = state.busy,
                            parentalControlEnabled = settings.parentalControlEnabled,
                            parentalUnlocked = state.parentalUnlocked,
                            resumeRowEnabled = HomeBlock.Resume !in settings.disabledHomeBlocks,
                            favoritesRowEnabled = HomeBlock.Favorites !in settings.disabledHomeBlocks,
                            recentChannelsEnabled = HomeBlock.RecentChannels !in settings.disabledHomeBlocks,
                            liveMatchesEnabled = HomeBlock.LiveMatches !in settings.disabledHomeBlocks,
                            footballScoresEnabled = HomeBlock.FootballScores !in settings.disabledHomeBlocks,
                            recommendationRows = remember(homeState.homeRecommendationRows, homeState.homeJustWatchRows, settings.disabledHomeBlocks) {
                                (homeState.homeRecommendationRows + homeState.homeJustWatchRows).filter { it.kind.homeBlock !in settings.disabledHomeBlocks }
                            },
                            tvProgrammeNow = homeState.homeTvProgrammeNow.ifDisabled(HomeBlock.TvProgrammeNow, settings),
                            tvProgrammeTonight = homeState.homeTvProgrammeTonight.ifDisabled(HomeBlock.TvProgrammeTonight, settings),
                            beinSportsNow = homeState.homeBeinSportsNow.ifDisabled(HomeBlock.BeinSportsNow, settings),
                            beinSportsNext = homeState.homeBeinSportsNext.ifDisabled(HomeBlock.BeinSportsNext, settings),
                            ukGuideNow = homeState.homeUkGuideNow.ifDisabled(HomeBlock.UkGuideNow, settings),
                            ukGuideNext = homeState.homeUkGuideNext.ifDisabled(HomeBlock.UkGuideNext, settings),
                            onOpenSection = viewModel::openSection,
                            onSettings = viewModel::showSettings,
                            onSearch = viewModel::showSearch,
                            onEpg = viewModel::showEpg,
                            onRefresh = viewModel::refresh,
                            onOpenLiveMatches = viewModel::showLiveMatches,
                            onChangePlaylist = viewModel::logout,
                            onResumePlayback = viewModel::resumeHomePlayback,
                            onOpenHomeEntry = viewModel::openHomeEntry,
                            onOpenLiveMatchChannel = viewModel::openLiveMatchChannel,
                            onRefreshWeather = viewModel::refreshWeatherIfStale,
                            onRefreshLiveMatches = viewModel::refreshLiveOnSatIfStale,
                            onRefreshTvProgrammeNow = viewModel::refreshTvProgrammeNow,
                            onRefreshBeinSportsGuide = viewModel::refreshBeinSportsGuide,
                            onRefreshUkGuide = viewModel::refreshUkGuide,
                        )
                    }
                }

                mobileNative && state.screen is StreamiaScreen.Browser && state.catalog != null -> {
                    val type = state.browserType ?: MediaType.Live
                    MobileScaffold(type.mobileTab(), onSelect = onMobileTab) {
                        BackHandler { viewModel.showHome() }
                        MobileBrowserScreen(
                            catalog = state.catalog!!,
                            epgPrograms = liveEpgProgramsHolder.value,
                            library = state.library,
                            appSettings = state.appSettings,
                            loadingCategoryKeys = state.loadingCategoryKeys,
                            vodPages = state.vodPages,
                            categoryLoadErrors = state.categoryLoadErrors,
                            parentalUnlocked = state.parentalUnlocked,
                            offline = state.offline,
                            busy = state.busy,
                            message = state.message,
                            type = type,
                            initialCategoryId = state.browserCategoryId,
                            onEntrySelected = viewModel::openEntry,
                            onLiveEntrySelected = viewModel::openLiveFromList,
                            onToggleEntryFavorite = viewModel::toggleEntryFavorite,
                            onToggleEntryHidden = viewModel::toggleEntryHidden,
                            onVerifyParentalPin = viewModel::verifyParentalPin,
                            onLocationChanged = viewModel::rememberBrowserLocation,
                            onEnsureCategoryLoaded = viewModel::ensureCategoryLoaded,
                            onLoadMoreInCategory = viewModel::loadMoreInCategory,
                            onRefresh = viewModel::refresh,
                            onSearch = viewModel::showSearch,
                            onDismissMessage = viewModel::dismissMessage,
                        )
                    }
                }

                mobileNative && state.screen is StreamiaScreen.More -> {
                    MobileScaffold(MobileTab.More, onSelect = onMobileTab) {
                        BackHandler { viewModel.showHome() }
                        MobileMoreScreen(
                            playlistName = state.profiles.firstOrNull { it.id == state.activeProfileId }?.name,
                            versionName = BuildConfig.VERSION_NAME,
                            onSearch = viewModel::showSearch,
                            onEpg = viewModel::showEpg,
                            onLiveMatches = viewModel::showLiveMatches,
                            onOrganizer = viewModel::showOrganizer,
                            onSettings = viewModel::showSettings,
                            onChangePlaylist = viewModel::logout,
                        )
                    }
                }


                mobileNative && state.screen is StreamiaScreen.Login -> MobileLoginScreen(
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

                mobileNative && state.screen is StreamiaScreen.Search && state.catalog != null -> MobileSearchScreen(
                    favoriteEntries = state.library.favoriteEntries,
                    query = state.searchQuery,
                    type = state.searchType,
                    search = viewModel::searchCatalog,
                    onQueryChange = viewModel::updateSearchQuery,
                    onTypeChange = viewModel::updateSearchType,
                    onOpenEntry = viewModel::openSearchEntry,
                    onToggleEntryFavorite = viewModel::toggleEntryFavorite,
                    onBack = viewModel::backFromMenu,
                )

                mobileNative && state.screen is StreamiaScreen.LiveMatches -> {
                    val homeState = homeStateHolder.value
                    MobileMatchesScreen(
                        matches = liveOnSatMatchesHolder.value,
                        loading = homeState.liveOnSatLoading,
                        resolvingChannels = homeState.liveOnSatResolving,
                        error = homeState.liveOnSatError,
                        fetchedAtEpochMillis = homeState.liveOnSatFetchedAtEpochMillis,
                        onOpenChannel = viewModel::openLiveMatchChannel,
                        onRefresh = viewModel::refreshLiveOnSatMatches,
                        onRefreshIfStale = viewModel::refreshLiveOnSatIfStale,
                        onBack = viewModel::backFromMenu,
                    )
                }

                mobileNative && state.screen is StreamiaScreen.MovieDetails -> {
                    val movie = (state.screen as StreamiaScreen.MovieDetails).movie
                    val resume = state.library.history.firstOrNull { it.entry.key == movie.key }?.takeIf { it.isResumable() }?.positionMs ?: 0L
                    val otherVersions = rememberOtherVersions(viewModel, movie)
                    MobileDetailsScreen(
                        entry = movie,
                        movieDetails = state.mediaDetails,
                        seriesDetails = null,
                        busy = state.busy,
                        message = state.message,
                        favorite = movie.key in state.library.favoriteEntries,
                        watched = movie.key in state.library.watchedEntries,
                        resumePositionMs = resume,
                        translatedPlot = state.aiPlot.takeIf { aiActive },
                        similarMedia = state.similarMedia.withAiOrder(state.aiSimilarKeys.takeIf { aiActive }),
                        otherVersions = otherVersions.orEmpty(),
                        episodeHistory = emptyList(),
                        onPlay = { viewModel.playMovie(movie) },
                        onPlayFromStart = { viewModel.playMovie(movie, fromStart = true) },
                        onEpisodeSelected = {},
                        onToggleFavorite = { viewModel.toggleEntryFavorite(movie) },
                        onToggleWatched = { viewModel.toggleEntryWatched(movie) },
                        onOpenSimilar = viewModel::openEntry,
                        onRetry = {},
                        onBack = viewModel::closeDetails,
                    )
                }

                mobileNative && state.screen is StreamiaScreen.Series && state.credentials != null -> {
                    val series = (state.screen as StreamiaScreen.Series).series
                    val otherVersions = rememberOtherVersions(viewModel, series)
                    MobileDetailsScreen(
                        entry = series,
                        movieDetails = null,
                        seriesDetails = state.seriesDetails,
                        busy = state.busy,
                        message = state.message,
                        favorite = series.key in state.library.favoriteEntries,
                        watched = series.key in state.library.watchedEntries,
                        resumePositionMs = 0L,
                        translatedPlot = state.aiPlot.takeIf { aiActive },
                        similarMedia = state.similarMedia.withAiOrder(state.aiSimilarKeys.takeIf { aiActive }),
                        otherVersions = otherVersions.orEmpty(),
                        episodeHistory = state.library.history,
                        onPlay = {},
                        onPlayFromStart = {},
                        onEpisodeSelected = { episode -> viewModel.playEpisode(series, episode) },
                        onToggleFavorite = { viewModel.toggleEntryFavorite(series) },
                        onToggleWatched = { viewModel.toggleEntryWatched(series) },
                        onOpenSimilar = viewModel::openEntry,
                        onRetry = { viewModel.openEntry(series) },
                        onBack = viewModel::closeSeries,
                    )
                }


                mobileNative && state.screen is StreamiaScreen.Settings && advancedSettings -> MobileAdvancedSettingsScreen(
                    settings = state.appSettings,
                    detectedPlaceName = homeStateHolder.value.weatherPlace?.name,
                    currentVersion = BuildConfig.VERSION_NAME,
                    updateChecking = state.updateChecking,
                    updateCheck = state.updateCheck,
                    onCheckForUpdate = viewModel::checkForUpdate,
                    onDismissUpdateCheck = viewModel::dismissUpdateCheck,
                    onInstallUpdate = viewModel::installPendingUpdate,
                    onAllowUpdateInstall = viewModel::openUpdateInstallPermission,
                    onExportBackup = viewModel::exportBackup,
                    onImportBackup = viewModel::importBackup,
                    onSearchCities = viewModel::searchCities,
                    onSetHomePlace = viewModel::setHomePlace,
                    onSetPrayerMethod = viewModel::setPrayerMethod,
                    onToggleAi = viewModel::toggleAi,
                    onSetAiLanguage = viewModel::setAiLanguage,
                    onLoadAiUsage = viewModel::loadAiUsage,
                    onResetAiUsage = viewModel::resetAiUsage,
                    onSetAiProvider = viewModel::setAiProvider,
                    onSetAiModel = viewModel::setAiModel,
                    hasAiKey = viewModel::hasAiKey,
                    onSaveAiKey = viewModel::saveAiKey,
                    onLoadAiModels = viewModel::loadAiModels,
                    onBack = { advancedSettings = false },
                )

                mobileNative && state.screen is StreamiaScreen.Settings -> MobileSettingsScreen(
                    settings = state.appSettings,
                    playlistName = state.profiles.firstOrNull { it.id == state.activeProfileId }?.name,
                    accountExpiresAtEpochSeconds = state.catalog?.account?.expiresAtEpochSeconds,
                    currentVersion = BuildConfig.VERSION_NAME,
                    liveHistoryCount = state.library.history.count { it.entry.type == MediaType.Live },
                    movieHistoryCount = state.library.history.count { it.entry.type == MediaType.Movie },
                    seriesHistoryCount = state.library.history.count { it.entry.type == MediaType.Series },
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
                    onToggleLiveVersionFailover = viewModel::toggleLiveVersionFailover,
                    onCycleSubtitleSizeScale = viewModel::cycleSubtitleSizeScale,
                    onToggleSubtitleBackground = viewModel::toggleSubtitleBackground,
                    onToggleHomeBlock = viewModel::toggleHomeBlock,
                    onOrganizer = viewModel::showOrganizer,
                    onRefresh = viewModel::refresh,
                    onClearLiveHistory = { viewModel.clearHistory(MediaType.Live) },
                    onClearMovieHistory = { viewModel.clearHistory(MediaType.Movie) },
                    onClearSeriesHistory = { viewModel.clearHistory(MediaType.Series) },
                    onClearAllHistory = { viewModel.clearHistory() },
                    onChangePlaylist = viewModel::logout,
                    onParentalControl = viewModel::showParentalControl,
                    onAbout = viewModel::showAbout,
                    onAdvanced = { advancedSettings = true },
                    onBack = viewModel::backFromMenu,
                )


                mobileNative && state.screen is StreamiaScreen.Epg && state.catalog != null -> MobileEpgScreen(
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
                    loadDescription = viewModel::epgDescription,
                    onBack = viewModel::backFromMenu,
                )

                mobileNative && state.screen is StreamiaScreen.Organizer && state.catalog != null -> MobileOrganizerScreen(
                    catalog = state.catalog!!,
                    hiddenCategories = state.library.hiddenCategories,
                    lockedCategories = state.library.lockedCategories,
                    parentalControlEnabled = state.appSettings.parentalControlEnabled,
                    onCategoryOrderChanged = viewModel::setCategoryOrder,
                    onToggleCategoryHidden = viewModel::toggleCategoryHidden,
                    onToggleCategoryLocked = viewModel::toggleCategoryLocked,
                    onBack = viewModel::closeOrganizer,
                )

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

                state.screen is StreamiaScreen.Home && state.catalog != null -> {
                  val homeState = homeStateHolder.value
                  val liveOnSatMatches = liveOnSatMatchesHolder.value
                  // Mêmes instances tant que leurs sources ne changent pas : recréées à chaque
                  // émission, elles faisaient recomposer tout l'accueil et relancer sa restauration.
                  val homeRecommendationRows = remember(homeState.homeRecommendationRows, homeState.homeJustWatchRows, state.appSettings.disabledHomeBlocks) {
                      (homeState.homeRecommendationRows + homeState.homeJustWatchRows).filter { it.kind.homeBlock !in state.appSettings.disabledHomeBlocks }
                  }
                  val homePendingBlocks = remember(homeState.homePendingBlocks, state.appSettings.disabledHomeBlocks) {
                      homeState.homePendingBlocks - state.appSettings.disabledHomeBlocks
                  }
                  HomeScreen(
                    catalog = state.catalog!!,
                    weatherPlace = homeState.weatherPlace,
                    weather = homeState.weather,
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
                    tvProgrammeNow = homeState.homeTvProgrammeNow.ifDisabled(HomeBlock.TvProgrammeNow, state.appSettings),
                    tvProgrammeTonight = homeState.homeTvProgrammeTonight.ifDisabled(HomeBlock.TvProgrammeTonight, state.appSettings),
                    beinSportsNow = homeState.homeBeinSportsNow.ifDisabled(HomeBlock.BeinSportsNow, state.appSettings),
                    beinSportsNext = homeState.homeBeinSportsNext.ifDisabled(HomeBlock.BeinSportsNext, state.appSettings),
                    ukGuideNow = homeState.homeUkGuideNow.ifDisabled(HomeBlock.UkGuideNow, state.appSettings),
                    ukGuideNext = homeState.homeUkGuideNext.ifDisabled(HomeBlock.UkGuideNext, state.appSettings),
                    liveMatches = liveOnSatMatches.ifDisabled(HomeBlock.LiveMatches, state.appSettings),
                    pendingBlocks = homePendingBlocks,
                    liveMatchesPending = homeState.liveOnSatPending && HomeBlock.LiveMatches !in state.appSettings.disabledHomeBlocks,
                    liveMatchesResolving = homeState.liveOnSatResolving,
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
                }

                state.screen is StreamiaScreen.Browser && state.catalog != null && state.credentials != null -> BrowserScreen(
                    catalog = state.catalog!!,
                    credentials = state.credentials!!,
                    livePlaybackSession = livePlaybackSession,
                    liveVideoSurface = liveVideoSurface,
                    epgPrograms = liveEpgProgramsHolder.value,
                    library = state.library,
                    appSettings = state.appSettings,
                    loadingCategoryKeys = state.loadingCategoryKeys,
                    vodPages = state.vodPages,
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
                    detectedPlaceName = homeStateHolder.value.weatherPlace?.name,
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
                    onToggleLiveVersionFailover = viewModel::toggleLiveVersionFailover,
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
                    onToggleAi = viewModel::toggleAi,
                    onSetAiLanguage = viewModel::setAiLanguage,
                    onLoadAiUsage = viewModel::loadAiUsage,
                    onResetAiUsage = viewModel::resetAiUsage,
                    onSetAiProvider = viewModel::setAiProvider,
                    onSetAiModel = viewModel::setAiModel,
                    hasAiKey = viewModel::hasAiKey,
                    onSaveAiKey = viewModel::saveAiKey,
                    onLoadAiModels = viewModel::loadAiModels,
                    onBack = viewModel::backFromMenu,
                )

                mobileNative && state.screen is StreamiaScreen.ParentalControl -> MobileParentalControlScreen(
                    enabled = state.appSettings.parentalControlEnabled,
                    onSetPin = viewModel::setParentalPin,
                    onVerifyPin = viewModel::verifyParentalPin,
                    onDisable = viewModel::disableParentalControl,
                    onBack = viewModel::backFromMenu,
                )

                mobileNative && state.screen is StreamiaScreen.About -> MobileAboutScreen(
                    versionName = BuildConfig.VERSION_NAME,
                    onLoadCacheSize = viewModel::cacheSizeBytes,
                    onLoadEpgCacheSize = viewModel::epgCacheSizeBytes,
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

                state.screen is StreamiaScreen.LiveMatches -> {
                  val homeState = homeStateHolder.value
                  LiveOnSatScreen(
                    matches = liveOnSatMatchesHolder.value,
                    loading = homeState.liveOnSatLoading,
                    resolvingChannels = homeState.liveOnSatResolving,
                    error = homeState.liveOnSatError,
                    fetchedAtEpochMillis = homeState.liveOnSatFetchedAtEpochMillis,
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
                }

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
                    loadDescription = viewModel::epgDescription,
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
                    val otherVersions = rememberOtherVersions(viewModel, movie)
                    MovieDetailsScreen(
                        movie = movie,
                        details = state.mediaDetails,
                        busy = state.busy,
                        message = state.message,
                        favorite = movie.key in state.library.favoriteEntries,
                        watched = movie.key in state.library.watchedEntries,
                        resumePositionMs = resume,
                        translatedPlot = state.aiPlot.takeIf { aiActive },
                        aiPlotLoading = aiActive && state.aiPlotLoading,
                        aiSimilarLoading = aiActive && state.aiSimilarLoading,
                        similarMedia = state.similarMedia.withAiOrder(state.aiSimilarKeys.takeIf { aiActive }),
                        similarLoading = state.similarLoading,
                        otherVersions = otherVersions.orEmpty(),
                        otherVersionsLoading = otherVersions == null,
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
                    val otherVersions = rememberOtherVersions(viewModel, series)
                    SeriesScreen(
                        series = series,
                        details = state.seriesDetails,
                        busy = state.busy,
                        message = state.message,
                        favorite = series.key in state.library.favoriteEntries,
                        watched = series.key in state.library.watchedEntries,
                        translatedPlot = state.aiPlot.takeIf { aiActive },
                        aiPlotLoading = aiActive && state.aiPlotLoading,
                        aiSimilarLoading = aiActive && state.aiSimilarLoading,
                        similarMedia = state.similarMedia.withAiOrder(state.aiSimilarKeys.takeIf { aiActive }),
                        similarLoading = state.similarLoading,
                        otherVersions = otherVersions.orEmpty(),
                        otherVersionsLoading = otherVersions == null,
                        episodeHistory = state.library.history,
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
                    val liveVersionIndex by viewModel.liveVersionIndex.collectAsStateWithLifecycle()
                    // Rappels stables (clés : contenu joué) : recréés à chaque évolution de l'état, ils
                    // faisaient recomposer tout le lecteur dès qu'une tâche de fond publiait quelque
                    // chose (guides, recommandations, historique), sans rien changer pour lui.
                    val latestReturnOrigin by rememberUpdatedState(state.contentReturnContext?.origin)
                    val onPlayerBack = remember(playerScreen.entry, livePlaybackSession, mobile) {
                        {
                            val origin = latestReturnOrigin
                            // Mobile : pas d'aperçu vidéo dans la liste, le flux s'arrête avec le lecteur.
                            if (mobile && playerScreen.entry.type == MediaType.Live) livePlaybackSession.stop(clearSession = true)
                            // Mobile : la liste du Direct rouvrira sur la catégorie et la chaîne jouées, d'où qu'elle ait été lancée.
                            if (mobile && playerScreen.entry.type == MediaType.Live) LiveBrowserReturnState.remember(playerScreen.entry)
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
                        }
                    }
                    val onMovieFinished = remember(playerScreen.entry) { { viewModel.finishMovie(playerScreen.entry) } }
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
                        seriesTitle = state.seriesDetails?.series?.name,
                        livePlaybackSession = livePlaybackSession,
                        liveVideoSurface = liveVideoSurface,
                        liveReturnsToSource = livePlayerReturnsToSource(state.contentReturnContext?.origin),
                        onBack = onPlayerBack,
                        onZap = viewModel::zap,
                        onPreviousChannel = viewModel::previousChannel,
                        pendingZapEntry = playerState.pendingZapEntry,
                        onEntrySelected = viewModel::openEntry,
                        onSwitchVersion = viewModel::switchLiveVersion,
                        liveVersionIndex = liveVersionIndex,
                        onProgress = viewModel::recordPlayback,
                        onCycleVideoAspect = viewModel::cycleVideoAspect,
                        onPlayNextEpisode = viewModel::playNextEpisode,
                        onMovieFinished = onMovieFinished,
                    )
                }

                else -> BootScreen("Chargement…")
              }
              }
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

/**
 * Ce que les écrans appellent à la place de la vidéo du Direct : ils la réclament le temps d'être
 * affichés (et choisissent son cadrage), la vue elle-même reste posée par [StreamiaApp].
 */
private fun liveVideoSurfaceClaim(
    claims: SnapshotStateList<Any>,
    resizeMode: MutableIntState,
): @Composable (LiveVideoSurfacePlacement) -> Unit = { placement ->
    val claim = remember { Any() }
    DisposableEffect(claim) {
        claims += claim
        onDispose { claims -= claim }
    }
    SideEffect { resizeMode.intValue = placement.resizeMode }
}

/**
 * « Autres versions » de la fiche ouverte, recherchées en base (tout le catalogue, pas seulement les
 * catégories déjà chargées). `null` tant que la recherche est en cours : la rangée montre ses cartes fantômes.
 */
@Composable
private fun rememberOtherVersions(viewModel: StreamiaViewModel, entry: MediaEntry): List<RecommendedMedia>? =
    produceState<List<RecommendedMedia>?>(null, entry.key) {
        value = null
        val query = versionSearchQuery(entry)
        value = if (query.isBlank()) emptyList() else otherVersionsOf(entry, viewModel.searchCatalog(query, entry.type))
    }.value

private fun MediaType.mobileTab(): MobileTab = when (this) {
    MediaType.Live -> MobileTab.Live
    MediaType.Movie -> MobileTab.Movies
    MediaType.Series -> MobileTab.Series
}

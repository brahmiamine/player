package fr.streamia.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import fr.streamia.tv.data.NetworkMonitor
import fr.streamia.tv.data.PlaybackSessionStore
import fr.streamia.tv.data.resolveStartupProfileId
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.player.LivePlaybackSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * OK / gauche / menu sur le lecteur Live demandent un retour vers le Browser Live principal
 * (catégories + chaînes + aperçu), traité par [StreamiaTvRoot].
 */
object PlayerOverlayController {
    private val _returnToBrowser = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val returnToBrowser = _returnToBrowser.asSharedFlow()

    fun requestReturnToBrowser() {
        _returnToBrowser.tryEmit(Unit)
    }
}

/**
 * Racine TV autour de StreamiaApp.
 * Elle restaure la dernière playlist/contenu et centralise le retour du plein écran Live
 * vers l'interface principale catégories + chaînes + aperçu.
 */
@Composable
fun StreamiaTvRoot(viewModel: StreamiaViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sessionStore = remember { PlaybackSessionStore(context.applicationContext) }
    val livePlaybackSession = remember(state.appSettings.bufferMode, state.appSettings.tunnelingEnabled) {
        LivePlaybackSession(context.applicationContext, state.appSettings.bufferMode, state.appSettings.tunnelingEnabled)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingLiveBrowserReturn by remember { mutableStateOf(false) }
    val networkMonitor = remember { NetworkMonitor.get(context) }
    val networkReconnections by networkMonitor.reconnections.collectAsStateWithLifecycle()

    // App visible seulement (aucune requête en arrière-plan) : retour du réseau, y compris pendant
    // que l'app était cachée, et nouvel essai régulier tant que la liste reste en « Mode cache »
    // (serveur Xtream injoignable alors que la connexion, elle, fonctionne).
    LaunchedEffect(lifecycleOwner) {
        var handledReconnections = networkMonitor.reconnections.value
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                networkMonitor.reconnections.collect { count ->
                    if (count != handledReconnections) {
                        handledReconnections = count
                        viewModel.onNetworkRestored()
                    }
                }
            }
            while (true) {
                viewModel.retryOfflineCatalog()
                delay(OFFLINE_CATALOG_RETRY_MS)
            }
        }
    }

    DisposableEffect(livePlaybackSession, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> livePlaybackSession.resume()
                Lifecycle.Event.ON_STOP -> livePlaybackSession.stop(clearSession = false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            livePlaybackSession.release()
        }
    }

    LaunchedEffect(Unit) {
        val initialState = viewModel.uiState.value
        if (initialState.activeProfileId != null || initialState.screen !is StreamiaScreen.Login) return@LaunchedEffect

        val availableIds = initialState.profiles.map { it.id }
        val storedSession = sessionStore.load()
        val validSession = storedSession?.takeIf { it.profileId in availableIds }
        if (storedSession != null && validSession == null) sessionStore.clearPlayback()

        val targetProfileId = resolveStartupProfileId(
            availableProfileIds = availableIds,
            playbackProfileId = validSession?.profileId,
            activeProfileId = sessionStore.loadActiveProfileId(),
            autoOpenDisabled = sessionStore.isAutoOpenDisabled(),
        )
        if (targetProfileId == null) {
            viewModel.finishStartup()
            return@LaunchedEffect
        }

        // Au démarrage on rouvre la dernière page de navigation, jamais directement le dernier
        // contenu joué (la reprise directe reste réservée à la carte « Continuer à regarder »).
        viewModel.openProfile(targetProfileId)
        val loaded = viewModel.uiState.first { candidate ->
            val profileReady = candidate.activeProfileId == targetProfileId &&
                candidate.catalog != null &&
                !candidate.busy
            val failed = candidate.screen is StreamiaScreen.Login &&
                !candidate.busy &&
                candidate.message != null
            profileReady || failed
        }

        if (loaded.activeProfileId != targetProfileId || loaded.catalog == null) return@LaunchedEffect
        when (val page = sessionStore.loadLastPage()) {
            "search" -> viewModel.showSearch()
            "live_matches" -> viewModel.showLiveMatches()
            "epg" -> viewModel.showEpg()
            "settings" -> viewModel.showSettings()
            else -> page?.removePrefix("browser:")?.takeIf { page.startsWith("browser:") }
                ?.let { type -> MediaType.entries.firstOrNull { it.name == type } }
                ?.let(viewModel::openSection)
        }
    }

    LaunchedEffect(Unit) {
        var previouslyActiveProfileId: String? = null
        // Seuls profil, page et contenu comptent : l'état complet change en continu (pages chargées,
        // progression, guides…) et chaque émission relisait puis réécrivait ces préférences sur le
        // thread principal.
        viewModel.uiState
            .map(::persistedNavigationOf)
            .distinctUntilChanged()
            .collect { current ->
                val activeProfileId = current.activeProfileId
                if (activeProfileId != null) {
                    val savedPlayback = sessionStore.load()
                    if (savedPlayback != null && savedPlayback.profileId != activeProfileId) {
                        sessionStore.clearPlayback()
                    }
                    sessionStore.saveActiveProfile(activeProfileId)
                    previouslyActiveProfileId = activeProfileId
                    current.lastPage?.let(sessionStore::saveLastPage)
                    current.content?.let { sessionStore.save(activeProfileId, it, current.returnToSeries) }
                }

                if (current.onLogin && activeProfileId == null && previouslyActiveProfileId != null) {
                    sessionStore.disableAutoOpen()
                    previouslyActiveProfileId = null
                }
            }
    }

    LaunchedEffect(Unit) {
        PlayerOverlayController.returnToBrowser.collect {
            val current = viewModel.uiState.value
            val playerScreen = current.screen as? StreamiaScreen.Player
            if (playerScreen?.entry?.type == MediaType.Live) {
                LiveBrowserReturnState.remember(playerScreen.entry.key)
                if (shouldDeferLiveBrowserReturn(current)) {
                    pendingLiveBrowserReturn = true
                } else {
                    pendingLiveBrowserReturn = false
                    viewModel.closePlayer(forceBrowser = true)
                }
            }
        }
    }

    LaunchedEffect(state.catalogHydrating, state.screen, pendingLiveBrowserReturn) {
        if (!pendingLiveBrowserReturn) return@LaunchedEffect
        val playerScreen = state.screen as? StreamiaScreen.Player
        if (playerScreen?.entry?.type != MediaType.Live) {
            pendingLiveBrowserReturn = false
            return@LaunchedEffect
        }
        if (!state.catalogHydrating) {
            pendingLiveBrowserReturn = false
            LiveBrowserReturnState.remember(playerScreen.entry.key)
            viewModel.closePlayer(forceBrowser = true)
        }
    }

    CompositionLocalProvider(LocalNetworkReconnections provides networkReconnections) {
        StreamiaApp(viewModel, livePlaybackSession)
    }
}

/** Ce que [StreamiaTvRoot] garde pour rouvrir l'app au même endroit au prochain démarrage. */
private data class PersistedNavigation(
    val activeProfileId: String?,
    val lastPage: String?,
    val content: MediaEntry?,
    val returnToSeries: Boolean,
    val onLogin: Boolean,
)

private fun persistedNavigationOf(state: StreamiaUiState): PersistedNavigation {
    // Fiches et lecteur ne comptent pas : on garde la page d'où ils ont été ouverts.
    val lastPage = when (state.screen) {
        StreamiaScreen.Home -> "home"
        StreamiaScreen.Browser -> state.browserType?.let { "browser:${it.name}" }
        StreamiaScreen.Search -> "search"
        StreamiaScreen.LiveMatches -> "live_matches"
        StreamiaScreen.Epg -> "epg"
        StreamiaScreen.Settings -> "settings"
        else -> null
    }
    val playerScreen = state.screen as? StreamiaScreen.Player
    val content = playerScreen?.entry ?: state.lastViewedEntry
    return PersistedNavigation(
        activeProfileId = state.activeProfileId,
        lastPage = lastPage,
        content = content,
        returnToSeries = playerScreen?.returnToSeries ?: (content?.type == MediaType.Series),
        onLogin = state.screen is StreamiaScreen.Login,
    )
}

/**
 * Nombre de retours du réseau depuis le lancement : les composants qui ont échoué pendant une
 * coupure (logos, scores, lecteur) le prennent comme clé pour réessayer dès qu'il change.
 */
internal val LocalNetworkReconnections = compositionLocalOf { 0 }

private const val OFFLINE_CATALOG_RETRY_MS = 5 * 60_000L

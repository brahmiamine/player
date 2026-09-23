package fr.streamia.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.streamia.tv.data.PlaybackSessionStore
import fr.streamia.tv.data.resolveStartupProfileId
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.player.LivePlaybackSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first

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
    val livePlaybackSession = remember(state.appSettings.bufferMode) {
        LivePlaybackSession(context.applicationContext, state.appSettings.bufferMode)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingLiveBrowserReturn by remember { mutableStateOf(false) }

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
        viewModel.uiState.collect { current ->
            val activeProfileId = current.activeProfileId
            if (activeProfileId != null) {
                val savedPlayback = sessionStore.load()
                if (savedPlayback != null && savedPlayback.profileId != activeProfileId) {
                    sessionStore.clearPlayback()
                }
                sessionStore.saveActiveProfile(activeProfileId)
                previouslyActiveProfileId = activeProfileId
            }

            // Fiches et lecteur ne comptent pas : on garde la page d'où ils ont été ouverts.
            when (current.screen) {
                StreamiaScreen.Home -> "home"
                StreamiaScreen.Browser -> current.browserType?.let { "browser:${it.name}" }
                StreamiaScreen.Search -> "search"
                StreamiaScreen.LiveMatches -> "live_matches"
                StreamiaScreen.Epg -> "epg"
                StreamiaScreen.Settings -> "settings"
                else -> null
            }?.takeIf { activeProfileId != null }?.let(sessionStore::saveLastPage)

            val playerScreen = current.screen as? StreamiaScreen.Player
            if (playerScreen != null && activeProfileId != null) {
                sessionStore.save(activeProfileId, playerScreen.entry, playerScreen.returnToSeries)
            } else if (current.lastViewedEntry != null && activeProfileId != null) {
                val entry = current.lastViewedEntry ?: return@collect
                sessionStore.save(activeProfileId, entry, entry.type == MediaType.Series)
            }

            if (current.screen is StreamiaScreen.Login && activeProfileId == null && previouslyActiveProfileId != null) {
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

    StreamiaApp(viewModel, livePlaybackSession)
}

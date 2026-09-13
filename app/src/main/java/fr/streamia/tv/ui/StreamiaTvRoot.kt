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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Point d'entrée conservé pour PlayerScreen. L'ancien sélecteur Live superposé a été supprimé :
 * OK / gauche / menu demandent maintenant simplement un retour vers le Browser Live principal.
 */
object PlayerOverlayController {
    private val _livePickerOpen = MutableStateFlow(false)
    val livePickerOpen = _livePickerOpen.asStateFlow()

    private val _returnToBrowser = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val returnToBrowser = _returnToBrowser.asSharedFlow()

    fun openLivePicker() {
        _livePickerOpen.value = false
        _returnToBrowser.tryEmit(Unit)
    }

    fun closeLivePicker() {
        _livePickerOpen.value = false
    }

    fun isLivePickerOpen(): Boolean = false
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
                Lifecycle.Event.ON_START -> {
                    if (shouldKeepLivePlayback(viewModel.uiState.value.screen)) {
                        livePlaybackSession.resume()
                    }
                }
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

    LaunchedEffect(state.screen, livePlaybackSession) {
        if (!shouldKeepLivePlayback(state.screen)) {
            livePlaybackSession.stop(clearSession = true)
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

        val session = validSession?.takeIf { it.profileId == targetProfileId }
        if (session != null) {
            viewModel.resumeStartup(targetProfileId, session.entry, session.returnToSeries)
            return@LaunchedEffect
        }

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
    }

    LaunchedEffect(Unit) {
        var previouslyActiveProfileId: String? = null
        var lastPlaybackFingerprint: String? = null
        viewModel.uiState.collect { current ->
            val activeProfileId = current.activeProfileId
            if (activeProfileId != null && activeProfileId != previouslyActiveProfileId) {
                withContext(Dispatchers.IO) {
                    val savedPlayback = sessionStore.load()
                    if (savedPlayback != null && savedPlayback.profileId != activeProfileId) {
                        sessionStore.clearPlayback()
                    }
                    sessionStore.saveActiveProfile(activeProfileId)
                }
                previouslyActiveProfileId = activeProfileId
                lastPlaybackFingerprint = null
            }

            val playerScreen = current.screen as? StreamiaScreen.Player
            val playbackFingerprint = when {
                playerScreen != null && activeProfileId != null ->
                    "$activeProfileId:${playerScreen.entry.key}:${playerScreen.returnToSeries}"
                current.lastViewedEntry != null && activeProfileId != null -> {
                    val entry = current.lastViewedEntry
                    "$activeProfileId:${entry.key}:${entry.type == MediaType.Series}"
                }
                else -> null
            }
            if (playbackFingerprint != null && playbackFingerprint != lastPlaybackFingerprint) {
                lastPlaybackFingerprint = playbackFingerprint
                withContext(Dispatchers.IO) {
                    val player = current.screen as? StreamiaScreen.Player
                    if (player != null && activeProfileId != null) {
                        sessionStore.save(activeProfileId, player.entry, player.returnToSeries)
                    } else {
                        val entry = current.lastViewedEntry ?: return@withContext
                        sessionStore.save(activeProfileId ?: return@withContext, entry, entry.type == MediaType.Series)
                    }
                }
            }

            if (current.screen is StreamiaScreen.Login && activeProfileId == null && previouslyActiveProfileId != null) {
                withContext(Dispatchers.IO) { sessionStore.disableAutoOpen() }
                previouslyActiveProfileId = null
                lastPlaybackFingerprint = null
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

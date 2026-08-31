package fr.streamia.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.streamia.tv.data.PlaybackSessionStore
import fr.streamia.tv.data.resolveStartupProfileId
import fr.streamia.tv.domain.MediaType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

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
        ) ?: return@LaunchedEffect

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
        val session = validSession?.takeIf { it.profileId == targetProfileId } ?: return@LaunchedEffect

        val restoredEntry = when {
            session.entry.type == MediaType.Series && session.entry.playable -> session.entry
            else -> loaded.catalog.entry(session.entry.key) ?: session.entry
        }

        when (restoredEntry.type) {
            MediaType.Movie -> viewModel.playMovie(restoredEntry)
            MediaType.Live,
            MediaType.Series,
            -> viewModel.openEntry(restoredEntry)
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

            val playerScreen = current.screen as? StreamiaScreen.Player
            if (playerScreen != null && activeProfileId != null) {
                sessionStore.save(activeProfileId, playerScreen.entry, playerScreen.returnToSeries)
            }

            if (current.screen is StreamiaScreen.Login && activeProfileId == null && previouslyActiveProfileId != null) {
                sessionStore.disableAutoOpen()
                previouslyActiveProfileId = null
            }
        }
    }

    LaunchedEffect(Unit) {
        PlayerOverlayController.returnToBrowser.collect {
            val playerScreen = viewModel.uiState.value.screen as? StreamiaScreen.Player
            if (playerScreen?.entry?.type == MediaType.Live) {
                LiveBrowserReturnState.remember(playerScreen.entry.key)
                viewModel.closePlayer()
            }
        }
    }

    StreamiaApp(viewModel)
}

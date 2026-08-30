package fr.streamia.tv.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.streamia.tv.data.CatalogSource
import fr.streamia.tv.data.LoadedCatalog
import fr.streamia.tv.data.PlaylistProfile
import fr.streamia.tv.data.XtreamRepository
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.SeriesDetails
import fr.streamia.tv.domain.SeriesEpisode
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.adjacentTo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StreamiaViewModel(private val repository: XtreamRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(StreamiaUiState())
    val uiState: StateFlow<StreamiaUiState> = _uiState.asStateFlow()

    init {
        showLogin()
    }

    fun signIn(
        profileId: String?,
        profileName: String,
        server: String,
        username: String,
        password: String,
    ) {
        if (_uiState.value.busy) return
        val credentials = ServerCredentials(server.trim(), username.trim(), password)
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { repository.signIn(credentials, profileId, profileName) }
                .onSuccess(::showCatalog)
                .onFailure(::showError)
        }
    }

    fun openProfile(profileId: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = "Ouverture de la liste…") }
        viewModelScope.launch {
            runCatching { repository.openProfile(profileId) }
                .onSuccess(::showCatalog)
                .onFailure(::showError)
        }
    }

    fun importM3u(uri: Uri, profileId: String?, profileName: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = "Analyse du fichier M3U…") }
        viewModelScope.launch {
            runCatching { repository.importM3u(uri, profileId, profileName) }
                .onSuccess(::showCatalog)
                .onFailure(::showError)
        }
    }

    fun renameProfile(profileId: String, name: String) {
        val updated = repository.renameProfile(profileId, name) ?: return
        _uiState.update { state ->
            state.copy(
                profiles = state.profiles.map { if (it.id == updated.id) updated else it }
                    .sortedByDescending(PlaylistProfile::updatedAt),
                message = null,
            )
        }
    }

    fun deleteProfile(profileId: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { repository.deleteProfile(profileId) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            busy = false,
                            profiles = repository.profiles(),
                            message = "Liste supprimée.",
                        )
                    }
                }
                .onFailure(::showError)
        }
    }

    fun refresh() {
        val state = _uiState.value
        val credentials = state.credentials ?: return
        val profileId = state.activeProfileId ?: return
        if (state.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { repository.refresh(credentials, profileId) }
                .onSuccess(::showCatalog)
                .onFailure(::showError)
        }
    }

    fun openEntry(entry: MediaEntry) {
        if (entry.type == MediaType.Series && !entry.playable) {
            loadSeries(entry)
        } else {
            openPlayer(entry, returnToSeries = false)
        }
    }

    fun playEpisode(series: MediaEntry, episode: SeriesEpisode) {
        val playable = MediaEntry(
            id = episode.id,
            name = episode.title,
            displayName = "S${episode.season.toString().padStart(2, '0')}E${episode.number.toString().padStart(2, '0')} · ${episode.title}",
            type = MediaType.Series,
            categoryId = series.categoryId,
            iconUrl = episode.iconUrl ?: series.iconUrl,
            number = episode.number,
            extension = episode.extension,
            plot = episode.plot,
            rating = series.rating,
            playable = true,
        )
        openPlayer(playable, returnToSeries = true)
    }

    fun closePlayer() {
        val player = _uiState.value.screen as? StreamiaScreen.Player
        _uiState.update {
            it.copy(
                screen = if (player?.returnToSeries == true && it.seriesDetails != null) {
                    StreamiaScreen.Series(it.seriesDetails.series)
                } else {
                    StreamiaScreen.Browser
                },
                epg = emptyList(),
            )
        }
    }

    fun closeSeries() {
        _uiState.update { it.copy(screen = StreamiaScreen.Browser, seriesDetails = null, message = null) }
    }

    fun zap(delta: Int) {
        val state = _uiState.value
        val current = (state.screen as? StreamiaScreen.Player)?.entry ?: return
        if (current.type != MediaType.Live) return
        val next = state.catalog
            ?.entriesIn(MediaType.Live, current.categoryId)
            ?.adjacentTo(current.key, delta)
            ?: return
        openPlayer(next, returnToSeries = false)
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            showLogin()
        }
    }

    private fun openPlayer(entry: MediaEntry, returnToSeries: Boolean) {
        _uiState.update {
            it.copy(
                screen = StreamiaScreen.Player(entry, returnToSeries),
                message = null,
                epg = emptyList(),
            )
        }
        if (entry.type == MediaType.Live) loadEpg(entry)
    }

    private fun loadSeries(series: MediaEntry) {
        val credentials = _uiState.value.credentials ?: return
        _uiState.update {
            it.copy(
                busy = true,
                message = null,
                seriesDetails = null,
                screen = StreamiaScreen.Series(series),
            )
        }
        viewModelScope.launch {
            runCatching { repository.seriesDetails(credentials, series) }
                .onSuccess { details ->
                    _uiState.update { it.copy(busy = false, seriesDetails = details) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(busy = false, message = error.safeMessage()) }
                }
        }
    }

    private fun loadEpg(entry: MediaEntry) {
        val credentials = _uiState.value.credentials ?: return
        viewModelScope.launch {
            runCatching { repository.shortEpg(credentials, entry.id) }
                .onSuccess { programs ->
                    val current = (_uiState.value.screen as? StreamiaScreen.Player)?.entry
                    if (current?.key == entry.key) _uiState.update { it.copy(epg = programs) }
                }
        }
    }

    private fun showLogin() {
        _uiState.value = StreamiaUiState(
            booting = false,
            screen = StreamiaScreen.Login,
            profiles = repository.profiles(),
        )
    }

    private fun showCatalog(loaded: LoadedCatalog) {
        _uiState.value = StreamiaUiState(
            booting = false,
            busy = false,
            screen = StreamiaScreen.Browser,
            catalog = loaded.catalog,
            credentials = loaded.credentials,
            activeProfileId = loaded.profileId,
            profiles = repository.profiles(),
            offline = loaded.source == CatalogSource.Cache,
            message = loaded.importSummary,
        )
    }

    private fun showError(error: Throwable) {
        _uiState.update { it.copy(busy = false, message = error.safeMessage(), profiles = repository.profiles()) }
    }

    private fun Throwable.safeMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Une erreur inattendue s'est produite."
}

data class StreamiaUiState(
    val booting: Boolean = true,
    val busy: Boolean = false,
    val screen: StreamiaScreen = StreamiaScreen.Login,
    val catalog: Catalog? = null,
    val credentials: ServerCredentials? = null,
    val activeProfileId: String? = null,
    val profiles: List<PlaylistProfile> = emptyList(),
    val offline: Boolean = false,
    val message: String? = null,
    val seriesDetails: SeriesDetails? = null,
    val epg: List<EpgProgram> = emptyList(),
)

sealed interface StreamiaScreen {
    data object Login : StreamiaScreen
    data object Browser : StreamiaScreen
    data class Series(val series: MediaEntry) : StreamiaScreen
    data class Player(val entry: MediaEntry, val returnToSeries: Boolean = false) : StreamiaScreen
}

class StreamiaViewModelFactory(private val repository: XtreamRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = StreamiaViewModel(repository) as T
}

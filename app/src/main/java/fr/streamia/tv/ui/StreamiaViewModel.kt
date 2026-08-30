package fr.streamia.tv.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.streamia.tv.data.CatalogSource
import fr.streamia.tv.data.LoadedCatalog
import fr.streamia.tv.data.PlaylistProfile
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.data.XtreamRepository
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgGuide
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaDetails
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

    init { showLogin() }

    fun signIn(profileId: String?, profileName: String, server: String, username: String, password: String) {
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

    fun importM3uUrl(
        profileId: String?,
        profileName: String,
        m3uUrl: String,
        xmlTvUrl: String,
        autoRefreshHours: Int,
    ) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = "Téléchargement et analyse de la playlist…") }
        viewModelScope.launch {
            runCatching {
                repository.importM3uUrl(m3uUrl, profileId, profileName, xmlTvUrl, autoRefreshHours)
            }.onSuccess(::showCatalog).onFailure(::showError)
        }
    }

    fun saveM3uSettings(profileId: String, m3uUrl: String, xmlTvUrl: String, autoRefreshHours: Int) {
        val updated = repository.updateRemoteSettings(
            profileId,
            m3uUrl.trim().takeIf(String::isNotBlank),
            xmlTvUrl.trim().takeIf(String::isNotBlank),
            autoRefreshHours,
        ) ?: return
        _uiState.update { it.copy(profiles = repository.profiles(), message = "Paramètres M3U/EPG enregistrés.") }
        if (updated.isRemoteM3u && m3uUrl.isNotBlank()) openProfile(profileId)
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
                .onSuccess { _uiState.update { it.copy(busy = false, profiles = repository.profiles(), message = "Liste supprimée.") } }
                .onFailure(::showError)
        }
    }

    fun refresh() {
        val profileId = _uiState.value.activeProfileId ?: return
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { repository.refreshProfile(profileId) }
                .onSuccess(::showCatalog)
                .onFailure(::showError)
        }
    }

    fun openEntry(entry: MediaEntry) {
        when {
            entry.type == MediaType.Live -> openPlayer(entry, returnToSeries = false)
            entry.type == MediaType.Movie -> loadMovie(entry)
            entry.type == MediaType.Series && !entry.playable -> loadSeries(entry)
            else -> openPlayer(entry, returnToSeries = entry.type == MediaType.Series)
        }
    }

    fun playMovie(movie: MediaEntry) = openPlayer(movie, returnToSeries = false)

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
            rating = episode.rating ?: series.rating,
            playable = true,
        )
        openPlayer(playable, returnToSeries = true)
    }

    fun showSearch() { _uiState.update { it.copy(screen = StreamiaScreen.Search, message = null) } }
    fun showOrganizer() { _uiState.update { it.copy(screen = StreamiaScreen.Organizer, message = null) } }
    fun showBrowser() { _uiState.update { it.copy(screen = StreamiaScreen.Browser, message = null) } }

    fun openCategory(category: MediaCategory) {
        _uiState.update {
            it.copy(
                screen = StreamiaScreen.Browser,
                browserType = category.type,
                browserCategoryId = category.id,
                message = null,
            )
        }
    }

    fun showEpg() {
        val state = _uiState.value
        val profileId = state.activeProfileId ?: return
        val credentials = state.credentials ?: return
        val rawCatalog = state.rawCatalog ?: state.catalog ?: return
        _uiState.update { it.copy(screen = StreamiaScreen.Epg, epgLoading = it.epgGuide == null, message = null) }
        if (state.epgGuide != null) return
        viewModelScope.launch {
            runCatching { repository.fullEpg(profileId, credentials, rawCatalog) }
                .onSuccess { guide -> _uiState.update { it.copy(epgGuide = guide, epgLoading = false) } }
                .onFailure { error -> _uiState.update { it.copy(epgLoading = false, message = error.safeMessage()) } }
        }
    }

    fun reloadEpg() {
        _uiState.update { it.copy(epgGuide = null) }
        showEpg()
    }

    fun toggleEntryFavorite(entry: MediaEntry) {
        val profileId = _uiState.value.activeProfileId ?: return
        repository.toggleEntryFavorite(profileId, entry)
        refreshLibraryPresentation()
    }

    fun toggleCategoryFavorite(category: MediaCategory) {
        val profileId = _uiState.value.activeProfileId ?: return
        repository.toggleCategoryFavorite(profileId, category)
        refreshLibraryPresentation()
    }

    fun recordPlayback(entry: MediaEntry, positionMs: Long, durationMs: Long) {
        val profileId = _uiState.value.activeProfileId ?: return
        repository.recordPlayback(profileId, entry, positionMs, durationMs)
        _uiState.update { it.copy(library = repository.library(profileId)) }
    }

    fun clearHistory() {
        val profileId = _uiState.value.activeProfileId ?: return
        repository.clearHistory(profileId)
        refreshLibraryPresentation()
    }

    fun setCategoryOrder(type: MediaType, categoryKeys: List<String>) {
        val profileId = _uiState.value.activeProfileId ?: return
        repository.setCategoryOrder(profileId, type, categoryKeys)
        refreshLibraryPresentation()
    }

    fun moveEntries(entryKeys: Set<String>, targetCategoryId: String) {
        val profileId = _uiState.value.activeProfileId ?: return
        repository.moveEntries(profileId, entryKeys, targetCategoryId)
        refreshLibraryPresentation()
    }

    fun resetEntryMoves(entryKeys: Set<String>) {
        val profileId = _uiState.value.activeProfileId ?: return
        repository.resetEntryMoves(profileId, entryKeys)
        refreshLibraryPresentation()
    }

    fun closePlayer() {
        val player = _uiState.value.screen as? StreamiaScreen.Player
        _uiState.update {
            it.copy(
                screen = if (player?.returnToSeries == true && it.seriesDetails != null) {
                    StreamiaScreen.Series(it.seriesDetails.series)
                } else StreamiaScreen.Browser,
                epg = emptyList(),
                resumePositionMs = 0,
            )
        }
    }

    fun closeDetails() { _uiState.update { it.copy(screen = StreamiaScreen.Browser, mediaDetails = null, message = null) } }
    fun closeSeries() { _uiState.update { it.copy(screen = StreamiaScreen.Browser, seriesDetails = null, message = null) } }

    fun zap(delta: Int) {
        val state = _uiState.value
        val current = (state.screen as? StreamiaScreen.Player)?.entry ?: return
        if (current.type != MediaType.Live) return
        val sameCategory = state.catalog?.entriesIn(MediaType.Live, current.categoryId).orEmpty()
        val pool = if (sameCategory.size > 1) sameCategory else state.catalog?.entriesFor(MediaType.Live).orEmpty()
        val next = pool.adjacentTo(current.key, delta) ?: return
        openPlayer(next, returnToSeries = false)
    }

    fun dismissMessage() { _uiState.update { it.copy(message = null) } }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            showLogin()
        }
    }

    private fun openPlayer(entry: MediaEntry, returnToSeries: Boolean) {
        val profileId = _uiState.value.activeProfileId
        val resume = if (profileId != null && entry.type != MediaType.Live) repository.resumePosition(profileId, entry.key) else 0L
        _uiState.update {
            it.copy(
                screen = StreamiaScreen.Player(entry, returnToSeries),
                message = null,
                epg = emptyList(),
                resumePositionMs = resume,
            )
        }
        if (entry.type == MediaType.Live) loadEpg(entry)
    }

    private fun loadMovie(movie: MediaEntry) {
        val credentials = _uiState.value.credentials ?: return
        _uiState.update { it.copy(busy = true, mediaDetails = null, screen = StreamiaScreen.MovieDetails(movie), message = null) }
        viewModelScope.launch {
            runCatching { repository.movieDetails(credentials, movie) }
                .onSuccess { details -> _uiState.update { it.copy(busy = false, mediaDetails = details) } }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            busy = false,
                            mediaDetails = MediaDetails(movie, plot = movie.plot, rating = movie.rating),
                            message = error.safeMessage(),
                        )
                    }
                }
        }
    }

    private fun loadSeries(series: MediaEntry) {
        val credentials = _uiState.value.credentials ?: return
        _uiState.update { it.copy(busy = true, message = null, seriesDetails = null, screen = StreamiaScreen.Series(series)) }
        viewModelScope.launch {
            runCatching { repository.seriesDetails(credentials, series) }
                .onSuccess { details -> _uiState.update { it.copy(busy = false, seriesDetails = details) } }
                .onFailure { error -> _uiState.update { it.copy(busy = false, message = error.safeMessage()) } }
        }
    }

    private fun loadEpg(entry: MediaEntry) {
        val state = _uiState.value
        val fromGuide = state.epgGuide?.forEntry(entry).orEmpty()
        if (fromGuide.isNotEmpty()) {
            _uiState.update { it.copy(epg = currentPrograms(fromGuide)) }
            return
        }
        val credentials = state.credentials ?: return
        viewModelScope.launch {
            runCatching { repository.shortEpg(credentials, entry.id) }
                .onSuccess { programs ->
                    val current = (_uiState.value.screen as? StreamiaScreen.Player)?.entry
                    if (current?.key == entry.key) _uiState.update { it.copy(epg = programs) }
                }
        }
    }

    private fun currentPrograms(programs: List<EpgProgram>): List<EpgProgram> {
        val now = System.currentTimeMillis() / 1000
        val currentIndex = programs.indexOfFirst { p ->
            (p.startEpochSeconds ?: Long.MIN_VALUE) <= now && (p.endEpochSeconds ?: Long.MAX_VALUE) >= now
        }.takeIf { it >= 0 } ?: programs.indexOfFirst { (it.startEpochSeconds ?: Long.MAX_VALUE) >= now }.coerceAtLeast(0)
        return programs.drop(currentIndex).take(3)
    }

    private fun refreshLibraryPresentation() {
        val state = _uiState.value
        val profileId = state.activeProfileId ?: return
        val raw = state.rawCatalog ?: state.catalog ?: return
        _uiState.update {
            it.copy(
                catalog = repository.customizedCatalog(profileId, raw),
                library = repository.library(profileId),
            )
        }
    }

    private fun showLogin() {
        _uiState.value = StreamiaUiState(booting = false, screen = StreamiaScreen.Login, profiles = repository.profiles())
    }

    private fun showCatalog(loaded: LoadedCatalog) {
        val library = repository.library(loaded.profileId)
        val customized = repository.customizedCatalog(loaded.profileId, loaded.catalog)
        _uiState.value = StreamiaUiState(
            booting = false,
            busy = false,
            screen = StreamiaScreen.Browser,
            rawCatalog = loaded.catalog,
            catalog = customized,
            credentials = loaded.credentials,
            activeProfileId = loaded.profileId,
            profiles = repository.profiles(),
            library = library,
            offline = loaded.source == CatalogSource.Cache,
            message = loaded.importSummary,
        )
    }

    private fun showError(error: Throwable) {
        _uiState.update { it.copy(busy = false, message = error.safeMessage(), profiles = repository.profiles()) }
    }

    private fun Throwable.safeMessage(): String = message?.takeIf { it.isNotBlank() } ?: "Une erreur inattendue s'est produite."
}

data class StreamiaUiState(
    val booting: Boolean = true,
    val busy: Boolean = false,
    val screen: StreamiaScreen = StreamiaScreen.Login,
    val rawCatalog: Catalog? = null,
    val catalog: Catalog? = null,
    val credentials: ServerCredentials? = null,
    val activeProfileId: String? = null,
    val profiles: List<PlaylistProfile> = emptyList(),
    val library: UserLibrarySnapshot = UserLibrarySnapshot(),
    val offline: Boolean = false,
    val message: String? = null,
    val mediaDetails: MediaDetails? = null,
    val seriesDetails: SeriesDetails? = null,
    val epg: List<EpgProgram> = emptyList(),
    val epgGuide: EpgGuide? = null,
    val epgLoading: Boolean = false,
    val resumePositionMs: Long = 0,
    val browserType: MediaType? = null,
    val browserCategoryId: String? = null,
)

sealed interface StreamiaScreen {
    data object Login : StreamiaScreen
    data object Browser : StreamiaScreen
    data object Search : StreamiaScreen
    data object Epg : StreamiaScreen
    data object Organizer : StreamiaScreen
    data class MovieDetails(val movie: MediaEntry) : StreamiaScreen
    data class Series(val series: MediaEntry) : StreamiaScreen
    data class Player(val entry: MediaEntry, val returnToSeries: Boolean = false) : StreamiaScreen
}

class StreamiaViewModelFactory(private val repository: XtreamRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = StreamiaViewModel(repository) as T
}

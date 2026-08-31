package fr.streamia.tv.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.streamia.tv.data.CatalogSource
import fr.streamia.tv.data.LoadedCatalog
import fr.streamia.tv.data.PlaylistProfile
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.data.hasSameCatalogLayoutAs
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StreamiaViewModel(private val repository: XtreamRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(StreamiaUiState())
    private val catalogLayoutMutation = Mutex()
    private val libraryMutation = Mutex()
    private var libraryMutationSequence = 0L
    val uiState: StateFlow<StreamiaUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = StreamiaUiState(
            booting = true,
            screen = StreamiaScreen.Login,
            profiles = repository.profiles(),
        )
    }

    fun finishStartup() {
        if (_uiState.value.activeProfileId == null) showLogin()
    }

    /**
     * Lance immédiatement le dernier média avec les informations sauvegardées. Le catalogue
     * complet est relu depuis le disque ensuite, sans retarder le premier affichage vidéo.
     */
    fun resumeStartup(profileId: String, entry: MediaEntry, returnToSeries: Boolean) {
        val profile = repository.profile(profileId)
        val credentials = profile?.credentialsOrNull()
        if (profile == null || credentials == null) {
            openProfile(profileId)
            return
        }
        val library = repository.library(profileId)
        val startupCatalog = Catalog(emptyList(), listOf(entry))
        _uiState.value = StreamiaUiState(
            booting = false,
            busy = false,
            catalogHydrating = true,
            screen = StreamiaScreen.Player(entry, returnToSeries),
            rawCatalog = startupCatalog,
            catalog = startupCatalog,
            credentials = credentials,
            activeProfileId = profileId,
            profiles = repository.profiles(),
            library = library,
            resumePositionMs = library.history.firstOrNull { it.entry.key == entry.key }?.positionMs ?: 0L,
        )
        viewModelScope.launch {
            runCatching { repository.openProfile(profileId) }
                .onSuccess { loaded ->
                    mergeCatalog(loaded)
                    if (loaded.source == CatalogSource.Cache) refreshSilently(profileId)
                }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(catalogHydrating = false, offline = true, message = it.safeMessage())
                    }
                }
        }
        if (entry.type == MediaType.Live) loadEpg(entry)
    }

    fun signIn(profileId: String?, profileName: String, server: String, username: String, password: String) {
        if (_uiState.value.busy) return
        val credentials = ServerCredentials(server.trim(), username.trim(), password)
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                showCatalog(repository.signIn(credentials, profileId, profileName))
            } catch (error: Throwable) {
                showError(error)
            }
        }
    }

    /**
     * Si une liste Xtream/M3U est déjà connue en cache, on l'affiche tout de suite — le cache
     * Xtream est permanent, ce n'est donc pas un état provisoire — pendant que
     * [XtreamRepository.openProfile] confirme/réconcilie les favoris en arrière-plan sans bloquer
     * l'écran derrière un « Chargement… ». Sans cache local, on retombe sur l'écran de chargement
     * classique le temps du premier chargement réseau.
     */
    fun openProfile(profileId: String) {
        if (_uiState.value.busy) return
        // Marquer busy dès l'entrée, avant même la lecture du cache : repository.cachedCatalog()
        // suspend sur Dispatchers.IO, ce qui laisse une fenêtre où un second appui (rebond
        // télécommande, ou simple impatience) repasserait le garde busy ci-dessus et déclencherait
        // un second openProfile() concurrent pour le même profil.
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val profile = repository.profile(profileId)
            val credentials = profile?.credentialsOrNull()
            val cachedCatalog = if (credentials != null) repository.cachedCatalog(profileId) else null
            if (credentials != null && cachedCatalog != null) {
                _uiState.value = StreamiaUiState(
                    booting = false,
                    busy = false,
                    screen = StreamiaScreen.Home,
                    rawCatalog = cachedCatalog,
                    catalog = cachedCatalog,
                    credentials = credentials,
                    activeProfileId = profileId,
                    profiles = repository.profiles(),
                    library = repository.library(profileId),
                )
                try {
                    mergeCatalog(repository.openProfile(profileId))
                } catch (error: Throwable) {
                    _uiState.update { state ->
                        if (state.activeProfileId == profileId) state.copy(offline = true, message = error.safeMessage())
                        else state
                    }
                }
                return@launch
            }
            _uiState.update { it.copy(busy = true, message = "Ouverture de la liste…") }
            try {
                val loaded = repository.openProfile(profileId)
                showCatalog(loaded)
                if (loaded.source == CatalogSource.Cache) refreshSilently(profileId)
            } catch (error: Throwable) {
                showError(error)
            }
        }
    }

    fun importM3u(uri: Uri, profileId: String?, profileName: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = "Analyse du fichier M3U…") }
        viewModelScope.launch {
            try {
                showCatalog(repository.importM3u(uri, profileId, profileName))
            } catch (error: Throwable) {
                showError(error)
            }
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
            try {
                showCatalog(repository.importM3uUrl(m3uUrl, profileId, profileName, xmlTvUrl, autoRefreshHours))
            } catch (error: Throwable) {
                showError(error)
            }
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
            try {
                mergeCatalog(repository.refreshProfile(profileId))
            } catch (error: Throwable) {
                showError(error)
            }
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

    fun showHome() { _uiState.update { it.copy(screen = StreamiaScreen.Home, message = null) } }
    fun showSettings() { _uiState.update { it.copy(screen = StreamiaScreen.Settings, message = null) } }
    fun showSearch() { _uiState.update { it.copy(screen = StreamiaScreen.Search, message = null) } }
    fun showOrganizer() { _uiState.update { it.copy(screen = StreamiaScreen.Organizer, message = null) } }
    fun showBrowser() { _uiState.update { it.copy(screen = StreamiaScreen.Browser, message = null) } }

    fun openSection(type: MediaType) {
        _uiState.update {
            it.copy(
                screen = StreamiaScreen.Browser,
                browserType = type,
                browserCategoryId = null,
                message = null,
            )
        }
    }

    fun rememberBrowserLocation(type: MediaType, categoryId: String?) {
        _uiState.update { it.copy(browserType = type, browserCategoryId = categoryId) }
    }

    fun rememberLastContent(entry: MediaEntry) {
        _uiState.update { state ->
            if (state.lastViewedEntry?.key == entry.key) state else state.copy(lastViewedEntry = entry)
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

    /**
     * Bascule optimiste : l'état local change tout de suite, la persistance est confirmée ensuite
     * en arrière-plan puis la bibliothèque est relue pour rester source de vérité. [libraryMutation]
     * sérialise cette confirmation — sans elle, deux appuis rapprochés sur le même favori (double
     * appui télécommande) lancent deux relectures concurrentes sur Dispatchers.IO ; rien ne garantit
     * que celle du premier appui se termine avant celle du second, et celle qui arrive en dernier
     * écrase l'état affiché même si elle correspond à un instantané antérieur, ce qui fait
     * clignoter l'icône vers une valeur déjà obsolète.
     *
     * [libraryMutationSequence] complète la sérialisation : sans lui, la relecture du *premier*
     * appui (encore en file quand le second optimiste s'applique déjà) écraserait brièvement l'état
     * affiché avec un instantané qui ne contient pas encore le second changement — un retour en
     * arrière visible avant que la relecture du second appui ne corrige. Seule la relecture dont le
     * numéro de séquence est encore le plus récent au moment où elle se termine est appliquée ; les
     * relectures intermédiaires, déjà dépassées par un appui plus récent, sont ignorées.
     */
    fun toggleEntryFavorite(entry: MediaEntry) {
        val profileId = _uiState.value.activeProfileId ?: return
        _uiState.update { state ->
            val favorites = state.library.favoriteEntries.toMutableSet().apply {
                if (!add(entry.key)) remove(entry.key)
            }
            state.copy(library = state.library.copy(favoriteEntries = favorites))
        }
        val sequence = ++libraryMutationSequence
        viewModelScope.launch {
            libraryMutation.withLock {
                runCatching {
                    withContext(Dispatchers.IO) {
                        repository.toggleEntryFavorite(profileId, entry)
                        repository.library(profileId)
                    }
                }.onSuccess { library ->
                    if (sequence != libraryMutationSequence) return@onSuccess
                    _uiState.update { state -> if (state.activeProfileId == profileId) state.copy(library = library) else state }
                }
            }
        }
    }

    fun toggleCategoryFavorite(category: MediaCategory) {
        val profileId = _uiState.value.activeProfileId ?: return
        _uiState.update { state ->
            val favorites = state.library.favoriteCategories.toMutableSet().apply {
                if (!add(category.key)) remove(category.key)
            }
            state.copy(library = state.library.copy(favoriteCategories = favorites))
        }
        val sequence = ++libraryMutationSequence
        viewModelScope.launch {
            libraryMutation.withLock {
                runCatching {
                    withContext(Dispatchers.IO) {
                        repository.toggleCategoryFavorite(profileId, category)
                        repository.library(profileId)
                    }
                }.onSuccess { library ->
                    if (sequence != libraryMutationSequence) return@onSuccess
                    _uiState.update { state -> if (state.activeProfileId == profileId) state.copy(library = library) else state }
                }
            }
        }
    }

    fun recordPlayback(entry: MediaEntry, positionMs: Long, durationMs: Long) {
        val profileId = _uiState.value.activeProfileId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.recordPlayback(profileId, entry, positionMs, durationMs)
        }
    }

    fun clearHistory() {
        val profileId = _uiState.value.activeProfileId ?: return
        repository.clearHistory(profileId)
        refreshLibraryPresentation()
    }

    fun setCategoryOrder(type: MediaType, categoryKeys: List<String>) {
        val profileId = _uiState.value.activeProfileId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            catalogLayoutMutation.withLock {
                repository.setCategoryOrder(profileId, type, categoryKeys)
            }
        }
    }

    fun moveEntries(entryKeys: Set<String>, targetCategoryId: String) {
        val profileId = _uiState.value.activeProfileId ?: return
        if (entryKeys.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            catalogLayoutMutation.withLock {
                repository.moveEntries(profileId, entryKeys, targetCategoryId)
            }
        }
    }

    fun resetEntryMoves(entryKeys: Set<String>) {
        val profileId = _uiState.value.activeProfileId ?: return
        if (entryKeys.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            catalogLayoutMutation.withLock {
                repository.resetEntryMoves(profileId, entryKeys)
            }
        }
    }

    fun closeOrganizer() {
        showSettings()
        val profileId = _uiState.value.activeProfileId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            catalogLayoutMutation.withLock {
                val presentation = libraryPresentation(profileId)
                applyLibraryPresentation(profileId, presentation)
            }
        }
    }

    fun closePlayer() {
        val profileId = _uiState.value.activeProfileId
        val player = _uiState.value.screen as? StreamiaScreen.Player
        _uiState.update {
            it.copy(
                screen = when {
                    it.catalogHydrating -> StreamiaScreen.Home
                    player?.returnToSeries == true && it.seriesDetails != null -> StreamiaScreen.Series(it.seriesDetails.series)
                    else -> StreamiaScreen.Browser
                },
                epg = emptyList(),
                resumePositionMs = 0,
            )
        }
        if (profileId != null) refreshLibrarySnapshot(profileId)
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
                lastViewedEntry = entry,
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
        viewModelScope.launch {
            val presentation = withContext(Dispatchers.IO) { libraryPresentation(profileId) }
            applyLibraryPresentation(profileId, presentation)
        }
    }

    private fun refreshLibrarySnapshot(profileId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val library = repository.library(profileId)
            _uiState.update { state -> if (state.activeProfileId == profileId) state.copy(library = library) else state }
        }
    }

    private fun libraryPresentation(profileId: String): Pair<Catalog?, UserLibrarySnapshot> {
        val state = _uiState.value
        val raw = state.rawCatalog ?: state.catalog
        return (raw?.let { repository.customizedCatalog(profileId, it) }) to repository.library(profileId)
    }

    private fun applyLibraryPresentation(profileId: String, presentation: Pair<Catalog?, UserLibrarySnapshot>) {
        _uiState.update { state ->
            if (state.activeProfileId != profileId) state
            else state.copy(catalog = presentation.first ?: state.catalog, library = presentation.second)
        }
    }

    private suspend fun refreshSilently(profileId: String) {
        try {
            mergeCatalog(repository.refreshProfile(profileId))
        } catch (_: Throwable) {
            if (_uiState.value.activeProfileId == profileId) {
                _uiState.update { state -> state.copy(offline = true, busy = false) }
            }
        }
    }

    private suspend fun mergeCatalog(loaded: LoadedCatalog) {
        if (_uiState.value.activeProfileId != loaded.profileId) return
        var presentation = repository.prepareCatalogPresentation(loaded.profileId, loaded.catalog)

        while (true) {
            var committed = false
            var layoutChanged = false
            _uiState.update { state ->
                committed = false
                layoutChanged = false
                when {
                    state.activeProfileId != loaded.profileId -> state
                    !state.library.hasSameCatalogLayoutAs(presentation.library) -> {
                        layoutChanged = true
                        state
                    }
                    else -> {
                        committed = true
                        state.copy(
                            booting = false,
                            busy = false,
                            catalogHydrating = false,
                            rawCatalog = loaded.catalog,
                            catalog = presentation.catalog,
                            credentials = loaded.credentials,
                            profiles = presentation.profiles,
                            // Favoris et progression peuvent changer pendant le calcul : conserver
                            // l'instantané courant empêche l'actualisation de les faire régresser.
                            library = state.library,
                            offline = loaded.source == CatalogSource.Cache,
                            epgGuide = null,
                            message = loaded.importSummary ?: state.message,
                        )
                    }
                }
            }
            if (committed || !layoutChanged) return

            val latest = _uiState.value
            if (latest.activeProfileId != loaded.profileId) return
            presentation = repository.prepareCatalogPresentation(
                profileId = loaded.profileId,
                catalog = loaded.catalog,
                librarySnapshot = latest.library,
            )
        }
    }

    private fun showLogin() {
        _uiState.value = StreamiaUiState(booting = false, screen = StreamiaScreen.Login, profiles = repository.profiles())
    }

    private suspend fun showCatalog(loaded: LoadedCatalog) {
        val presentation = repository.prepareCatalogPresentation(loaded.profileId, loaded.catalog)
        _uiState.value = StreamiaUiState(
            booting = false,
            busy = false,
            screen = StreamiaScreen.Home,
            rawCatalog = loaded.catalog,
            catalog = presentation.catalog,
            credentials = loaded.credentials,
            activeProfileId = loaded.profileId,
            profiles = presentation.profiles,
            library = presentation.library,
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
    val catalogHydrating: Boolean = false,
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
    val lastViewedEntry: MediaEntry? = null,
)

sealed interface StreamiaScreen {
    data object Login : StreamiaScreen
    data object Home : StreamiaScreen
    data object Browser : StreamiaScreen
    data object Settings : StreamiaScreen
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

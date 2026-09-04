package fr.streamia.tv.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.streamia.tv.BuildConfig
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.CatalogSource
import fr.streamia.tv.data.EpgCacheMetadata
import fr.streamia.tv.data.LoadedCatalog
import fr.streamia.tv.data.PlaylistProfile
import fr.streamia.tv.data.UpdateCheckResult
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.data.hasSameCatalogLayoutAs
import fr.streamia.tv.data.XtreamRepository
import fr.streamia.tv.domain.AccountInfo
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgGuide
import fr.streamia.tv.domain.EpgNowContext
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaDetails
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.SeriesDetails
import fr.streamia.tv.domain.SeriesEpisode
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.adjacentTo
import fr.streamia.tv.domain.epgNowContextAt
import fr.streamia.tv.domain.withTimeOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

class StreamiaViewModel(private val repository: XtreamRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(StreamiaUiState())
    private val catalogLayoutMutation = Mutex()
    private val libraryMutation = Mutex()
    private var libraryMutationSequence = 0L
    private var connectionTestSequence = 0L
    private var epgGuideLoadSequence = 0L
    private var epgSyncJob: Job? = null
    private var epgSyncProfileId: String? = null
    private var epgChannelJob: Job? = null
    private var epgTickerJob: Job? = null
    // Lu/écrit uniquement depuis le thread principal (appelants Compose) : évite de relancer une
    // requête SQLite déjà en vol pour la même page quand plusieurs recompositions déclenchent le
    // même chargement (ex. sélection rapide de catégories, LaunchedEffect qui se relance).
    private val categoryLoadsInFlight = mutableSetOf<String>()
    val uiState: StateFlow<StreamiaUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = StreamiaUiState(
            booting = true,
            screen = StreamiaScreen.Login,
            profiles = repository.profiles(),
            appSettings = repository.appSettings(),
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
            appSettings = repository.appSettings(),
            resumePositionMs = library.history.firstOrNull { it.entry.key == entry.key }?.positionMs ?: 0L,
        )
        viewModelScope.launch {
            // Catalogue déjà résolu (favoris/ordre déjà appliqués) persisté lors d'une précédente
            // réconciliation réussie pour ce profil : s'il est encore valide pour l'organisation
            // courante, il permet de sortir de catalogHydrating immédiatement, sans attendre que
            // openProfile()+mergeCatalog() ci-dessous refassent tout le travail. Ce chemin lent
            // continue de tourner derrière pour rattraper un éventuel changement côté fournisseur
            // (nouvelles/anciennes chaînes) depuis la dernière fois : c'est lui qui a le dernier mot.
            val resolved = runCatching { repository.resolvedCatalogIfLayoutUnchanged(profileId, library) }.getOrNull()
            if (resolved != null) {
                _uiState.update { state ->
                    if (state.activeProfileId == profileId) {
                        state.copy(catalogHydrating = false, rawCatalog = resolved, catalog = resolved)
                    } else state
                }
            }
            runCatching { repository.openProfile(profileId) }
                .onSuccess { loaded ->
                    mergeCatalog(loaded)
                    if (loaded.source == CatalogSource.Cache) refreshSilently(profileId)
                }
                .onFailure {
                    _uiState.update { state ->
                        if (state.activeProfileId == profileId) {
                            state.copy(catalogHydrating = false, offline = true, message = it.safeMessage())
                        } else state
                    }
                }
        }
        if (entry.type == MediaType.Live) {
            loadEpg(entry)
            startEpgTicker(entry)
        } else {
            epgChannelJob?.cancel()
            epgTickerJob?.cancel()
        }
    }

    fun signIn(profileId: String?, profileName: String, server: String, username: String, password: String) {
        if (_uiState.value.busy || _uiState.value.testingConnection) return
        val credentials = ServerCredentials(server.trim(), username.trim(), password)
        _uiState.update { it.copy(busy = true, message = null, testSucceeded = false) }
        viewModelScope.launch {
            try {
                showCatalog(repository.signIn(credentials, profileId, profileName))
            } catch (error: Throwable) {
                showError(error)
            }
        }
    }

    /**
     * Contrôle rapide des identifiants Xtream (un seul aller-retour, sans charger le catalogue)
     * avant d'enregistrer la liste. N'écrit rien sur disque et ne change pas d'écran : c'est une
     * simple vérification que l'utilisateur peut lancer depuis le formulaire.
     *
     * Utilise un état [StreamiaUiState.testingConnection] distinct de `busy` plutôt que de le
     * réutiliser : `busy` déclenche l'indicateur « Connexion au serveur… / chargement de la liste »
     * de [XtreamForm], qui serait trompeur ici puisqu'aucun catalogue n'est chargé. Les deux
     * actions restent mutuellement exclusives : chacune vérifie l'état de l'autre avant de démarrer,
     * et l'écran désactive les deux boutons ainsi que « Retour » tant que l'une des deux tourne —
     * comme pour [openProfile], l'état est marqué avant tout point de suspension pour fermer la
     * fenêtre entre l'appui et la recomposition qui désactive le bouton.
     *
     * [connectionTestSequence] écarte un résultat devenu obsolète si un second test démarre avant
     * que le premier n'ait fini (même logique que [libraryMutationSequence] pour les favoris).
     */
    fun testConnection(server: String, username: String, password: String) {
        if (_uiState.value.busy || _uiState.value.testingConnection) return
        val credentials = ServerCredentials(server.trim(), username.trim(), password)
        val sequence = ++connectionTestSequence
        _uiState.update { it.copy(testingConnection = true, message = null, testSucceeded = false) }
        viewModelScope.launch {
            try {
                val account = repository.testConnection(credentials)
                if (sequence != connectionTestSequence) return@launch
                _uiState.update {
                    it.copy(testingConnection = false, message = account.toConnectionSuccessMessage(), testSucceeded = true)
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (sequence != connectionTestSequence) return@launch
                _uiState.update { it.copy(testingConnection = false, message = error.safeMessage(), testSucceeded = false) }
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
                    mergeCatalog(repository.openProfile(profileId, knownCache = cachedCatalog))
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

    fun checkForUpdate() {
        if (_uiState.value.updateChecking) return
        _uiState.update { it.copy(updateChecking = true, updateCheck = null) }
        viewModelScope.launch {
            val result = repository.checkForUpdate(BuildConfig.VERSION_NAME)
            _uiState.update { it.copy(updateChecking = false, updateCheck = result) }
        }
    }

    fun dismissUpdateCheck() { _uiState.update { it.copy(updateCheck = null) } }

    suspend fun exportBackup(): String = repository.exportBackup(_uiState.value.activeProfileId)

    /**
     * Restaure réglages + préférences du profil actif, puis relit les stores pour que l'interface
     * reflète immédiatement le contenu importé — [libraryPresentation] recalcule aussi le catalogue
     * personnalisé (ordre des catégories, déplacements), pas seulement le [StreamiaUiState.library]
     * brut, sans quoi le Browser garderait l'ancienne disposition jusqu'à une action qui la relit
     * incidemment.
     */
    suspend fun importBackup(json: String): String {
        val profileId = _uiState.value.activeProfileId
        val message = repository.importBackup(profileId, json)
        _uiState.update { it.copy(appSettings = repository.appSettings()) }
        if (profileId != null) {
            val presentation = withContext(Dispatchers.IO) { libraryPresentation(profileId) }
            applyLibraryPresentation(profileId, presentation)
        }
        return message
    }

    fun openEntry(entry: MediaEntry) {
        when {
            entry.type == MediaType.Live -> openPlayer(entry, returnToSeries = false)
            entry.type == MediaType.Movie -> loadMovie(entry)
            entry.type == MediaType.Series && !entry.playable -> loadSeries(entry)
            else -> openPlayer(entry, returnToSeries = entry.type == MediaType.Series)
        }
    }

    /**
     * Reprend directement la lecture d'un contenu VOD (rangée « Reprendre la lecture ») sans passer
     * par l'écran de détails que [openEntry] ouvrirait pour un film. Les épisodes de série sont déjà
     * lisibles directement via [openEntry] ; seul le cas Film nécessite ce raccourci.
     */
    fun resumePlayback(entry: MediaEntry) {
        if (entry.type == MediaType.Movie) openPlayer(entry, returnToSeries = false) else openEntry(entry)
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

    /**
     * Interroge directement l'index SQLite plutôt que [fr.streamia.tv.domain.Catalog.search], qui
     * ne voit que les entrées déjà matérialisées en mémoire : sous chargement paresseux, un
     * contenu jamais parcouru serait invisible à une recherche purement en mémoire.
     */
    suspend fun searchCatalog(query: String, type: MediaType?): List<MediaEntry> {
        val state = _uiState.value
        val profileId = state.activeProfileId ?: return emptyList()
        // Une catégorie verrouillée est traitée comme masquée pour la recherche : il n'y a pas
        // d'écran de saisie de code depuis un résultat de recherche, donc son contenu ne doit tout
        // simplement pas apparaître tant que le code n'a pas été saisi cette session ailleurs.
        val excludedCategoryKeys = if (state.appSettings.parentalControlEnabled && !state.parentalUnlocked) {
            state.library.hiddenCategories + state.library.lockedCategories
        } else {
            state.library.hiddenCategories
        }
        val excludedCategoryIdsByType = state.catalog
            ?.categories
            .orEmpty()
            .filter { it.key in excludedCategoryKeys }
            .groupBy(MediaCategory::type)
            .mapValues { (_, categories) -> categories.mapTo(mutableSetOf(), MediaCategory::id) }

        return runCatching { repository.search(profileId, query, type) }
            .getOrDefault(emptyList())
            .filterNot { entry ->
                entry.key in state.library.hiddenEntries ||
                    entry.categoryId in excludedCategoryIdsByType[entry.type].orEmpty()
            }
    }

    fun showHome() {
        _uiState.update {
            it.copy(
                screen = StreamiaScreen.Home,
                message = null,
                epgGuide = null,
                epgAvailableDates = emptyList(),
                epgSelectedDate = null,
                epgLoading = false,
            )
        }
    }
    fun showSettings() { _uiState.update { it.copy(screen = StreamiaScreen.Settings, message = null) } }
    fun showTools() { _uiState.update { it.copy(screen = StreamiaScreen.Tools, message = null) } }
    fun showAbout() { _uiState.update { it.copy(screen = StreamiaScreen.About, message = null) } }

    suspend fun cacheSizeBytes(): Long = repository.cacheSizeBytes()
    suspend fun epgCacheSizeBytes(): Long = repository.epgCacheSizeBytes()
    fun showParentalControl() { _uiState.update { it.copy(screen = StreamiaScreen.ParentalControl, message = null) } }
    fun showSearch() { _uiState.update { it.copy(screen = StreamiaScreen.Search, message = null) } }

    fun toggleLivePreview() {
        updateAppSettings { it.copy(livePreviewEnabled = !it.livePreviewEnabled) }
    }

    fun cycleLivePreviewDelay() {
        updateAppSettings { it.copy(livePreviewDelayMs = it.nextLivePreviewDelayMs()) }
    }

    fun cycleVodSeekStep() {
        updateAppSettings { it.copy(vodSeekStepSeconds = it.nextVodSeekStepSeconds()) }
    }

    fun cycleVideoAspect() {
        updateAppSettings { it.copy(videoAspect = it.nextVideoAspect()) }
    }

    fun cycleBufferMode() {
        updateAppSettings { it.copy(bufferMode = it.nextBufferMode()) }
    }

    fun cycleLiveStreamFormat() {
        updateAppSettings { it.copy(liveStreamFormat = it.nextLiveStreamFormat()) }
    }

    fun cycleLiveChannelSortOrder() {
        updateAppSettings { it.copy(liveChannelSortOrder = it.nextLiveChannelSortOrder()) }
    }

    fun cycleVodSortOrder() {
        updateAppSettings { it.copy(vodSortOrder = it.nextVodSortOrder()) }
    }

    fun cycleEpgTimeOffset() {
        updateAppSettings { it.copy(epgTimeOffsetHours = it.nextEpgTimeOffsetHours()) }
    }

    fun toggleAutoPlayNextEpisode() {
        updateAppSettings { it.copy(autoPlayNextEpisode = !it.autoPlayNextEpisode) }
    }

    fun cycleSubtitleSizeScale() {
        updateAppSettings { it.copy(subtitleSizeScale = it.nextSubtitleSizeScale()) }
    }

    fun toggleSubtitleBackground() {
        updateAppSettings { it.copy(subtitleBackgroundEnabled = !it.subtitleBackgroundEnabled) }
    }

    /** Appelé depuis le lecteur (fin de lecture, ou bouton « Lire maintenant » du bandeau). */
    fun playNextEpisode() {
        val state = _uiState.value
        val series = state.seriesDetails ?: return
        val current = (state.screen as? StreamiaScreen.Player)?.entry ?: return
        val next = series.nextEpisode(current.id) ?: return
        playEpisode(series.series, next)
    }

    private fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        val settings = repository.updateAppSettings(transform)
        _uiState.update { it.copy(appSettings = settings) }
    }
    /**
     * L'organisateur permet de réordonner des catégories et de déplacer des entrées entre elles
     * pour n'importe quel type qu'on y sélectionne : contrairement au navigateur, il a donc besoin
     * des listes complètes des trois types, pas seulement de la catégorie visitée.
     */
    fun showOrganizer() {
        _uiState.update { it.copy(screen = StreamiaScreen.Organizer, message = null) }
        MediaType.entries.forEach(::ensureSectionLoaded)
    }
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

    /**
     * Charge la première page d'une catégorie depuis SQLite si elle n'est pas déjà matérialisée.
     * Le catalogue affiché reste léger (catégories + comptes + quelques entrées de contexte) tant
     * que l'utilisateur n'a pas réellement ouvert une catégorie : c'est cet appel — déclenché par
     * [fr.streamia.tv.ui.BrowserScreen] à la sélection — qui va chercher ses chaînes/films/séries.
     * `Catalog.ALL_CATEGORY_ID` (« Tout ») est une catégorie comme une autre pour
     * [XtreamRepository.loadCategoryPage] : elle charge simplement les premières entrées du type
     * sans filtrer par category_id.
     */
    fun ensureCategoryLoaded(type: MediaType, categoryId: String) {
        val profileId = _uiState.value.activeProfileId ?: return
        val catalog = _uiState.value.catalog ?: return
        if (catalog.isCategoryLoaded(type, categoryId)) return
        loadCategoryPage(profileId, type, categoryId, offset = 0)
    }

    /**
     * Charge la page suivante d'une catégorie déjà ouverte, à appeler quand la liste/grille
     * approche de sa fin. L'offset se déduit du nombre d'entrées déjà matérialisées pour cette
     * catégorie : les pages s'enchaînent sans trou tant qu'aucun appel ne saute une page.
     */
    fun loadMoreInCategory(type: MediaType, categoryId: String) {
        val profileId = _uiState.value.activeProfileId ?: return
        val catalog = _uiState.value.catalog ?: return
        val loaded = catalog.entriesIn(type, categoryId).size
        if (loaded > 0 && loaded >= catalog.countIn(type, categoryId)) return
        loadCategoryPage(profileId, type, categoryId, offset = loaded)
    }

    private fun loadCategoryPage(profileId: String, type: MediaType, categoryId: String, offset: Int) {
        val loadKey = "$profileId:${Catalog.categoryKey(type, categoryId)}:$offset"
        if (!categoryLoadsInFlight.add(loadKey)) return
        viewModelScope.launch {
            try {
                val page = runCatching { repository.loadCategoryPage(profileId, type, categoryId, offset) }.getOrNull() ?: return@launch
                if (page.entries.isEmpty() && offset > 0) return@launch
                _uiState.update { state ->
                    if (state.activeProfileId != profileId) return@update state
                    val rawBase = state.rawCatalog ?: state.catalog ?: return@update state
                    val mergedRaw = rawBase.withMaterializedEntries(page.entries, type, categoryId)
                    val mergedCatalog = repository.customizedCatalog(profileId, mergedRaw)
                    state.copy(rawCatalog = mergedRaw, catalog = mergedCatalog)
                }
            } finally {
                categoryLoadsInFlight.remove(loadKey)
            }
        }
    }

    /**
     * Hydrate un type entier en une requête plutôt que catégorie par catégorie. Utilisé pour le
     * Live (assez petit pour être chargé proactivement juste après l'ouverture du profil, ce qui
     * évite de patcher séparément le zapping, le saut par numéro de chaîne et le guide EPG — tous
     * lisent [fr.streamia.tv.domain.Catalog.entriesFor]/[fr.streamia.tv.domain.Catalog.entriesIn]
     * directement) et pour Films/Séries à l'ouverture de l'organisateur, qui a besoin des listes
     * complètes pour réordonner des catégories ou déplacer des entrées entre elles. Un catalogue
     * déjà entièrement en mémoire (venant d'une connexion ou d'un rafraîchissement réseau) n'a pas
     * de métadonnées légères : [fr.streamia.tv.domain.Catalog.isCategoryLoaded] y répond toujours
     * vrai et cette fonction ne fait rien.
     */
    private fun ensureSectionLoaded(type: MediaType, forceEpgSync: Boolean = false) {
        val profileId = _uiState.value.activeProfileId ?: return
        val catalog = _uiState.value.catalog ?: return
        if (catalog.isCategoryLoaded(type, Catalog.ALL_CATEGORY_ID)) {
            if (type == MediaType.Live) startEpgBackgroundSync(force = forceEpgSync)
            return
        }
        val loadKey = "$profileId:section:${type.name}"
        if (!categoryLoadsInFlight.add(loadKey)) return
        viewModelScope.launch {
            try {
                val section = runCatching { repository.loadSection(profileId, type) }.getOrNull() ?: return@launch
                _uiState.update { state ->
                    if (state.activeProfileId != profileId) return@update state
                    val rawBase = state.rawCatalog ?: state.catalog ?: return@update state
                    val mergedRaw = rawBase.withFullSectionMaterialized(section, type)
                    val mergedCatalog = repository.customizedCatalog(profileId, mergedRaw)
                    state.copy(rawCatalog = mergedRaw, catalog = mergedCatalog)
                }
                if (type == MediaType.Live) startEpgBackgroundSync(force = forceEpgSync)
            } finally {
                categoryLoadsInFlight.remove(loadKey)
            }
        }
    }

    fun rememberLastContent(entry: MediaEntry) {
        _uiState.update { state ->
            if (state.lastViewedEntry?.key == entry.key) state else state.copy(lastViewedEntry = entry)
        }
    }

    fun showEpg() {
        if (_uiState.value.activeProfileId == null) return
        _uiState.update { it.copy(screen = StreamiaScreen.Epg, epgLoading = true, message = null) }
        ensureSectionLoaded(MediaType.Live)
        loadEpgGuideFromCache(_uiState.value.epgSelectedDate)
        startEpgBackgroundSync()
    }

    fun selectEpgDate(date: LocalDate) {
        _uiState.update { it.copy(epgSelectedDate = date, epgGuide = null, epgLoading = true, message = null) }
        loadEpgGuideFromCache(date)
    }

    fun reloadEpg() {
        _uiState.update { it.copy(epgLoading = true, message = null) }
        startEpgBackgroundSync(force = true)
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

    fun toggleEntryHidden(entry: MediaEntry) {
        val profileId = _uiState.value.activeProfileId ?: return
        _uiState.update { state ->
            val hidden = state.library.hiddenEntries.toMutableSet().apply {
                if (!add(entry.key)) remove(entry.key)
            }
            state.copy(library = state.library.copy(hiddenEntries = hidden))
        }
        val sequence = ++libraryMutationSequence
        viewModelScope.launch {
            libraryMutation.withLock {
                runCatching {
                    withContext(Dispatchers.IO) {
                        repository.toggleEntryHidden(profileId, entry)
                        repository.library(profileId)
                    }
                }.onSuccess { library ->
                    if (sequence != libraryMutationSequence) return@onSuccess
                    _uiState.update { state -> if (state.activeProfileId == profileId) state.copy(library = library) else state }
                }
            }
        }
    }

    fun toggleCategoryHidden(category: MediaCategory) {
        val profileId = _uiState.value.activeProfileId ?: return
        _uiState.update { state ->
            val hidden = state.library.hiddenCategories.toMutableSet().apply {
                if (!add(category.key)) remove(category.key)
            }
            state.copy(library = state.library.copy(hiddenCategories = hidden))
        }
        val sequence = ++libraryMutationSequence
        viewModelScope.launch {
            libraryMutation.withLock {
                runCatching {
                    withContext(Dispatchers.IO) {
                        repository.toggleCategoryHidden(profileId, category)
                        repository.library(profileId)
                    }
                }.onSuccess { library ->
                    if (sequence != libraryMutationSequence) return@onSuccess
                    _uiState.update { state -> if (state.activeProfileId == profileId) state.copy(library = library) else state }
                }
            }
        }
    }

    fun toggleCategoryLocked(category: MediaCategory) {
        val profileId = _uiState.value.activeProfileId ?: return
        _uiState.update { state ->
            val locked = state.library.lockedCategories.toMutableSet().apply {
                if (!add(category.key)) remove(category.key)
            }
            state.copy(library = state.library.copy(lockedCategories = locked))
        }
        val sequence = ++libraryMutationSequence
        viewModelScope.launch {
            libraryMutation.withLock {
                runCatching {
                    withContext(Dispatchers.IO) {
                        repository.toggleCategoryLocked(profileId, category)
                        repository.library(profileId)
                    }
                }.onSuccess { library ->
                    if (sequence != libraryMutationSequence) return@onSuccess
                    _uiState.update { state -> if (state.activeProfileId == profileId) state.copy(library = library) else state }
                }
            }
        }
    }

    fun toggleEntryWatched(entry: MediaEntry) {
        val profileId = _uiState.value.activeProfileId ?: return
        _uiState.update { state ->
            val watched = state.library.watchedEntries.toMutableSet().apply {
                if (!add(entry.key)) remove(entry.key)
            }
            state.copy(library = state.library.copy(watchedEntries = watched))
        }
        val sequence = ++libraryMutationSequence
        viewModelScope.launch {
            libraryMutation.withLock {
                runCatching {
                    withContext(Dispatchers.IO) {
                        repository.toggleEntryWatched(profileId, entry)
                        repository.library(profileId)
                    }
                }.onSuccess { library ->
                    if (sequence != libraryMutationSequence) return@onSuccess
                    _uiState.update { state -> if (state.activeProfileId == profileId) state.copy(library = library) else state }
                }
            }
        }
    }

    /** Enregistre un nouveau code parental et active le verrouillage — déverrouille aussi la session en cours puisque c'est l'utilisateur qui vient de le saisir. */
    fun setParentalPin(pin: String) {
        val settings = repository.setParentalPin(pin)
        _uiState.update { it.copy(appSettings = settings, parentalUnlocked = true) }
    }

    fun disableParentalControl() {
        val settings = repository.clearParentalPin()
        _uiState.update { it.copy(appSettings = settings, parentalUnlocked = false) }
    }

    /** Code correct : déverrouille le contenu verrouillé pour le reste de la session (jusqu'à la fermeture de l'app). */
    fun verifyParentalPin(pin: String): Boolean {
        val correct = repository.verifyParentalPin(pin)
        if (correct) _uiState.update { it.copy(parentalUnlocked = true) }
        return correct
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

    fun clearHistory(type: MediaType? = null) {
        val profileId = _uiState.value.activeProfileId ?: return
        repository.clearHistory(profileId, type)
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
        showTools()
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
                epg = EpgNowContext(),
                resumePositionMs = 0,
            )
        }
        epgChannelJob?.cancel()
        epgTickerJob?.cancel()
        if (profileId != null) refreshLibrarySnapshot(profileId)
    }

    fun closeDetails() { _uiState.update { it.copy(screen = StreamiaScreen.Browser, mediaDetails = null, message = null) } }
    fun closeSeries() { _uiState.update { it.copy(screen = StreamiaScreen.Browser, seriesDetails = null, message = null) } }

    fun zap(delta: Int) {
        val state = _uiState.value
        val current = (state.screen as? StreamiaScreen.Player)?.entry ?: return
        if (current.type != MediaType.Live) return
        // Le repli sur allLive peut faire sauter le zapping dans une autre catégorie que celle en
        // cours : si elle est verrouillée et pas encore déverrouillée cette session, elle doit être
        // exclue comme si elle était masquée — il n'y a pas d'écran de code pendant le zapping.
        val lockedCategoryIds = if (state.appSettings.parentalControlEnabled && !state.parentalUnlocked) {
            state.catalog?.categoriesFor(MediaType.Live)
                .orEmpty()
                .filter { it.key in state.library.lockedCategories }
                .mapTo(mutableSetOf(), MediaCategory::id)
        } else {
            emptySet()
        }
        val sameCategory = state.catalog?.entriesIn(MediaType.Live, current.categoryId)
            .orEmpty()
            .filterNot { it.key in state.library.hiddenEntries }
        val allLive = state.catalog?.entriesFor(MediaType.Live)
            .orEmpty()
            .filterNot { it.key in state.library.hiddenEntries || it.categoryId in lockedCategoryIds }
        val pool = if (sameCategory.size > 1) sameCategory else allLive
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
                epg = EpgNowContext(),
                epgGuide = null,
                epgAvailableDates = emptyList(),
                epgSelectedDate = null,
                resumePositionMs = resume,
                lastViewedEntry = entry,
            )
        }
        if (entry.type == MediaType.Live) {
            loadEpg(entry)
            startEpgTicker(entry)
        } else {
            epgChannelJob?.cancel()
            epgTickerJob?.cancel()
        }
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
        val profileId = state.activeProfileId ?: return
        val credentials = state.credentials ?: return
        val offsetHours = state.appSettings.epgTimeOffsetHours
        epgChannelJob?.cancel()
        epgChannelJob = viewModelScope.launch {
            val now = System.currentTimeMillis() / 1000
            val cached = runCatching {
                repository.cachedEpgNow(profileId, entry, now, offsetHours)
            }.getOrNull()
            if (cached != null && !cached.isEmpty) {
                updatePlayerEpgIfCurrent(entry, cached)
                return@launch
            }

            val programs = runCatching { repository.shortEpg(credentials, entry.id) }.getOrNull().orEmpty()
            if (programs.isEmpty()) return@launch
            val selected = programs.epgNowContextAt(nowEpochSeconds = now, offsetHours = offsetHours)
            val fallback = if (!selected.isEmpty) selected else EpgNowContext(
                current = programs.getOrNull(0)?.withTimeOffset(offsetHours),
                next = programs.getOrNull(1)?.withTimeOffset(offsetHours),
            )
            updatePlayerEpgIfCurrent(entry, fallback)
        }
    }

    private fun updatePlayerEpgIfCurrent(entry: MediaEntry, context: EpgNowContext) {
        val current = (_uiState.value.screen as? StreamiaScreen.Player)?.entry
        if (current?.key == entry.key) _uiState.update { it.copy(epg = context) }
    }

    private fun startEpgTicker(entry: MediaEntry) {
        epgTickerJob?.cancel()
        epgTickerJob = viewModelScope.launch {
            while (isActive) {
                delay(EPG_PLAYER_REFRESH_MS)
                val state = _uiState.value
                val current = (state.screen as? StreamiaScreen.Player)?.entry ?: return@launch
                if (current.key != entry.key || current.type != MediaType.Live) return@launch
                val profileId = state.activeProfileId ?: return@launch
                val cached = runCatching {
                    repository.cachedEpgNow(
                        profileId = profileId,
                        entry = current,
                        nowEpochSeconds = System.currentTimeMillis() / 1000,
                        offsetHours = state.appSettings.epgTimeOffsetHours,
                    )
                }.getOrNull()
                if (cached != null && !cached.isEmpty) updatePlayerEpgIfCurrent(current, cached)
            }
        }
    }

    private fun startEpgBackgroundSync(force: Boolean = false) {
        val state = _uiState.value
        val profileId = state.activeProfileId ?: return
        val credentials = state.credentials ?: return
        if (state.catalogHydrating) return
        val liveEntries = state.catalog?.entriesFor(MediaType.Live).orEmpty()
        if (liveEntries.isEmpty()) return
        if (epgSyncJob?.isActive == true && epgSyncProfileId == profileId) return

        epgSyncProfileId = profileId
        epgSyncJob = viewModelScope.launch {
            try {
                repository.refreshEpg(
                    profileId = profileId,
                    credentials = credentials,
                    liveEntries = liveEntries,
                    force = force,
                )
                if (_uiState.value.activeProfileId != profileId) return@launch
                val player = (_uiState.value.screen as? StreamiaScreen.Player)?.entry
                if (player?.type == MediaType.Live) loadEpg(player)
                if (_uiState.value.screen is StreamiaScreen.Epg) {
                    loadEpgGuideFromCache(_uiState.value.epgSelectedDate)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (_uiState.value.activeProfileId == profileId && _uiState.value.screen is StreamiaScreen.Epg) {
                    _uiState.update { it.copy(epgLoading = false, message = error.safeMessage()) }
                }
            }
        }
    }

    private fun loadEpgGuideFromCache(preferredDate: LocalDate?) {
        val state = _uiState.value
        val profileId = state.activeProfileId ?: return
        val offsetHours = state.appSettings.epgTimeOffsetHours
        val sequence = ++epgGuideLoadSequence
        viewModelScope.launch {
            val metadata = runCatching { repository.epgMetadata(profileId) }.getOrNull()
            if (sequence != epgGuideLoadSequence || _uiState.value.activeProfileId != profileId) return@launch
            if (metadata == null || metadata.programCount <= 0) {
                if (_uiState.value.screen is StreamiaScreen.Epg) {
                    _uiState.update { it.copy(epgGuide = null, epgAvailableDates = emptyList(), epgLoading = true) }
                }
                return@launch
            }

            val dates = epgDates(metadata, offsetHours)
            if (dates.isEmpty()) {
                _uiState.update { it.copy(epgGuide = null, epgAvailableDates = emptyList(), epgLoading = false) }
                return@launch
            }
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val date = preferredDate?.takeIf { it in dates }
                ?: dates.firstOrNull { it == today }
                ?: dates.first()
            val dayStart = date.atStartOfDay(zone).toEpochSecond()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
            val guide = runCatching {
                repository.cachedEpgGuide(
                    profileId = profileId,
                    displayStartEpochSeconds = dayStart,
                    displayEndEpochSeconds = dayEnd,
                    offsetHours = offsetHours,
                )
            }.getOrElse {
                if (sequence == epgGuideLoadSequence && _uiState.value.screen is StreamiaScreen.Epg) {
                    _uiState.update { stateNow -> stateNow.copy(epgLoading = false, message = it.safeMessage()) }
                }
                return@launch
            }
            if (sequence != epgGuideLoadSequence || _uiState.value.activeProfileId != profileId) return@launch
            if (_uiState.value.screen is StreamiaScreen.Epg) {
                _uiState.update {
                    it.copy(
                        epgGuide = guide,
                        epgAvailableDates = dates,
                        epgSelectedDate = date,
                        epgLoading = false,
                    )
                }
            }
        }
    }

    private fun epgDates(metadata: EpgCacheMetadata, offsetHours: Int): List<LocalDate> {
        val minStart = metadata.minStartEpochSeconds ?: return emptyList()
        val maxEnd = metadata.maxEndEpochSeconds ?: return emptyList()
        if (maxEnd <= minStart) return emptyList()
        val shiftSeconds = offsetHours * 3_600L
        val zone = ZoneId.systemDefault()
        val minDate = java.time.Instant.ofEpochSecond(minStart + shiftSeconds).atZone(zone).toLocalDate()
        val maxDate = java.time.Instant.ofEpochSecond((maxEnd - 1 + shiftSeconds).coerceAtLeast(minStart + shiftSeconds))
            .atZone(zone)
            .toLocalDate()
        val span = ChronoUnit.DAYS.between(minDate, maxDate).coerceIn(0L, MAX_EPG_DAY_SPAN)
        return (0..span).map { minDate.plusDays(it) }
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
                            epgAvailableDates = emptyList(),
                            epgSelectedDate = null,
                            message = loaded.importSummary ?: state.message,
                        )
                    }
                }
            }
            if (committed || !layoutChanged) {
                // Garde le catalogue déjà résolu sur disque pour que resumeStartup() puisse le
                // réutiliser directement à la prochaine relance de l'app tant que l'organisation
                // courante (categoryOrder/movedEntries) n'a pas changé depuis (voir
                // resolvedCatalogIfLayoutUnchanged). Seulement quand quelque chose a réellement été
                // personnalisé (applyUserLibraryToCatalog a reconstruit une nouvelle instance) :
                // sans chaîne déplacée ni tri de catégories, ce catalogue résolu serait un doublon
                // strictement identique au cache brut déjà écrit par CatalogCache.save(), payé en
                // pure perte (I/O + reparsing) à chaque lecture par la majorité des profils qui
                // n'utilisent pas l'organisateur.
                if (committed && presentation.catalog !== loaded.catalog) {
                    runCatching { repository.saveResolvedCatalog(loaded.profileId, presentation.catalog, presentation.library) }
                }
                if (committed) {
                    ensureSectionLoaded(
                        MediaType.Live,
                        forceEpgSync = loaded.source == CatalogSource.Network || loaded.source == CatalogSource.Import,
                    )
                }
                return
            }

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
        _uiState.value = StreamiaUiState(
            booting = false,
            screen = StreamiaScreen.Login,
            profiles = repository.profiles(),
            appSettings = repository.appSettings(),
        )
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
            appSettings = repository.appSettings(),
            offline = loaded.source == CatalogSource.Cache,
            message = loaded.importSummary,
        )
        ensureSectionLoaded(
            MediaType.Live,
            forceEpgSync = loaded.source == CatalogSource.Network || loaded.source == CatalogSource.Import,
        )
    }

    private fun showError(error: Throwable) {
        _uiState.update { it.copy(busy = false, message = error.safeMessage(), testSucceeded = false, profiles = repository.profiles()) }
    }

    private fun Throwable.safeMessage(): String = message?.takeIf { it.isNotBlank() } ?: "Une erreur inattendue s'est produite."

    private fun AccountInfo.toConnectionSuccessMessage(): String = buildString {
        append("Connexion réussie · compte $status")
        expiresAtEpochSeconds?.let { append(", expire le ${formatExpiry(it)}") }
    }

    private fun formatExpiry(epochSeconds: Long): String =
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(epochSeconds * 1000L))
}

data class StreamiaUiState(
    val booting: Boolean = true,
    val busy: Boolean = false,
    val testingConnection: Boolean = false,
    val testSucceeded: Boolean = false,
    val catalogHydrating: Boolean = false,
    val screen: StreamiaScreen = StreamiaScreen.Login,
    val rawCatalog: Catalog? = null,
    val catalog: Catalog? = null,
    val credentials: ServerCredentials? = null,
    val activeProfileId: String? = null,
    val profiles: List<PlaylistProfile> = emptyList(),
    val library: UserLibrarySnapshot = UserLibrarySnapshot(),
    val appSettings: AppSettings = AppSettings(),
    /** Code parental saisi correctement pendant cette session (redevient false à la relance de l'app). */
    val parentalUnlocked: Boolean = false,
    val updateChecking: Boolean = false,
    val updateCheck: UpdateCheckResult? = null,
    val offline: Boolean = false,
    val message: String? = null,
    val mediaDetails: MediaDetails? = null,
    val seriesDetails: SeriesDetails? = null,
    val epg: EpgNowContext = EpgNowContext(),
    val epgGuide: EpgGuide? = null,
    val epgAvailableDates: List<LocalDate> = emptyList(),
    val epgSelectedDate: LocalDate? = null,
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
    data object Tools : StreamiaScreen
    data object About : StreamiaScreen
    data object ParentalControl : StreamiaScreen
    data object Search : StreamiaScreen
    data object Epg : StreamiaScreen
    data object Organizer : StreamiaScreen
    data class MovieDetails(val movie: MediaEntry) : StreamiaScreen
    data class Series(val series: MediaEntry) : StreamiaScreen
    data class Player(val entry: MediaEntry, val returnToSeries: Boolean = false) : StreamiaScreen
}

private const val EPG_PLAYER_REFRESH_MS = 30_000L
private const val MAX_EPG_DAY_SPAN = 30L

class StreamiaViewModelFactory(private val repository: XtreamRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = StreamiaViewModel(repository) as T
}

package fr.streamia.tv.ui

import fr.streamia.tv.data.WatchNextPublisher
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.streamia.tv.BuildConfig
import fr.streamia.tv.beinsports.BeinSportsChannelMatcher
import fr.streamia.tv.beinsports.ResolvedBeinProgrammeItem
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.CatalogSource
import fr.streamia.tv.data.EpgCacheMetadata
import fr.streamia.tv.data.CurrentWeather
import fr.streamia.tv.data.HomeBlock
import fr.streamia.tv.data.HomePlace
import fr.streamia.tv.data.HomeWeatherClient
import fr.streamia.tv.data.PrayerMethod
import fr.streamia.tv.data.JustWatchSection
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
import fr.streamia.tv.domain.LiveZapIndex
import fr.streamia.tv.domain.epgNowContextAt
import fr.streamia.tv.domain.withTimeOffset
import fr.streamia.tv.liveonsat.ChannelMatcher
import fr.streamia.tv.data.LiveOnSatFetchResult
import fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch
import fr.streamia.tv.liveonsat.withEpgTiming
import fr.streamia.tv.tvprogramme.ResolvedTvProgrammeItem
import fr.streamia.tv.tvprogramme.ResolvedTvProgrammeNowItem
import fr.streamia.tv.tvprogramme.TvProgrammeChannelMatcher
import fr.streamia.tv.ukguide.ResolvedUkProgrammeItem
import fr.streamia.tv.ukguide.UkGuideChannelMatcher
import fr.streamia.tv.recommendation.ContentFeatures
import fr.streamia.tv.recommendation.RecommendationBuildContext
import fr.streamia.tv.recommendation.RecommendationEngine
import fr.streamia.tv.recommendation.RecommendationProfileInput
import fr.streamia.tv.recommendation.RecommendationRow
import fr.streamia.tv.recommendation.RecommendationRowKind
import fr.streamia.tv.recommendation.RecommendedMedia
import fr.streamia.tv.recommendation.ViewingRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
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
    private val epgGuideMemoryCache = EpgGuideMemoryCache()
    private val recommendationEngine = RecommendationEngine()
    private var homeRecommendationBuildSequence = 0L
    private var homeRecommendationJob: Job? = null
    private var homeRecommendationLastBuiltProfileId: String? = null
    private var homeRecommendationLastBuiltAtMillis = 0L
    private val liveOnSatChannelMatcher = ChannelMatcher()
    private var liveOnSatLoadSequence = 0L
    private var liveOnSatLoadJob: Job? = null
    /** Échéance comptée depuis l'âge réel du cache ; après un échec, nouvel essai plus tôt. */
    private var liveOnSatNextCheckAtMillis = 0L
    private val tvProgrammeChannelMatcher = TvProgrammeChannelMatcher()
    private val beinSportsChannelMatcher = BeinSportsChannelMatcher()
    private val ukGuideChannelMatcher = UkGuideChannelMatcher()

    // Guides tiers de l'accueil : même cycle chargement → rapprochement → publication, voir [HomeGuide].
    private val tvProgrammeNowGuide = HomeGuide(
        blocks = setOf(HomeBlock.TvProgrammeNow),
        fetch = { repository.loadTvProgrammeNow(it) },
        isEmpty = { it.programmes.isEmpty() },
    ) { fetch, catalog, visible ->
        val resolved = tvProgrammeChannelMatcher.resolveNow(fetch.programmes, catalog).filter { visible(it.channel) }
        ({ state -> state.copy(homeTvProgrammeNow = resolved) })
    }
    private val tvProgrammeTonightGuide = HomeGuide(
        blocks = setOf(HomeBlock.TvProgrammeTonight),
        fetch = { repository.loadTvProgrammeTonight(it) },
        isEmpty = { it.programmes.isEmpty() },
    ) { fetch, catalog, visible ->
        val resolved = tvProgrammeChannelMatcher.resolve(fetch.programmes, catalog).filter { visible(it.channel) }
        ({ state -> state.copy(homeTvProgrammeTonight = resolved) })
    }
    private val beinSportsGuide = HomeGuide(
        blocks = setOf(HomeBlock.BeinSportsNow, HomeBlock.BeinSportsNext),
        fetch = { repository.loadBeinSportsGuide(it) },
        isEmpty = { it.rows.current.isEmpty() && it.rows.next.isEmpty() },
    ) { fetch, catalog, visible ->
        val current = beinSportsChannelMatcher.resolve(fetch.rows.current, catalog).filter { visible(it.channel) }
        val next = beinSportsChannelMatcher.resolve(fetch.rows.next, catalog).filter { visible(it.channel) }
        ({ state -> state.copy(homeBeinSportsNow = current, homeBeinSportsNext = next) })
    }
    private val ukGuide = HomeGuide(
        blocks = setOf(HomeBlock.UkGuideNow, HomeBlock.UkGuideNext),
        fetch = { repository.loadUkGuide(it) },
        isEmpty = { it.rows.current.isEmpty() && it.rows.next.isEmpty() },
    ) { fetch, catalog, visible ->
        val current = ukGuideChannelMatcher.resolve(fetch.rows.current, catalog).filter { visible(it.channel) }
        val next = ukGuideChannelMatcher.resolve(fetch.rows.next, catalog).filter { visible(it.channel) }
        ({ state -> state.copy(homeUkGuideNow = current, homeUkGuideNext = next) })
    }
    private val weatherClient = HomeWeatherClient()
    private var weatherNextCheckAtMillis = 0L
    private var weatherJob: Job? = null
    private val homeGuides = listOf(tvProgrammeNowGuide, tvProgrammeTonightGuide, beinSportsGuide, ukGuide)
    private var epgSyncJob: Job? = null
    private var epgSyncProfileId: String? = null
    private var epgPrefetchJob: Job? = null
    private var epgChannelJob: Job? = null
    private var epgTickerJob: Job? = null
    private var zapJob: Job? = null
    /** Chaîne Direct regardée juste avant la chaîne courante, pour « dernière chaîne ». */
    private var previousLiveEntry: MediaEntry? = null
    private var lastLiveEntry: MediaEntry? = null
    private var secondaryLoadsJob: Job? = null
    // Clé comparée par identité des instances (catalogue/ensembles), recalculée seulement quand
    // l'un d'eux change réellement.
    private var zapIndexCache: Pair<List<Any>, LiveZapIndex>? = null
    // Lu/écrit uniquement depuis le thread principal (appelants Compose) : évite de relancer une
    // requête SQLite déjà en vol pour la même page quand plusieurs recompositions déclenchent le
    // même chargement (ex. sélection rapide de catégories, LaunchedEffect qui se relance).
    private val categoryLoadsInFlight = mutableSetOf<String>()
    val uiState: StateFlow<StreamiaUiState> = _uiState.asStateFlow()
    private val _playerState = MutableStateFlow(PlayerUiState())
    val playerState: StateFlow<PlayerUiState> = _playerState.asStateFlow()

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
        // Après une fermeture complète Android recrée le ViewModel, donc le petit cache RAM EPG
        // repart vide. La base SQLite, elle, est persistante : la relire immédiatement ici remet
        // la journée courante en mémoire sans aucun appel réseau, même quand le démarrage reprend
        // directement le dernier flux Live et contourne openProfile()/showCatalog().
        warmEpgGuideCache(profileId)
        // Reprise directe dans le lecteur : la vidéo passe d'abord, les guides tiers attendent.
        scheduleSecondaryLoads(STARTUP_SECONDARY_LOADS_PLAYER_DELAY_MS)
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
                    if (loaded.source == CatalogSource.Cache) {
                        // Reprise directe dans le lecteur : la vidéo d'abord, l'actualisation ensuite.
                        delay(CATALOG_BACKGROUND_REFRESH_DELAY_MS)
                        refreshSilently(profileId)
                    }
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
            lastLiveEntry = entry
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
                    appSettings = repository.appSettings(),
                )
                warmEpgGuideCache(profileId)
                refreshHomeRecommendations()
                scheduleSecondaryLoads(STARTUP_SECONDARY_LOADS_HOME_DELAY_MS)
                try {
                    val loaded = repository.openProfile(profileId, knownCache = cachedCatalog)
                    mergeCatalog(loaded)
                    if (loaded.source == CatalogSource.Cache) {
                        // Catalogue expiré : actualisation silencieuse une fois l'accueil et ses guides
                        // chargés, pour ne pas concurrencer le démarrage (lectures WAL non bloquées).
                        delay(CATALOG_BACKGROUND_REFRESH_DELAY_MS)
                        refreshSilently(profileId)
                    }
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
        val previousEpgUrl = repository.profile(profileId)?.xmlTvUrl
        val updated = repository.updateRemoteSettings(
            profileId,
            m3uUrl.trim().takeIf(String::isNotBlank),
            xmlTvUrl.trim().takeIf(String::isNotBlank),
            autoRefreshHours,
        ) ?: return
        _uiState.update { it.copy(profiles = repository.profiles(), message = "Paramètres M3U/EPG enregistrés.") }

        val epgSourceChanged = previousEpgUrl != updated.xmlTvUrl
        if (epgSourceChanged) {
            viewModelScope.launch {
                epgGuideMemoryCache.clearProfile(profileId)
                repository.clearEpgCache(profileId)
                if (_uiState.value.activeProfileId == profileId) {
                    _playerState.update { it.copy(epg = EpgNowContext()) }
                    _uiState.update {
                        it.copy(
                            epgGuide = null,
                            todayEpgGuide = null,
                            epgAvailableDates = emptyList(),
                            epgSelectedDate = null,
                        )
                    }
                }
                if (updated.isRemoteM3u && m3uUrl.isNotBlank()) {
                    openProfile(profileId)
                } else if (_uiState.value.activeProfileId == profileId) {
                    startEpgBackgroundSync(force = true)
                }
            }
        } else if (updated.isRemoteM3u && m3uUrl.isNotBlank()) {
            openProfile(profileId)
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
                    epgGuideMemoryCache.clearProfile(profileId)
                    _uiState.update { it.copy(busy = false, profiles = repository.profiles(), message = "Liste supprimée.") }
                }
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
            val result = repository.checkForUpdate(BuildConfig.VERSION_CODE)
            // updateChecking reste vrai pendant le téléchargement : le bouton affiche « Téléchargement… ».
            _uiState.update { it.copy(updateCheck = result) }
            if (result is UpdateCheckResult.UpdateAvailable) {
                runCatching { repository.downloadAndInstallUpdate(result.release) }.onFailure { error ->
                    val message = "Téléchargement impossible : " + (error.message ?: "erreur inconnue.")
                    _uiState.update { it.copy(updateCheck = UpdateCheckResult.Error(message)) }
                }
            }
            _uiState.update { it.copy(updateChecking = false) }
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
        // Le Browser reste le retour par défaut. Depuis un détail (contenu similaire / retry),
        // conserver le contexte qui a amené l'utilisateur jusque-là au lieu de l'écraser.
        if (_uiState.value.screen is StreamiaScreen.Browser || _uiState.value.screen is StreamiaScreen.Epg) {
            _uiState.update { it.copy(contentReturnContext = ContentReturnContext.browser(entry.key)) }
        }
        openEntryInternal(entry)
    }

    fun openHomeEntry(entry: MediaEntry, rowKey: String, itemKey: String = entry.key) {
        _uiState.update { it.copy(contentReturnContext = ContentReturnContext.home(rowKey, itemKey)) }
        openEntryInternal(entry)
    }

    fun openSearchEntry(entry: MediaEntry) {
        _uiState.update { it.copy(contentReturnContext = ContentReturnContext.search(entry.key)) }
        openEntryInternal(entry)
    }

    fun openLiveMatchChannel(entry: MediaEntry, matchKey: String) {
        _uiState.update { it.copy(contentReturnContext = ContentReturnContext.liveMatches(matchKey, entry.key)) }
        openEntryInternal(entry)
    }

    private fun openEntryInternal(entry: MediaEntry) {
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
    fun resumeHomePlayback(entry: MediaEntry) {
        _uiState.update { it.copy(contentReturnContext = ContentReturnContext.home(HomeRowKey.Resume, entry.key)) }
        if (entry.type == MediaType.Movie) openPlayer(entry, returnToSeries = false) else openEntryInternal(entry)
    }

    fun resumePlayback(entry: MediaEntry) {
        if (entry.type == MediaType.Movie) openPlayer(entry, returnToSeries = false) else openEntry(entry)
    }

    fun playMovie(movie: MediaEntry) = openPlayer(movie, returnToSeries = false, returnToDetails = true)

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
        // Le guide EPG est volontairement conservé en mémoire quand on revient à l'accueil.
        _uiState.update {
            it.copy(
                screen = StreamiaScreen.Home,
                menuBackStack = emptyList(),
                message = null,
                epgLoading = false,
            )
        }
        refreshHomeRecommendations()
        homeGuides.forEach { it.resolve() }
        tvProgrammeNowGuide.load(forceRefresh = false)
        beinSportsGuide.load(forceRefresh = false)
        ukGuide.load(forceRefresh = false)
    }

    /**
     * Ouvre un écran « de menu » (Recherche, EPG, Paramètres, Outils…) en empilant l'écran courant :
     * Retour ramène ainsi à l'écran d'où l'on vient — notamment Direct/Films/Séries — au lieu de
     * renvoyer systématiquement à l'accueil. Les écrans de contenu (lecteur, fiche film/série) ne
     * sont jamais empilés, ils gardent leur propre logique de retour.
     */
    private fun navigateToMenu(
        target: StreamiaScreen,
        homeFocus: HomeFocusTarget? = null,
        update: (StreamiaUiState) -> StreamiaUiState = { it },
    ) {
        _uiState.update { state ->
            val pushed = when (state.screen) {
                target -> state.menuBackStack
                is StreamiaScreen.Player, is StreamiaScreen.MovieDetails, is StreamiaScreen.Series ->
                    state.menuBackStack
                else -> state.menuBackStack + state.screen
            }
            // La carte à refocaliser n'est mémorisée que lorsqu'on quitte réellement l'accueil.
            val focus = if (state.screen == StreamiaScreen.Home && homeFocus != null) homeFocus else state.homeFocusTarget
            update(state).copy(screen = target, menuBackStack = pushed, message = null, homeFocusTarget = focus)
        }
    }

    /** Consommé par l'accueil une fois le focus reposé sur la carte mémorisée. */
    fun consumeHomeFocusTarget() {
        _uiState.update { if (it.homeFocusTarget == null) it else it.copy(homeFocusTarget = null) }
    }

    /** Consommé par le navigateur une fois le contenu rouvert refocalisé (retour depuis une fiche). */
    fun consumeBrowserRestore() {
        _uiState.update {
            if (it.contentReturnContext?.origin == ContentReturnOrigin.Browser) {
                it.copy(contentReturnContext = null)
            } else {
                it
            }
        }
    }

    /** Retour depuis un écran de menu : revient à l'écran empilé, sinon à l'accueil. */
    fun backFromMenu() {
        val state = _uiState.value
        when (val target = state.menuBackStack.lastOrNull()) {
            null, StreamiaScreen.Home -> showHome()
            else -> _uiState.update {
                it.copy(
                    screen = target,
                    menuBackStack = state.menuBackStack.dropLast(1),
                    message = null,
                    epgLoading = false,
                )
            }
        }
    }

    fun showSettings() = navigateToMenu(StreamiaScreen.Settings, HomeFocusTarget.Settings)
    fun showTools() = navigateToMenu(StreamiaScreen.Tools)
    fun showAbout() = navigateToMenu(StreamiaScreen.About)

    suspend fun cacheSizeBytes(): Long = repository.cacheSizeBytes()
    suspend fun epgCacheSizeBytes(): Long = repository.epgCacheSizeBytes()
    fun showParentalControl() = navigateToMenu(StreamiaScreen.ParentalControl)
    fun showSearch() = navigateToMenu(StreamiaScreen.Search, HomeFocusTarget.Search)

    fun updateSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                contentReturnContext = it.contentReturnContext
                    ?.takeUnless { context -> context.origin == ContentReturnOrigin.Search },
            )
        }
    }

    fun updateSearchType(type: MediaType?) {
        _uiState.update {
            it.copy(
                searchType = type,
                contentReturnContext = it.contentReturnContext
                    ?.takeUnless { context -> context.origin == ContentReturnOrigin.Search },
            )
        }
    }

    fun showLiveMatches() {
        navigateToMenu(StreamiaScreen.LiveMatches, HomeFocusTarget.LiveMatches)
        // Déjà chargés au démarrage : affichage direct depuis l'état, sans relire ni rescraper.
        if (_uiState.value.liveOnSatMatches.isEmpty()) loadLiveOnSatMatches(forceRefresh = false) else refreshLiveOnSatIfStale()
    }

    /** Appelé en boucle par l'accueil et la page Matchs : recharge quand le cache atteint 2 h. */
    fun refreshLiveOnSatIfStale() {
        if (System.currentTimeMillis() < liveOnSatNextCheckAtMillis) return
        loadLiveOnSatMatches(forceRefresh = false)
    }

    fun refreshLiveOnSatMatches() = loadLiveOnSatMatches(forceRefresh = true)

    fun refreshTvProgrammeNow() = tvProgrammeNowGuide.load(forceRefresh = false)

    fun refreshBeinSportsGuide() = beinSportsGuide.load(forceRefresh = false)

    fun refreshUkGuide() = ukGuide.load(forceRefresh = false)

    /** Météo de l'en-tête : au plus une requête toutes les 30 min (5 min après un échec). */
    fun refreshWeatherIfStale() {
        if (System.currentTimeMillis() < weatherNextCheckAtMillis || weatherJob?.isActive == true) return
        weatherJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val place = _uiState.value.appSettings.homePlace
                        ?: _uiState.value.weatherPlace
                        ?: weatherClient.locateByIp()
                        ?: error("Localisation impossible.")
                    place to weatherClient.currentWeather(place)
                }
            }
            result.onSuccess { (place, weather) ->
                _uiState.update { it.copy(weatherPlace = place, weather = weather) }
            }
            weatherNextCheckAtMillis = System.currentTimeMillis() + if (result.isSuccess) 30 * 60_000L else 5 * 60_000L
        }
    }

    suspend fun searchCities(query: String): List<HomePlace> =
        withContext(Dispatchers.IO) { runCatching { weatherClient.searchCities(query) }.getOrDefault(emptyList()) }

    /** null = revenir à la détection d'après la connexion. */
    fun setHomePlace(place: HomePlace?) {
        updateAppSettings { it.copy(homePlace = place) }
        weatherJob?.cancel()
        weatherNextCheckAtMillis = 0L
        _uiState.update { it.copy(weatherPlace = place, weather = null) }
        refreshWeatherIfStale()
    }

    fun setPrayerMethod(method: PrayerMethod) {
        updateAppSettings { it.copy(prayerMethod = method) }
    }

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
        val profileId = _uiState.value.activeProfileId ?: return
        epgGuideMemoryCache.clearProfile(profileId)
        _uiState.update {
            it.copy(
                epgGuide = null,
                todayEpgGuide = null,
                epgAvailableDates = emptyList(),
                epgSelectedDate = null,
                epgLoading = false,
            )
        }
        warmEpgGuideCache(profileId)
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


    fun toggleHomeBlock(block: HomeBlock) {
        updateAppSettings {
            it.copy(
                disabledHomeBlocks = if (block in it.disabledHomeBlocks) {
                    it.disabledHomeBlocks - block
                } else {
                    it.disabledHomeBlocks + block
                },
            )
        }
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
        navigateToMenu(StreamiaScreen.Organizer)
        MediaType.entries.forEach(::ensureSectionLoaded)
    }
    fun showBrowser() { _uiState.update { it.copy(screen = StreamiaScreen.Browser, message = null) } }

    fun openSection(type: MediaType) {
        _uiState.update {
            it.copy(
                screen = StreamiaScreen.Browser,
                browserType = type,
                browserCategoryId = null,
                menuBackStack = emptyList(),
                message = null,
                homeFocusTarget = when (type) {
                    MediaType.Live -> HomeFocusTarget.Live
                    MediaType.Movie -> HomeFocusTarget.Movies
                    MediaType.Series -> HomeFocusTarget.Series
                },
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
        prefetchNeighborCategories(profileId, type, categoryId)
    }

    /**
     * Charge en arrière-plan les catégories adjacentes à [categoryId] dans le rail (précédente et
     * suivante), tant qu'elles ne sont pas déjà matérialisées. La navigation à la télécommande est
     * linéaire (haut/bas dans le rail) : l'utilisateur ouvre presque toujours la catégorie voisine,
     * et une page SQLite indexée coûte beaucoup moins que la latence d'un clic non préchargé.
     */
    private fun prefetchNeighborCategories(profileId: String, type: MediaType, categoryId: String) {
        val catalog = _uiState.value.catalog ?: return
        val ordered = catalog.categoriesFor(type)
        val index = ordered.indexOfFirst { it.id == categoryId }
        if (index < 0) return
        listOfNotNull(ordered.getOrNull(index - 1), ordered.getOrNull(index + 1)).forEach { neighbor ->
            if (!catalog.isCategoryLoaded(type, neighbor.id)) {
                loadCategoryPage(profileId, type, neighbor.id, offset = 0)
            }
        }
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
        setCategoryLoading(type, categoryId, loading = true)
        viewModelScope.launch {
            try {
                val page = runCatching { repository.loadCategoryPage(profileId, type, categoryId, offset) }.getOrNull() ?: return@launch
                if (page.entries.isEmpty() && offset > 0) return@launch
                mergeIntoCatalog(profileId) { base ->
                    base.withMaterializedEntries(page.entries, type, categoryId)
                }
            } finally {
                categoryLoadsInFlight.remove(loadKey)
                setCategoryLoading(type, categoryId, loading = false)
            }
        }
    }

    /**
     * Fusionne une évolution du catalogue brut **hors du thread principal**.
     *
     * Reconstruire un [Catalog] réindexe toutes les entrées matérialisées — dont les dizaines de
     * milliers de chaînes Direct chargées au démarrage — et [XtreamRepository.customizedCatalog]
     * relit en plus les préférences du profil. Fait jusqu'ici directement dans `_uiState.update`,
     * donc sur le thread principal : chaque sélection de catégorie Films/Séries figeait l'écran, qui
     * continuait d'afficher la catégorie précédente le temps du calcul.
     *
     * [catalogLayoutMutation] sérialise les fusions et la base est relue sous ce verrou : deux
     * chargements concurrents (navigation rapide entre catégories) ne peuvent plus s'écraser l'un
     * l'autre en repartant d'une même version périmée.
     */
    private suspend fun mergeIntoCatalog(profileId: String, merge: (Catalog) -> Catalog) {
        catalogLayoutMutation.withLock {
            if (_uiState.value.activeProfileId != profileId) return
            val base = _uiState.value.rawCatalog ?: _uiState.value.catalog ?: return
            val raw = withContext(Dispatchers.Default) { merge(base) }
            val customized = withContext(Dispatchers.Default) { repository.customizedCatalog(profileId, raw) }
            _uiState.update { state ->
                if (state.activeProfileId != profileId) state
                else state.copy(rawCatalog = raw, catalog = customized)
            }
        }
    }

    /**
     * Marque une catégorie comme en cours de lecture. Sans cette information, l'interface ne peut pas
     * distinguer « la page arrive » de « la catégorie est vide » et affichait « Aucun contenu dans
     * cette catégorie » pendant tout le chargement.
     */
    private fun setCategoryLoading(type: MediaType, categoryId: String, loading: Boolean) {
        val key = Catalog.categoryKey(type, categoryId)
        _uiState.update { state ->
            val next = if (loading) state.loadingCategoryKeys + key else state.loadingCategoryKeys - key
            if (next == state.loadingCategoryKeys) state else state.copy(loadingCategoryKeys = next)
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
    private fun ensureSectionLoaded(type: MediaType) {
        val profileId = _uiState.value.activeProfileId ?: return
        val catalog = _uiState.value.catalog ?: return
        if (catalog.isCategoryLoaded(type, Catalog.ALL_CATEGORY_ID)) {
            if (type == MediaType.Live) {
                startEpgBackgroundSync()
                homeGuides.forEach { it.resolve() }
            }
            return
        }
        val loadKey = "$profileId:section:${type.name}"
        if (!categoryLoadsInFlight.add(loadKey)) return
        viewModelScope.launch {
            try {
                val section = runCatching { repository.loadSection(profileId, type) }.getOrNull() ?: return@launch
                mergeIntoCatalog(profileId) { base -> base.withFullSectionMaterialized(section, type) }
                if (type == MediaType.Live) {
                    startEpgBackgroundSync()
                    homeGuides.forEach { it.resolve() }
                }
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
        navigateToMenu(StreamiaScreen.Epg, HomeFocusTarget.Guide) { state ->
            // Si une journée est déjà matérialisée (retour depuis Accueil/Player), on l'affiche
            // immédiatement. Une éventuelle lecture SQLite ou resynchro se fait derrière.
            state.copy(epgLoading = state.epgGuide == null)
        }
        ensureSectionLoaded(MediaType.Live)
        loadEpgGuideFromCache(_uiState.value.epgSelectedDate)
        startEpgBackgroundSync()
    }

    fun selectEpgDate(date: LocalDate) {
        val state = _uiState.value
        val profileId = state.activeProfileId ?: return
        val offsetHours = state.appSettings.epgTimeOffsetHours
        val cached = epgGuideMemoryCache.get(profileId, date, offsetHours)
        if (cached != null) {
            _uiState.update {
                it.copy(
                    epgSelectedDate = date,
                    epgGuide = cached,
                    epgLoading = false,
                    message = null,
                )
            }
            prefetchEpgNeighbors(profileId, date, state.epgAvailableDates, offsetHours)
            return
        }

        // Ne jamais effacer la grille courante pendant une simple navigation de jour. Si le jour
        // n'est pas encore chaud en RAM, on garde l'ancien affichage quelques millisecondes puis on
        // bascule atomiquement sur le résultat SQLite.
        _uiState.update { it.copy(epgLoading = true, message = null) }
        loadEpgGuideFromCache(date)
    }

    fun reloadEpg() {
        // Une actualisation manuelle peut être longue : l'ancien guide reste visible jusqu'au
        // commit transactionnel du nouveau XMLTV.
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
    /**
     * Bascule une clé dans un ensemble de la bibliothèque (favori, masqué, verrouillé, vu) : mise à
     * jour immédiate de l'interface, écriture sérialisée sur IO, puis relecture de la bibliothèque
     * seulement si aucune bascule plus récente n'a eu lieu entre-temps.
     */
    private fun toggleInLibrary(
        key: String,
        read: (UserLibrarySnapshot) -> Set<String>,
        write: (UserLibrarySnapshot, Set<String>) -> UserLibrarySnapshot,
        persist: (profileId: String) -> Unit,
    ) {
        val profileId = _uiState.value.activeProfileId ?: return
        _uiState.update { state ->
            val updated = read(state.library).toMutableSet().apply { if (!add(key)) remove(key) }
            state.copy(library = write(state.library, updated))
        }
        val sequence = ++libraryMutationSequence
        viewModelScope.launch {
            libraryMutation.withLock {
                runCatching {
                    withContext(Dispatchers.IO) {
                        persist(profileId)
                        repository.library(profileId)
                    }
                }.onSuccess { library ->
                    if (sequence != libraryMutationSequence) return@onSuccess
                    _uiState.update { state -> if (state.activeProfileId == profileId) state.copy(library = library) else state }
                }
            }
        }
    }

    fun toggleEntryFavorite(entry: MediaEntry) = toggleInLibrary(entry.key, { it.favoriteEntries }, { lib, set -> lib.copy(favoriteEntries = set) }) { profileId ->
        repository.toggleEntryFavorite(profileId, entry)
    }

    fun toggleEntryHidden(entry: MediaEntry) = toggleInLibrary(entry.key, { it.hiddenEntries }, { lib, set -> lib.copy(hiddenEntries = set) }) { profileId ->
        repository.toggleEntryHidden(profileId, entry)
    }

    fun toggleCategoryHidden(category: MediaCategory) = toggleInLibrary(category.key, { it.hiddenCategories }, { lib, set -> lib.copy(hiddenCategories = set) }) { profileId ->
        repository.toggleCategoryHidden(profileId, category)
    }

    fun toggleCategoryLocked(category: MediaCategory) = toggleInLibrary(category.key, { it.lockedCategories }, { lib, set -> lib.copy(lockedCategories = set) }) { profileId ->
        repository.toggleCategoryLocked(profileId, category)
    }

    fun toggleEntryWatched(entry: MediaEntry) = toggleInLibrary(entry.key, { it.watchedEntries }, { lib, set -> lib.copy(watchedEntries = set) }) { profileId ->
        repository.toggleEntryWatched(profileId, entry)
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

    fun toggleCategoryFavorite(category: MediaCategory) = toggleInLibrary(category.key, { it.favoriteCategories }, { lib, set -> lib.copy(favoriteCategories = set) }) { profileId ->
        repository.toggleCategoryFavorite(profileId, category)
    }

    fun recordPlayback(entry: MediaEntry, positionMs: Long, durationMs: Long) {
        val profileId = _uiState.value.activeProfileId ?: return
        viewModelScope.launch {
            libraryMutation.withLock {
                val history = withContext(Dispatchers.IO) {
                    repository.recordPlayback(profileId, entry, positionMs, durationMs)
                    repository.publishWatchNext(profileId)
                    repository.library(profileId).history
                }
                _uiState.update { state ->
                    if (state.activeProfileId == profileId) {
                        state.copy(library = state.library.copy(history = history))
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun clearHistory(type: MediaType? = null) {
        val profileId = _uiState.value.activeProfileId ?: return
        repository.clearHistory(profileId, type)
        refreshLibraryPresentation()
        viewModelScope.launch(Dispatchers.IO) { repository.publishWatchNext(profileId) }
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
        backFromMenu()
        val profileId = _uiState.value.activeProfileId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            catalogLayoutMutation.withLock {
                val presentation = libraryPresentation(profileId)
                applyLibraryPresentation(profileId, presentation)
            }
        }
    }

    fun closePlayer(forceBrowser: Boolean = false) {
        zapJob?.cancel()
        _playerState.value = PlayerUiState()
        val profileId = _uiState.value.activeProfileId
        val player = _uiState.value.screen as? StreamiaScreen.Player
        _uiState.update {
            val sourceDestination = it.contentReturnContext
                ?.takeIf { context -> context.origin != ContentReturnOrigin.Browser }
                ?.destinationScreen()
            it.copy(
                screen = when {
                    forceBrowser -> StreamiaScreen.Browser
                    player?.returnToSeries == true && it.seriesDetails != null -> StreamiaScreen.Series(it.seriesDetails.series)
                    // Film lancé depuis sa fiche : Retour rouvre la fiche (le contexte de parcours,
                    // lui, ne sert qu'à un second Retour, de la fiche vers la liste).
                    player?.returnToDetails == true && player.entry.type == MediaType.Movie ->
                        StreamiaScreen.MovieDetails(player.entry)
                    sourceDestination != null -> sourceDestination
                    it.catalogHydrating -> StreamiaScreen.Home
                    else -> StreamiaScreen.Browser
                },
                resumePositionMs = 0,
            )
        }
        epgChannelJob?.cancel()
        epgTickerJob?.cancel()
        if (profileId != null) refreshLibrarySnapshot(profileId)
    }

    fun closeDetails() {
        _uiState.update {
            it.copy(
                screen = it.contentReturnContext?.destinationScreen() ?: StreamiaScreen.Browser,
                mediaDetails = null,
                similarMedia = emptyList(),
                message = null,
            )
        }
    }
    fun closeSeries() {
        _uiState.update {
            it.copy(
                screen = it.contentReturnContext?.destinationScreen() ?: StreamiaScreen.Browser,
                seriesDetails = null,
                similarMedia = emptyList(),
                message = null,
            )
        }
    }

    fun zap(delta: Int) {
        val state = _uiState.value
        val current = (state.screen as? StreamiaScreen.Player)?.entry ?: return
        if (current.type != MediaType.Live) return
        val catalog = state.catalog ?: return
        // Zap rapide : chaque appui avance depuis la chaîne déjà annoncée (pas depuis celle qui
        // joue), le bandeau s'affiche tout de suite, et le flux ne démarre qu'une fois les appuis
        // terminés — enchaîner CH+ ne lance plus un flux réseau (et un EPG) par chaîne traversée.
        val from = _playerState.value.pendingZapEntry ?: current
        val next = liveZapIndex(state, catalog).adjacent(from, delta) ?: return
        _playerState.update { it.copy(pendingZapEntry = next) }
        zapJob?.cancel()
        zapJob = viewModelScope.launch {
            delay(ZAP_SETTLE_MS)
            _playerState.update { it.copy(pendingZapEntry = null) }
            if (next.key != current.key) openPlayer(next, returnToSeries = false)
        }
    }

    /**
     * Le repli sur toutes les chaînes peut faire sauter le zapping dans une autre catégorie : une
     * catégorie verrouillée et pas encore déverrouillée cette session est exclue comme si elle était
     * masquée — il n'y a pas d'écran de code pendant le zapping.
     */
    private fun liveZapIndex(state: StreamiaUiState, catalog: Catalog): LiveZapIndex {
        val locked = state.appSettings.parentalControlEnabled && !state.parentalUnlocked
        val key = listOf(catalog, state.library.hiddenEntries, state.library.lockedCategories, locked)
        zapIndexCache?.takeIf { it.first == key }?.let { return it.second }
        val lockedCategoryIds = if (locked) {
            catalog.categoriesFor(MediaType.Live)
                .filter { it.key in state.library.lockedCategories }
                .mapTo(mutableSetOf(), MediaCategory::id)
        } else {
            emptySet()
        }
        return LiveZapIndex(catalog, state.library.hiddenEntries, lockedCategoryIds).also { zapIndexCache = key to it }
    }

    /**
     * Carte « Continuer à regarder » de Google TV (voir [WatchNextPublisher.resumeUri]) : reprend le
     * contenu à sa position, par le même chemin rapide que la reprise au démarrage.
     */
    fun openResumeLink(uri: Uri?): Boolean {
        if (uri?.scheme != WatchNextPublisher.SCHEME || uri.host != WatchNextPublisher.HOST_RESUME) return false
        val profileId = uri.getQueryParameter("profile") ?: return false
        val key = uri.getQueryParameter("key") ?: return false
        if (repository.profile(profileId) == null) return false
        val entry = repository.library(profileId).history.firstOrNull { it.entry.key == key }?.entry ?: return false
        resumeStartup(profileId, entry, returnToSeries = entry.type == MediaType.Series)
        return true
    }

    /** Revient à la chaîne regardée juste avant (un second appui ramène à la chaîne d'origine). */
    fun previousChannel() {
        val current = (_uiState.value.screen as? StreamiaScreen.Player)?.entry ?: return
        if (current.type != MediaType.Live) return
        val target = previousLiveEntry?.takeIf { it.key != current.key } ?: return
        zapJob?.cancel()
        _playerState.update { it.copy(pendingZapEntry = null) }
        openPlayer(target, returnToSeries = false)
    }

    fun dismissMessage() { _uiState.update { it.copy(message = null) } }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            showLogin()
        }
    }

    private fun openPlayer(entry: MediaEntry, returnToSeries: Boolean, returnToDetails: Boolean = false) {
        // Toute chaîne ouverte en plein écran compte, qu'on y arrive par zap ou via la liste Direct.
        if (entry.type == MediaType.Live) {
            lastLiveEntry?.takeIf { it.key != entry.key }?.let { previousLiveEntry = it }
            lastLiveEntry = entry
        }
        val profileId = _uiState.value.activeProfileId
        val resume = if (profileId != null && entry.type != MediaType.Live) repository.resumePosition(profileId, entry.key) else 0L
        _playerState.update { it.copy(epg = EpgNowContext()) }
        _uiState.update {
            it.copy(
                screen = StreamiaScreen.Player(entry, returnToSeries, returnToDetails),
                message = null,
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
        val profileId = _uiState.value.activeProfileId
        _uiState.update { it.copy(busy = true, mediaDetails = null, similarMedia = emptyList(), screen = StreamiaScreen.MovieDetails(movie), message = null) }
        viewModelScope.launch {
            val details = runCatching { repository.movieDetails(credentials, movie) }
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
                .getOrNull()
            if (profileId != null) {
                val resolvedDetails = details ?: _uiState.value.mediaDetails
                resolvedDetails?.let { runCatching { repository.cacheRecommendationDetails(profileId, it) } }
                loadSimilarMedia(profileId, movie, resolvedDetails)
            }
        }
    }

    private fun loadSeries(series: MediaEntry) {
        val credentials = _uiState.value.credentials ?: return
        val profileId = _uiState.value.activeProfileId
        _uiState.update { it.copy(busy = true, message = null, seriesDetails = null, similarMedia = emptyList(), screen = StreamiaScreen.Series(series)) }
        viewModelScope.launch {
            runCatching { repository.seriesDetails(credentials, series) }
                .onSuccess { details ->
                    _uiState.update { it.copy(busy = false, seriesDetails = details) }
                    if (profileId != null) {
                        details.details?.let { info -> runCatching { repository.cacheRecommendationDetails(profileId, info) } }
                        loadSimilarMedia(profileId, series, details.details)
                    }
                }
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
            localEpgNow(profileId, entry, now, offsetHours)?.let {
                updatePlayerEpgIfCurrent(entry, it)
                return@launch
            }

            // Dernier repli : l'EPG court Xtream, utile lors d'un tout premier démarrage avant
            // que le XMLTV local ait fini d'être réchauffé.
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

    /**
     * Programme en cours/suivant depuis les données locales uniquement (aucun réseau), du plus
     * précis au plus tolérant :
     * 1) requête SQLite courte quand l'alias fournisseur correspond exactement au XMLTV ;
     * 2) le guide déjà affiché, dont les alias sont plus tolérants ("FR: TF1 4K" ↔ "TF1.fr") et
     *    les horaires déjà décalés ;
     * 3) la journée courante (le Guide TV peut être resté sur hier/demain), relue depuis SQLite
     *    une fois puis gardée dans le petit LRU mémoire.
     */
    private suspend fun localEpgNow(profileId: String, entry: MediaEntry, now: Long, offsetHours: Int): EpgNowContext? {
        runCatching { repository.cachedEpgNow(profileId, entry, now, offsetHours) }.getOrNull()
            ?.takeUnless { it.isEmpty }
            ?.let { return it }

        _uiState.value.epgGuide?.forEntry(entry)?.epgNowContextAt(nowEpochSeconds = now)
            ?.takeUnless { it.isEmpty }
            ?.let { return it }

        return todayEpgGuide(profileId, offsetHours)?.forEntry(entry)?.epgNowContextAt(nowEpochSeconds = now)?.takeUnless { it.isEmpty }
    }

    private suspend fun todayEpgGuide(profileId: String, offsetHours: Int): EpgGuide? =
        epgGuideFor(profileId, LocalDate.now(ZoneId.systemDefault()), offsetHours)

    /**
     * Guide d'une journée : LRU mémoire, sinon relu une fois depuis SQLite puis mis en LRU (jamais de
     * réseau). Le guide du jour est aussi publié dans [StreamiaUiState.todayEpgGuide] pour la liste
     * des chaînes Direct.
     */
    private suspend fun epgGuideFor(profileId: String, date: LocalDate, offsetHours: Int): EpgGuide? {
        val guide = epgGuideMemoryCache.get(profileId, date, offsetHours) ?: runCatching {
            val (dayStart, dayEnd) = epgDayBounds(date)
            repository.cachedEpgGuide(
                profileId = profileId,
                displayStartEpochSeconds = dayStart,
                displayEndEpochSeconds = dayEnd,
                offsetHours = offsetHours,
            )
        }.getOrNull()?.also { epgGuideMemoryCache.put(profileId, date, offsetHours, it) }
        if (guide != null && date == LocalDate.now(ZoneId.systemDefault())) {
            _uiState.update { state ->
                if (state.activeProfileId == profileId && state.todayEpgGuide !== guide) state.copy(todayEpgGuide = guide) else state
            }
        }
        return guide
    }

    private fun updatePlayerEpgIfCurrent(entry: MediaEntry, context: EpgNowContext) {
        val current = (_uiState.value.screen as? StreamiaScreen.Player)?.entry
        if (current?.key == entry.key) _playerState.update { it.copy(epg = context) }
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
                val now = System.currentTimeMillis() / 1000
                localEpgNow(profileId, current, now, state.appSettings.epgTimeOffsetHours)
                    ?.let { updatePlayerEpgIfCurrent(current, it) }
            }
        }
    }

    private fun startEpgBackgroundSync(force: Boolean = false) {
        val state = _uiState.value
        val profileId = state.activeProfileId ?: return
        val credentials = state.credentials ?: return
        if (state.catalogHydrating) return
        val catalog = state.catalog ?: return
        if (!catalog.isCategoryLoaded(MediaType.Live, Catalog.ALL_CATEGORY_ID)) return
        val liveEntries = catalog.entriesFor(MediaType.Live)
        if (liveEntries.isEmpty()) return

        if (epgSyncJob?.isActive == true) {
            if (epgSyncProfileId == profileId) return
            epgSyncJob?.cancel()
        }
        epgSyncProfileId = profileId
        epgSyncJob = viewModelScope.launch {
            try {
                val changed = repository.refreshEpg(
                    profileId = profileId,
                    credentials = credentials,
                    liveEntries = liveEntries,
                    force = force,
                )
                if (changed) {
                    epgGuideMemoryCache.clearProfile(profileId)
                    reresolveLiveOnSat()
                }
                if (_uiState.value.activeProfileId != profileId) return@launch
                val player = (_uiState.value.screen as? StreamiaScreen.Player)?.entry
                if (player?.type == MediaType.Live) loadEpg(player)
                if (_uiState.value.screen is StreamiaScreen.Epg) {
                    loadEpgGuideFromCache(
                        preferredDate = _uiState.value.epgSelectedDate,
                        forceMetadataRefresh = changed,
                    )
                } else if (changed) {
                    // La nouvelle version du guide est déjà en SQLite : réchauffer silencieusement
                    // aujourd'hui pour la prochaine ouverture du Guide TV.
                    warmEpgGuideCache(profileId)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (_uiState.value.activeProfileId == profileId && _uiState.value.screen is StreamiaScreen.Epg) {
                    _uiState.update { it.copy(epgLoading = false, message = error.safeMessage()) }
                }
            } finally {
                if (epgSyncProfileId == profileId) epgSyncProfileId = null
            }
        }
    }

    private fun loadEpgGuideFromCache(
        preferredDate: LocalDate?,
        forceMetadataRefresh: Boolean = false,
        prefetchNeighbors: Boolean = true,
    ) {
        val state = _uiState.value
        val profileId = state.activeProfileId ?: return
        val offsetHours = state.appSettings.epgTimeOffsetHours
        val sequence = ++epgGuideLoadSequence
        val knownDates = if (forceMetadataRefresh) emptyList() else state.epgAvailableDates
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        val optimisticDate = preferredDate?.takeIf { knownDates.isEmpty() || it in knownDates }
            ?: knownDates.firstOrNull { it == today }
            ?: knownDates.firstOrNull()
        if (optimisticDate != null && knownDates.isNotEmpty()) {
            val memoryGuide = epgGuideMemoryCache.get(profileId, optimisticDate, offsetHours)
            if (memoryGuide != null) {
                if (_uiState.value.screen is StreamiaScreen.Epg) {
                    _uiState.update { current ->
                        if (current.activeProfileId == profileId) {
                            current.copy(
                                epgGuide = memoryGuide,
                                epgAvailableDates = knownDates,
                                epgSelectedDate = optimisticDate,
                                epgLoading = false,
                            )
                        } else current
                    }
                }
                if (prefetchNeighbors) {
                    prefetchEpgNeighbors(profileId, optimisticDate, knownDates, offsetHours)
                }
                return
            }
        }

        viewModelScope.launch {
            val dates = if (knownDates.isNotEmpty()) {
                knownDates
            } else {
                val metadata = runCatching { repository.epgMetadata(profileId) }.getOrNull()
                if (sequence != epgGuideLoadSequence || _uiState.value.activeProfileId != profileId) return@launch
                if (metadata == null || metadata.programCount <= 0) {
                    if (_uiState.value.screen is StreamiaScreen.Epg) {
                        // Ne jamais jeter une grille déjà affichée sur une lecture transitoirement
                        // vide (une resynchro peut être en train de remplacer les lignes).
                        _uiState.update { current -> current.copy(epgLoading = false) }
                    }
                    return@launch
                }
                epgDates(metadata, offsetHours)
            }

            if (dates.isEmpty()) {
                if (_uiState.value.screen is StreamiaScreen.Epg) {
                    _uiState.update { it.copy(epgLoading = false) }
                }
                return@launch
            }

            val date = preferredDate?.takeIf { it in dates }
                ?: dates.firstOrNull { it == today }
                ?: dates.first()
            val memoryGuide = epgGuideMemoryCache.get(profileId, date, offsetHours)
            val guide = memoryGuide ?: runCatching {
                val (dayStart, dayEnd) = epgDayBounds(date)
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
            if (memoryGuide == null) {
                epgGuideMemoryCache.put(profileId, date, offsetHours, guide)
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
            if (prefetchNeighbors) {
                prefetchEpgNeighbors(profileId, date, dates, offsetHours)
            }
        }
    }

    /**
     * Au retour d'un profil, prépare la journée courante depuis SQLite avant même que l'utilisateur
     * ouvre le Guide TV. Aucun réseau ici : si le cache disque n'existe pas encore, on sort.
     */
    private fun warmEpgGuideCache(profileId: String) {
        val state = _uiState.value
        if (state.activeProfileId != profileId) return
        val offsetHours = state.appSettings.epgTimeOffsetHours
        val preferredDate = state.epgSelectedDate

        viewModelScope.launch {
            val metadata = runCatching { repository.epgMetadata(profileId) }.getOrNull() ?: return@launch
            if (metadata.programCount <= 0 || _uiState.value.activeProfileId != profileId) return@launch
            val dates = epgDates(metadata, offsetHours)
            if (dates.isEmpty()) return@launch

            val today = LocalDate.now(ZoneId.systemDefault())
            val date = preferredDate?.takeIf { it in dates }
                ?: dates.firstOrNull { it == today }
                ?: dates.first()
            val guide = epgGuideFor(profileId, date, offsetHours) ?: return@launch

            _uiState.update { current ->
                if (current.activeProfileId == profileId && current.screen !is StreamiaScreen.Epg) {
                    current.copy(
                        epgGuide = guide,
                        epgAvailableDates = dates,
                        epgSelectedDate = date,
                        epgLoading = false,
                    )
                } else current
            }

            // Si le player Live a été ouvert avant la fin du warm-up, son premier loadEpg() a pu
            // tomber sur un alias SQLite trop strict. Maintenant que le guide du jour est en RAM,
            // relancer immédiatement la résolution plutôt que d'attendre le ticker de 30 secondes.
            val player = (_uiState.value.screen as? StreamiaScreen.Player)?.entry
            if (player?.type == MediaType.Live && _playerState.value.epg.current == null) {
                loadEpg(player)
            }

            // Le warm-up au démarrage se limite volontairement à une seule journée pour ne pas
            // concurrencer le chargement du catalogue. Les jours voisins seront préchargés dès que
            // l'utilisateur ouvre réellement le Guide TV.
        }
    }

    private fun prefetchEpgNeighbors(
        profileId: String,
        date: LocalDate,
        availableDates: List<LocalDate>,
        offsetHours: Int,
    ) {
        val index = availableDates.indexOf(date)
        if (index < 0) return
        val neighbors = buildList {
            availableDates.getOrNull(index - 1)?.let(::add)
            availableDates.getOrNull(index + 1)?.let(::add)
        }.filter { epgGuideMemoryCache.get(profileId, it, offsetHours) == null }
        if (neighbors.isEmpty()) return

        // Une seule prélecture SQLite à la fois. Sur plusieurs milliers de chaînes, lancer J-1 et
        // J+1 en parallèle augmente inutilement la pression I/O et mémoire sur les boîtiers TV.
        epgPrefetchJob?.cancel()
        epgPrefetchJob = viewModelScope.launch {
            for (neighbor in neighbors) {
                if (_uiState.value.activeProfileId != profileId) return@launch
                epgGuideFor(profileId, neighbor, offsetHours)
            }
        }
    }

    /**
     * Construit les rangées « Recommandé pour vous »/« Parce que vous avez regardé… » de l'accueil.
     * Lecture bornée depuis SQLite, calcul CPU sur Dispatchers.Default, résultat jeté si le profil
     * actif a changé pendant le calcul.
     */
    private fun refreshHomeRecommendations(force: Boolean = false) {
        val state = _uiState.value
        val profileId = state.activeProfileId ?: return
        val catalog = state.catalog ?: return
        val nowMillis = System.currentTimeMillis()

        if (
            !force &&
            homeRecommendationLastBuiltProfileId == profileId &&
            nowMillis - homeRecommendationLastBuiltAtMillis < HOME_RECOMMENDATION_REBUILD_INTERVAL_MS
        ) {
            settleHomeBlocks(setOf(HomeBlock.Recommendations))
            return
        }
        if (!force && homeRecommendationJob?.isActive == true) return

        val library = state.library
        val excludedCategoryKeys = if (state.appSettings.parentalControlEnabled && !state.parentalUnlocked) {
            library.hiddenCategories + library.lockedCategories
        } else {
            library.hiddenCategories
        }
        val excludedCategoryIds = catalog.categories.asSequence()
            .filter { it.type != MediaType.Live && it.key in excludedCategoryKeys }
            .mapTo(mutableSetOf()) { it.id }

        val sequence = ++homeRecommendationBuildSequence
        homeRecommendationJob?.cancel()

        // Comme pour les Matchs : le scoring (similarité texte, décroissance des signaux) est du
        // CPU pur sur potentiellement plusieurs centaines de candidats, donc jamais sur Main.
        homeRecommendationJob = viewModelScope.launch(Dispatchers.Default) {
            // Fin du calcul (résultat, rien à recommander ou erreur) : plus de squelette, sauf si un
            // calcul plus récent a pris le relais.
            coroutineContext.job.invokeOnCompletion {
                if (sequence == homeRecommendationBuildSequence) settleHomeBlocks(setOf(HomeBlock.Recommendations))
            }
            val tasteSources = (
                library.history.sortedByDescending { it.updatedAt }.map { it.entry } +
                    library.favoriteEntries.mapNotNull(catalog::entry)
            ).distinctBy(MediaEntry::key).take(HOME_RECOMMENDATION_TASTE_SOURCE_LIMIT)
            val candidates = listOf(MediaType.Movie, MediaType.Series).flatMap { type ->
                val recent = runCatching {
                    repository.homeRecommendationCandidates(profileId, type, HOME_RECOMMENDATION_RECENT_LIMIT)
                }.getOrDefault(emptyList())
                val tasteCandidates = mutableListOf<MediaEntry>()
                for (source in tasteSources) {
                    if (source.type != type) continue
                    tasteCandidates += runCatching {
                        repository.similarityCandidates(profileId, source, HOME_RECOMMENDATION_PER_SOURCE_LIMIT)
                    }.getOrDefault(emptyList())
                }
                // Candidats liés aux goûts d'abord : avec « récents » en tête, la limite coupait
                // justement les contenus proches de ce que l'utilisateur regarde.
                (tasteCandidates + recent)
                    .distinctBy(MediaEntry::key)
                    .take(HOME_RECOMMENDATION_CANDIDATE_LIMIT)
            }

            if (sequence != homeRecommendationBuildSequence || _uiState.value.activeProfileId != profileId) return@launch
            if (candidates.isEmpty()) {
                _uiState.update { current ->
                    if (current.activeProfileId == profileId) current.copy(homeRecommendationRows = emptyList()) else current
                }
                return@launch
            }

            val historyEntries = library.history.map { it.entry }
            val favoriteEntries = library.favoriteEntries.mapNotNull(catalog::entry)
            val knownEntriesByKey = (historyEntries + favoriteEntries).associateBy(MediaEntry::key)
            val detailsSource = (candidates + historyEntries + favoriteEntries).distinctBy(MediaEntry::key)
            val detailsByKey = runCatching {
                repository.recommendationContentFeatures(profileId, detailsSource)
            }.getOrDefault(emptyMap())
            val feedback = runCatching { repository.recommendationFeedback(profileId) }.getOrDefault(emptyMap())
            val boostsBySource = tasteSources.associate { source ->
                source.key to runCatching {
                    repository.similarityBoosts(profileId, source, detailsByKey[source.key])
                }.getOrDefault(emptyMap())
            }

            if (sequence != homeRecommendationBuildSequence || _uiState.value.activeProfileId != profileId) return@launch

            val context = RecommendationBuildContext(
                candidates = candidates,
                detailsByKey = detailsByKey,
                profile = RecommendationProfileInput(
                    history = library.history.map { item ->
                        ViewingRecord(item.entry, item.positionMs, item.durationMs, item.updatedAt)
                    },
                    favoriteEntries = library.favoriteEntries,
                    watchedEntries = library.watchedEntries,
                    knownEntriesByKey = knownEntriesByKey,
                    feedback = feedback,
                    hiddenEntries = library.hiddenEntries,
                    hiddenCategoryIds = excludedCategoryIds,
                ),
                nowMillis = nowMillis,
                boostsBySource = boostsBySource,
            )
            val snapshot = recommendationEngine.buildSnapshot(profileId, context)
            // Sections JustWatch en parallèle : chacune fait plusieurs appels réseau et recherches.
            val justWatchRows = JustWatchSection.entries.map { section ->
                async {
                    val entries = runCatching { repository.justWatch(profileId, section, JUSTWATCH_ROW_LIMIT * 2) }
                        .getOrDefault(emptyList())
                        .filterNot { it.key in library.hiddenEntries || it.categoryId in excludedCategoryIds }
                        .take(JUSTWATCH_ROW_LIMIT)
                    // Un Top 10 n'a que 10 titres : quelques-uns suffisent à former une rangée.
                    val minimum = when (section) {
                        JustWatchSection.TopMoviesWeek, JustWatchSection.TopSeriesWeek -> 1
                        else -> JUSTWATCH_ROW_MIN
                    }
                    entries.takeIf { it.size >= minimum }?.let { items ->
                        RecommendationRow(
                            when (section) {
                                JustWatchSection.TopMoviesWeek -> RecommendationRowKind.JustWatchTopMoviesWeek
                                JustWatchSection.TopSeriesWeek -> RecommendationRowKind.JustWatchTopSeriesWeek
                                JustWatchSection.PopularMovies -> RecommendationRowKind.JustWatchPopularMovies
                                JustWatchSection.PopularSeries -> RecommendationRowKind.JustWatchPopularSeries
                                JustWatchSection.NewMovies -> RecommendationRowKind.JustWatchNewMovies
                                JustWatchSection.NewSeries -> RecommendationRowKind.JustWatchNewSeries
                            },
                            section.title,
                            items.map { RecommendedMedia(it, score = 0.0) },
                        )
                    }
                }
            }.awaitAll().filterNotNull()
            val rows = snapshot.rows + justWatchRows

            if (sequence != homeRecommendationBuildSequence || _uiState.value.activeProfileId != profileId) return@launch
            homeRecommendationLastBuiltProfileId = profileId
            homeRecommendationLastBuiltAtMillis = System.currentTimeMillis()
            _uiState.update { current ->
                if (current.activeProfileId == profileId) current.copy(homeRecommendationRows = rows) else current
            }
        }
    }

    /**
     * Calcule « Films/Séries similaires » en deux passes :
     * 1) ranking immédiat sur toutes les métadonnées déjà disponibles en SQLite ;
     * 2) si la rangée reste trop pauvre, enrichissement réseau borné de quelques candidats puis
     *    nouveau ranking. Les détails récupérés sont persistés, donc ce coût disparaît ensuite.
     *
     * Le moteur exige une relation descriptive réelle (genre, intrigue, casting, réalisateur,
     * saga ou similarité sémantique) : une simple catégorie IPTV commune ne suffit plus.
     */
    private suspend fun loadSimilarMedia(profileId: String, entry: MediaEntry, details: MediaDetails?) {
        val initialState = _uiState.value
        val hiddenEntries = initialState.library.hiddenEntries
        val excludedCategoryKeys = if (
            initialState.appSettings.parentalControlEnabled && !initialState.parentalUnlocked
        ) {
            initialState.library.hiddenCategories + initialState.library.lockedCategories
        } else {
            initialState.library.hiddenCategories
        }
        val hiddenCategoryIds = initialState.catalog
            ?.categories
            .orEmpty()
            .asSequence()
            .filter { it.key in excludedCategoryKeys }
            .mapTo(mutableSetOf()) { it.id }

        // Résumé anglais + mots-clés TMDB : même langue que le reste du catalogue enrichi.
        val sourceFeatures = repository.withTmdb(profileId, ContentFeatures.from(entry, details))
        val candidates = runCatching {
            repository.similarityCandidates(profileId, entry, SIMILAR_CANDIDATE_LIMIT, sourceFeatures)
        }.getOrDefault(emptyList())
        if (candidates.isEmpty()) return
        val boosts = runCatching { repository.similarityBoosts(profileId, entry, sourceFeatures) }.getOrDefault(emptyMap())

        val detailsByKey = runCatching {
            repository.recommendationContentFeatures(profileId, candidates)
        }.getOrDefault(emptyMap()).toMutableMap()

        fun isStillCurrent(): Boolean = when (val screen = _uiState.value.screen) {
            is StreamiaScreen.MovieDetails -> screen.movie.key == entry.key
            is StreamiaScreen.Series -> screen.series.key == entry.key
            else -> false
        }

        fun publish(items: List<RecommendedMedia>) {
            _uiState.update { state ->
                val onSameScreen = when (val screen = state.screen) {
                    is StreamiaScreen.MovieDetails -> screen.movie.key == entry.key
                    is StreamiaScreen.Series -> screen.series.key == entry.key
                    else -> false
                }
                if (onSameScreen) state.copy(similarMedia = items) else state
            }
        }

        suspend fun rank(): List<RecommendedMedia> = withContext(Dispatchers.Default) {
            recommendationEngine.similarTo(
                source = sourceFeatures,
                candidates = candidates,
                detailsByKey = detailsByKey,
                hiddenEntries = hiddenEntries,
                hiddenCategoryIds = hiddenCategoryIds,
                limit = SIMILAR_RESULT_LIMIT,
                minimumScore = SIMILAR_DETAIL_MIN_SCORE,
                boosts = boosts,
            )
        }

        var similar = rank()
        publish(similar)
        if (similar.size >= SIMILAR_TARGET_COUNT || !isStillCurrent()) return

        val credentials = _uiState.value.credentials ?: return
        val currentResultKeys = similar.mapTo(mutableSetOf()) { it.entry.key }

        // Priorité aux résultats déjà plausibles mais pas encore enrichis, puis à la catégorie
        // source. La borne stricte évite de transformer l'ouverture d'une fiche en import massif.
        val enrichmentCandidates = candidates
            .asSequence()
            .filter { it.type == entry.type }
            .filterNot {
                it.key == entry.key ||
                    it.key in hiddenEntries ||
                    it.categoryId in hiddenCategoryIds ||
                    detailsByKey[it.key]?.enriched == true
            }
            .sortedWith(
                compareByDescending<MediaEntry> { it.key in currentResultKeys }
                    .thenByDescending { it.categoryId == entry.categoryId }
                    .thenByDescending { !it.plot.isNullOrBlank() }
                    .thenByDescending { it.rating ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.addedAtEpochSeconds ?: 0L },
            )
            .take(SIMILAR_ENRICH_LIMIT)
            .toList()

        for (batch in enrichmentCandidates.chunked(SIMILAR_ENRICH_CONCURRENCY)) {
            if (!isStillCurrent()) return

            val enriched = supervisorScope {
                batch.map { candidate ->
                    async {
                        runCatching {
                            repository.enrichRecommendationDetails(profileId, credentials, candidate)
                        }.getOrNull()
                    }
                }.awaitAll()
            }

            enriched.filterNotNull().forEach { features ->
                detailsByKey[features.entry.key] = features
            }
            if (enriched.none { it != null }) continue

            similar = rank()
            publish(similar)
            if (similar.size >= SIMILAR_TARGET_COUNT) return
        }
    }

    /**
     * Charge la grille MENA beIN SPORTS et en extrait les émissions actuellement diffusées et
     * suivantes. Le scrape ne dépend pas du profil ; seule la résolution vers la playlist dépend
     * du catalogue Live courant.
     */
    /**
     * Un guide tiers de l'accueil (FR en direct / ce soir, beIN, UK). Le scrape ne dépend d'aucun
     * profil ; seul le rapprochement avec les chaînes Direct en dépend, fait hors thread UI et
     * publié seulement s'il est toujours le plus récent pour le profil actif (séquences).
     */
    private inner class HomeGuide<Raw : Any>(
        /** Blocs de l'accueil alimentés par ce guide : squelette tant que le premier chargement n'est pas fini. */
        private val blocks: Set<HomeBlock>,
        private val fetch: suspend (forceRefresh: Boolean) -> Raw,
        private val isEmpty: (Raw) -> Boolean,
        private val match: (Raw, Catalog, visible: (MediaEntry) -> Boolean) -> (StreamiaUiState) -> StreamiaUiState,
    ) {
        private var loadJob: Job? = null
        private var loadSequence = 0L
        private var resolveSequence = 0L
        private var raw: Raw? = null

        fun load(forceRefresh: Boolean) {
            val profileId = _uiState.value.activeProfileId ?: return
            if (!forceRefresh && loadJob?.isActive == true) return
            if (forceRefresh) loadJob?.cancel()
            val sequence = ++loadSequence
            loadJob = viewModelScope.launch {
                runCatching { fetch(forceRefresh) }.onSuccess { fetched ->
                    if (sequence != loadSequence || _uiState.value.activeProfileId != profileId) return@onSuccess
                    raw = fetched
                    resolve()
                }.onFailure { error ->
                    if (error !is CancellationException && sequence == loadSequence) settleHomeBlocks(blocks)
                }
            }
        }

        fun resolve() {
            val state = _uiState.value
            val profileId = state.activeProfileId ?: return
            val catalog = state.catalog ?: return
            val fetched = raw?.takeUnless(isEmpty) ?: run {
                // Chargé mais vide (aucun programme) : la rangée disparaît au lieu de rester en squelette.
                if (raw != null) settleHomeBlocks(blocks)
                return
            }
            val excludedCategoryKeys = if (state.appSettings.parentalControlEnabled && !state.parentalUnlocked) {
                state.library.hiddenCategories + state.library.lockedCategories
            } else {
                state.library.hiddenCategories
            }
            val excludedCategoryIds = catalog.categories.asSequence()
                .filter { it.type == MediaType.Live && it.key in excludedCategoryKeys }
                .mapTo(mutableSetOf()) { it.id }
            val hiddenEntries = state.library.hiddenEntries
            val sequence = ++resolveSequence
            viewModelScope.launch(Dispatchers.Default) {
                val publish = match(fetched, catalog) { channel ->
                    channel.key !in hiddenEntries && channel.categoryId !in excludedCategoryIds
                }
                if (sequence != resolveSequence) return@launch
                _uiState.update { latest ->
                    if (latest.activeProfileId == profileId) publish(latest).copy(homePendingBlocks = latest.homePendingBlocks - blocks) else latest
                }
            }
        }

        fun reset() {
            loadJob?.cancel()
            loadJob = null
            loadSequence += 1
            resolveSequence += 1
            raw = null
        }
    }

    /**
     * Charge (ou réutilise le cache si assez récent) les matchs du jour scrapés depuis
     * liveonsat.com, puis résout leurs diffuseurs contre les chaînes Direct de ce profil. Le scrape
     * lui-même ne dépend d'aucun profil ; seule cette résolution en dépend.
     */
    private fun loadLiveOnSatMatches(forceRefresh: Boolean) {
        // Appelé à chaque ouverture de l'app (openProfile/resumeStartup/showCatalog) : un chargement
        // déjà en vol pour la même raison ne doit pas en déclencher un second en parallèle. Un
        // forceRefresh explicite (bouton Actualiser) passe toujours devant : il annule le chargement
        // en cours plutôt que d'en laisser deux tourner (double scrape de liveonsat.com).
        if (!forceRefresh && liveOnSatLoadJob?.isActive == true) return
        if (forceRefresh) liveOnSatLoadJob?.cancel()
        val sequence = ++liveOnSatLoadSequence
        liveOnSatNextCheckAtMillis = System.currentTimeMillis() + LIVE_ONSAT_RETRY_MS
        _uiState.update { it.copy(liveOnSatLoading = true, liveOnSatError = null) }
        liveOnSatLoadJob = viewModelScope.launch {
            // Scrape + rapprochement des chaînes terminés (ou en échec) : plus de squelette.
            coroutineContext.job.invokeOnCompletion {
                if (sequence == liveOnSatLoadSequence) _uiState.update { it.copy(liveOnSatPending = false, liveOnSatResolving = false) }
            }
            val result = runCatching { repository.loadLiveOnSatMatches(forceRefresh) }
            if (sequence != liveOnSatLoadSequence) return@launch

            result.onSuccess { fetch ->
                // Cache encore valable : prochain rechargement quand il atteint 2 h. Cache expiré
                // renvoyé quand même (scrape en échec) : nouvel essai dans LIVE_ONSAT_RETRY_MS.
                val expiresAt = fetch.fetchedAtEpochMillis + XtreamRepository.LIVE_ONSAT_CACHE_MAX_AGE_MS
                if (expiresAt > System.currentTimeMillis()) liveOnSatNextCheckAtMillis = expiresAt
                // Chaînes déjà rapprochées pour ce même scrape, cette même playlist et ce même EPG :
                // réutilisées telles quelles, sinon rapprochement refait puis réenregistré.
                val profileId = _uiState.value.activeProfileId
                val version = profileId?.let { id -> runCatching { repository.liveOnSatResolutionVersion(id, fetch) }.getOrNull() }
                val cachedResolution = if (profileId == null || version == null) null else {
                    runCatching { repository.cachedLiveOnSatResolution(profileId, version, fetch) }.getOrNull()
                }?.let { resolved ->
                    val catalog = _uiState.value.catalog ?: return@let resolved
                    resolved.map { match ->
                        match.copy(matchedChannels = match.matchedChannels.mapValues { (_, channels) -> channels.map { catalog.entry(it.key) ?: it } })
                    }
                }
                if (sequence != liveOnSatLoadSequence) return@launch
                // Phase 1 : afficher immédiatement tous les matchs du jour, sans attendre la
                // résolution des chaînes ni l'enrichissement EPG (les deux étapes coûteuses).
                _uiState.update {
                    it.copy(
                        liveOnSatLoading = false,
                        liveOnSatMatches = cachedResolution
                            ?: it.liveOnSatMatchesFor(fetch)
                            ?: fetch.matches.map { match -> ResolvedLiveOnSatMatch(match, emptyMap()) },
                        liveOnSatFetchedAtEpochMillis = fetch.fetchedAtEpochMillis,
                        liveOnSatError = if (forceRefresh && fetch.fromCache) {
                            "Actualisation impossible, affichage des données précédentes."
                        } else {
                            null
                        },
                    )
                }
                // Phase 2 : résoudre les chaînes + EPG en arrière-plan, par lots progressifs.
                if (cachedResolution == null && profileId != null && version != null) {
                    resolveLiveOnSatChannels(sequence, profileId, version, fetch)
                }
            }.onFailure { error ->
                if (sequence != liveOnSatLoadSequence) return@launch
                _uiState.update { it.copy(liveOnSatLoading = false, liveOnSatError = error.safeMessage()) }
            }
        }
    }

    /**
     * Deuxième phase de [loadLiveOnSatMatches] : associe chaque diffuseur à une chaîne du profil et
     * enrichit les horaires via l'EPG. L'index des chaînes Direct n'est construit qu'une fois, puis
     * les matchs sont résolus par paquets de [LIVE_ONSAT_RESOLVE_BATCH], avec une mise à jour d'état
     * après chaque paquet : les matchs s'affichent donc d'abord, puis leurs chaînes reconnues
     * apparaissent progressivement, sans jamais bloquer l'affichage initial.
     */
    private suspend fun resolveLiveOnSatChannels(sequence: Long, profileId: String, version: String, fetch: LiveOnSatFetchResult) {
        val matches = fetch.matches
        if (matches.isEmpty()) return
        val state = _uiState.value
        if (state.activeProfileId != profileId) return
        // Page Matchs : chaînes fantômes dès maintenant — la préparation (chaînes Direct depuis
        // SQLite, index) prend déjà plusieurs secondes sur un gros catalogue. Fin du job = retrait.
        _uiState.update { if (sequence == liveOnSatLoadSequence) it.copy(liveOnSatResolving = true) else it }
        val catalog = state.catalog

        // Le matcher a besoin des catégories (bouquet AR pour "beIN Connect MENA"...), pas
        // seulement des chaînes : on réutilise le catalogue chargé, ou on en reconstitue un minimal
        // à partir des catégories déjà connues quand la section Direct n'est pas encore matérialisée.
        val matcherCatalog = if (catalog?.isCategoryLoaded(MediaType.Live, Catalog.ALL_CATEGORY_ID) == true) {
            catalog
        } else {
            val liveChannels = withContext(Dispatchers.IO) {
                runCatching { repository.loadSection(profileId, MediaType.Live) }.getOrDefault(emptyList())
            }
            Catalog(categories = catalog?.categories.orEmpty(), entries = liveChannels)
        }
        if (matcherCatalog.entriesFor(MediaType.Live).isEmpty()) return

        val todayGuide = todayEpgGuide(profileId, state.appSettings.epgTimeOffsetHours)

        val index = withContext(Dispatchers.Default) { liveOnSatChannelMatcher.buildIndex(matcherCatalog) }
        // Recalcul des mêmes matchs (playlist ou EPG renouvelés) : l'ancien résultat reste affiché
        // pour les paquets pas encore refaits, au lieu de faire disparaître les chaînes.
        val resolved = (state.liveOnSatMatchesFor(fetch) ?: matches.map { ResolvedLiveOnSatMatch(it, emptyMap()) }).toMutableList()

        var offset = 0
        while (offset < matches.size) {
            if (sequence != liveOnSatLoadSequence) return
            val end = minOf(offset + LIVE_ONSAT_RESOLVE_BATCH, matches.size)
            val chunk = withContext(Dispatchers.Default) {
                (offset until end).map { i ->
                    val match = matches[i]
                    val matched = match.channels.mapNotNull { channel ->
                        liveOnSatChannelMatcher.matchAll(index, channel.name)
                            .takeIf(List<MediaEntry>::isNotEmpty)
                            ?.let { entries -> channel.name to entries }
                    }.toMap()
                    ResolvedLiveOnSatMatch(match, matched).withEpgTiming(todayGuide)
                }
            }
            for (i in offset until end) resolved[i] = chunk[i - offset]
            _uiState.update { current ->
                if (sequence != liveOnSatLoadSequence) current
                else current.copy(liveOnSatMatches = resolved.toList())
            }
            offset = end
        }
        // Gardé jusqu'au prochain scrape : les prochaines ouvertures sautent tout ce calcul.
        repository.saveLiveOnSatResolution(profileId, version, resolved)
    }

    /** Premier chargement de ces blocs terminé : l'accueil remplace leur squelette par le contenu, ou rien. */
    private fun settleHomeBlocks(blocks: Set<HomeBlock>) {
        _uiState.update { if (it.homePendingBlocks.any(blocks::contains)) it.copy(homePendingBlocks = it.homePendingBlocks - blocks) else it }
    }

    /** Matchs affichés s'ils proviennent déjà de ce même scrape. */
    private fun StreamiaUiState.liveOnSatMatchesFor(fetch: LiveOnSatFetchResult): List<ResolvedLiveOnSatMatch>? =
        liveOnSatMatches.takeIf { liveOnSatFetchedAtEpochMillis == fetch.fetchedAtEpochMillis && it.size == fetch.matches.size }

    /**
     * Playlist actualisée ou EPG resynchronisé : relit les matchs (cache, sans scrape s'il a moins
     * de 2 h) pour refaire le rapprochement des chaînes, dont la version enregistrée ne correspond plus.
     */
    private fun reresolveLiveOnSat() {
        if (_uiState.value.liveOnSatMatches.isNotEmpty()) loadLiveOnSatMatches(forceRefresh = false)
    }

    private fun epgDayBounds(date: LocalDate): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return date.atStartOfDay(zone).toEpochSecond() to
            date.plusDays(1).atStartOfDay(zone).toEpochSecond()
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
                            // Le catalogue TV/Films/Séries et le cache EPG ont des cycles de vie
                            // indépendants. Une réconciliation du catalogue ne doit jamais effacer
                            // un guide déjà restauré depuis SQLite : c'était précisément ce qui
                            // faisait disparaître l'EPG après une relance de l'application.
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
                    ensureSectionLoaded(MediaType.Live)
                    refreshHomeRecommendations()
                    if (loaded.source == CatalogSource.Network || loaded.source == CatalogSource.Import) reresolveLiveOnSat()
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
        previousLiveEntry = null
        lastLiveEntry = null
        secondaryLoadsJob?.cancel()
        zapJob?.cancel()
        _playerState.value = PlayerUiState()
        epgGuideMemoryCache.clear()
        homeRecommendationJob?.cancel()
        homeRecommendationJob = null
        homeRecommendationBuildSequence += 1
        homeRecommendationLastBuiltProfileId = null
        homeRecommendationLastBuiltAtMillis = 0L
        homeGuides.forEach { it.reset() }
        epgPrefetchJob?.cancel()
        epgPrefetchJob = null
        epgSyncJob?.cancel()
        epgSyncJob = null
        epgSyncProfileId = null
        epgChannelJob?.cancel()
        epgTickerJob?.cancel()
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
        warmEpgGuideCache(loaded.profileId)
        ensureSectionLoaded(MediaType.Live)
        refreshHomeRecommendations()
        scheduleSecondaryLoads(STARTUP_SECONDARY_LOADS_HOME_DELAY_MS)
    }

    /**
     * Guides tiers (scraping + parsing Jsoup) lancés après le premier affichage et l'un après
     * l'autre plutôt que tous en même temps que le catalogue et la première image vidéo : sur un
     * boîtier à 4 petits cœurs, ils se disputaient le CPU au moment où l'utilisateur navigue.
     */
    private fun scheduleSecondaryLoads(initialDelayMs: Long) {
        secondaryLoadsJob?.cancel()
        secondaryLoadsJob = viewModelScope.launch {
            delay(initialDelayMs)
            val loads = listOf<() -> Unit>(
                { tvProgrammeNowGuide.load(forceRefresh = false) },
                { beinSportsGuide.load(forceRefresh = false) },
                { ukGuide.load(forceRefresh = false) },
                { tvProgrammeTonightGuide.load(forceRefresh = false) },
                { loadLiveOnSatMatches(forceRefresh = false) },
            )
            loads.forEach { load ->
                load()
                delay(SECONDARY_LOADS_GAP_MS)
            }
        }
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
    val epgGuide: EpgGuide? = null,
    /** Guide de la journée courante (horaires déjà décalés) : programme en cours de la liste Direct. */
    val todayEpgGuide: EpgGuide? = null,
    val epgAvailableDates: List<LocalDate> = emptyList(),
    val epgSelectedDate: LocalDate? = null,
    val epgLoading: Boolean = false,
    val homeRecommendationRows: List<RecommendationRow> = emptyList(),
    /** Blocs de l'accueil dont le premier chargement n'est pas fini (squelette affiché). */
    val homePendingBlocks: Set<HomeBlock> = HomeBlock.entries.toSet() - HomeBlock.Resume - HomeBlock.Favorites,
    /** Premier chargement des matchs liveonsat pas encore fini (squelette « Matchs en direct »). */
    val liveOnSatPending: Boolean = true,
    /** Rapprochement des chaînes liveonsat en cours (page Matchs : chaînes fantômes). */
    val liveOnSatResolving: Boolean = false,
    val homeTvProgrammeNow: List<ResolvedTvProgrammeNowItem> = emptyList(),
    val homeTvProgrammeTonight: List<ResolvedTvProgrammeItem> = emptyList(),
    val homeBeinSportsNow: List<ResolvedBeinProgrammeItem> = emptyList(),
    val homeBeinSportsNext: List<ResolvedBeinProgrammeItem> = emptyList(),
    val homeUkGuideNow: List<ResolvedUkProgrammeItem> = emptyList(),
    val homeUkGuideNext: List<ResolvedUkProgrammeItem> = emptyList(),
    /** Ville effective de l'en-tête (réglée, ou détectée d'après la connexion). */
    val weatherPlace: HomePlace? = null,
    val weather: CurrentWeather? = null,
    val similarMedia: List<RecommendedMedia> = emptyList(),
    val liveOnSatMatches: List<ResolvedLiveOnSatMatch> = emptyList(),
    val liveOnSatLoading: Boolean = false,
    val liveOnSatError: String? = null,
    val liveOnSatFetchedAtEpochMillis: Long? = null,
    val resumePositionMs: Long = 0,
    val browserType: MediaType? = null,
    val browserCategoryId: String? = null,
    /**
     * Catégories dont une page est en cours de lecture SQLite, clés [Catalog.categoryKey]. Permet à
     * l'interface d'afficher « Chargement… » plutôt que « Aucun contenu » pendant l'ouverture d'une
     * catégorie Films/Séries encore jamais parcourue.
     */
    val loadingCategoryKeys: Set<String> = emptySet(),
    val searchQuery: String = "",
    val searchType: MediaType? = null,
    val contentReturnContext: ContentReturnContext? = null,
    /**
     * Pile des écrans de menu traversés (Accueil, Direct/Films/Séries, Paramètres, Recherche, EPG,
     * Outils…). Elle permet à Retour de revenir à l'écran d'où l'on vient au lieu de l'accueil.
     */
    val menuBackStack: List<StreamiaScreen> = emptyList(),
    /** Carte de l'accueil à refocaliser au retour (voir [HomeFocusTarget]). */
    val homeFocusTarget: HomeFocusTarget? = null,
    val lastViewedEntry: MediaEntry? = null,
)

/**
 * État du lecteur qui change souvent (EPG courant rafraîchi toutes les 30 s, zap en cours) : dans
 * son propre flux, lu seulement par l'écran lecteur, pour ne pas invalider la racine de l'app.
 */
data class PlayerUiState(
    val epg: EpgNowContext = EpgNowContext(),
    /** Chaîne annoncée par un zap rapide en cours, pas encore lancée (voir [StreamiaViewModel.zap]). */
    val pendingZapEntry: MediaEntry? = null,
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
    data object LiveMatches : StreamiaScreen
    data class MovieDetails(val movie: MediaEntry) : StreamiaScreen
    data class Series(val series: MediaEntry) : StreamiaScreen
    data class Player(
        val entry: MediaEntry,
        val returnToSeries: Boolean = false,
        /** Lecture lancée depuis la fiche du film : Retour rouvre la fiche au lieu de la liste. */
        val returnToDetails: Boolean = false,
    ) : StreamiaScreen
}

private const val EPG_PLAYER_REFRESH_MS = 30_000L
private const val ZAP_SETTLE_MS = 350L
private const val STARTUP_SECONDARY_LOADS_HOME_DELAY_MS = 1_200L
private const val STARTUP_SECONDARY_LOADS_PLAYER_DELAY_MS = 8_000L
private const val SECONDARY_LOADS_GAP_MS = 600L
private const val CATALOG_BACKGROUND_REFRESH_DELAY_MS = 20_000L
private const val MAX_EPG_DAY_SPAN = 30L
private const val HOME_RECOMMENDATION_CANDIDATE_LIMIT = 400
private const val JUSTWATCH_ROW_LIMIT = 20
private const val JUSTWATCH_ROW_MIN = 5
private const val HOME_RECOMMENDATION_RECENT_LIMIT = 240
private const val HOME_RECOMMENDATION_TASTE_SOURCE_LIMIT = 4
private const val HOME_RECOMMENDATION_PER_SOURCE_LIMIT = 80
private const val HOME_RECOMMENDATION_REBUILD_INTERVAL_MS = 5 * 60_000L
private const val LIVE_ONSAT_RESOLVE_BATCH = 20
private const val LIVE_ONSAT_RETRY_MS = 15 * 60_000L
private const val SIMILAR_CANDIDATE_LIMIT = 300
private const val SIMILAR_RESULT_LIMIT = 12
private const val SIMILAR_TARGET_COUNT = 8
private const val SIMILAR_ENRICH_LIMIT = 12
private const val SIMILAR_ENRICH_CONCURRENCY = 4
private const val SIMILAR_DETAIL_MIN_SCORE = 0.28

class StreamiaViewModelFactory(private val repository: XtreamRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = StreamiaViewModel(repository) as T
}

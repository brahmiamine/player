package fr.streamia.tv.ui

import fr.streamia.tv.data.WatchNextPublisher
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.streamia.tv.data.AiProvider
import fr.streamia.tv.data.FicheInfo
import fr.streamia.tv.data.AiUsage
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.CatalogSource
import fr.streamia.tv.data.HomeBlock
import fr.streamia.tv.data.HomePlace
import fr.streamia.tv.data.PrayerMethod
import fr.streamia.tv.data.JustWatchSection
import fr.streamia.tv.data.VodSortOrder
import fr.streamia.tv.data.homeBlock
import fr.streamia.tv.data.LoadedCatalog
import fr.streamia.tv.data.PlaylistProfile
import fr.streamia.tv.data.hasSameCatalogLayoutAs
import fr.streamia.tv.data.XtreamRepository
import fr.streamia.tv.domain.AccountInfo
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.EpgNowContext
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaDetails
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.SeriesEpisode
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import fr.streamia.tv.domain.LiveVersionIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

class StreamiaViewModel(private val repository: XtreamRepository) : ViewModel() {
    /** État partagé avec les contrôleurs de domaine ci-dessous. */
    private val host = StreamiaStateHolder(viewModelScope, repository)
    private val _uiState get() = host.ui
    private val _homeState get() = host.home
    private val _playerState get() = host.player

    val uiState: StateFlow<StreamiaUiState> = host.ui.asStateFlow()
    val homeState: StateFlow<HomeUiState> = host.home.asStateFlow()
    val playerState: StateFlow<PlayerUiState> = host.player.asStateFlow()

    // Contrôleurs de domaine : chacun porte un morceau de l'application, le ViewModel les relie.
    // Les rappels croisés passent par des lambdas : chaque contrôleur ignore les autres.
    private val catalogPaging: CatalogPagingController = CatalogPagingController(host, onLiveSectionReady = {
        epg.startEpgBackgroundSync()
        homeGuides.resolveAll()
    })
    private val epg: EpgController = EpgController(host, onGuideReplaced = { liveOnSat.reresolveLiveOnSat() })
    private val liveOnSat: LiveOnSatController = LiveOnSatController(
        host,
        todayEpgGuide = { profileId: String, offsetHours: Int -> epg.todayEpgGuide(profileId, offsetHours) },
        liveSection = { profileId: String -> catalogPaging.liveSection(profileId) },
    )
    private val homeGuides = HomeGuidesController(host)
    private val recommendations = RecommendationsController(host)
    private val aiFeatures = AiController(
        host,
        openLive = { entry -> openRemoteEntry(entry) },
        resumeEntry = { entry -> resumeHomePlayback(entry) },
        openSearch = { query -> openSearchWith(query) },
    )

    /** Fonctions de l'assistant IA qui ont leur propre écran (voir [AiUiState]). */
    val aiState: StateFlow<AiUiState> = aiFeatures.state.asStateFlow()
    private val weather = WeatherController(host)
    private val updates = UpdateController(host)
    private val library = LibraryController(
        host,
        catalogLayoutMutation = catalogPaging.catalogLayoutMutation,
        mergeIntoCatalog = { profileId: String, merge: (Catalog) -> Catalog -> catalogPaging.mergeIntoCatalog(profileId, merge) },
    )
    private val liveZap = LiveZapController(host, openChannel = { entry -> openPlayer(entry, returnToSeries = false) })
    private val liveVersions = LiveVersionIndexController(host, liveSection = { profileId: String -> catalogPaging.liveSection(profileId) })

    /** Versions des chaînes du Direct (voir [LiveVersionIndexController]). */
    val liveVersionIndex: StateFlow<LiveVersionIndex?> get() = liveVersions.liveVersionIndex

    /** Programmes du jour par chaîne Direct (voir [liveEpgProgramsFlow]). */
    val liveEpgPrograms: StateFlow<Map<String, List<EpgProgram>>> = liveEpgProgramsFlow(host.ui, viewModelScope)

    /** Matchs du jour sans chaînes masquées (voir [visibleLiveOnSatMatchesFlow]). */
    val visibleLiveOnSatMatches: StateFlow<List<ResolvedLiveOnSatMatch>> = visibleLiveOnSatMatchesFlow(host.ui, host.home, viewModelScope)

    // --- API publique déléguée : l'interface garde un seul point d'entrée, le ViewModel. ---

    // Mises à jour de l'application
    fun checkForUpdate() = updates.checkForUpdate()
    fun dismissUpdateCheck() = updates.dismissUpdateCheck()
    fun installPendingUpdate() = updates.installPendingUpdate()
    fun openUpdateInstallPermission() = updates.openUpdateInstallPermission()
    fun resumePendingUpdateInstall() = updates.resumePendingUpdateInstall()

    // Assistant IA : chaque fonction est une action de l'utilisateur, jamais un appel automatique à la frappe
    fun searchWithAi(query: String, type: MediaType?) = aiFeatures.searchWithAi(query, type)
    fun clearAiSearch() = aiFeatures.clearAiSearch()
    fun startTonight(answers: fr.streamia.tv.data.TonightAnswers) = aiFeatures.startTonight(answers)
    fun resetTonight() = aiFeatures.resetTonight()
    fun loadCollections() = aiFeatures.loadCollections()
    fun loadBrief(force: Boolean = false) = aiFeatures.loadBrief(force)
    fun loadRecap() = aiFeatures.loadRecap()
    fun showAssistant(mode: AssistantMode) {
        if (!fr.streamia.tv.data.AiGate.active.value) return
        navigateToMenu(StreamiaScreen.Assistant(mode), HomeFocusTarget.Assistant)
    }

    /** Message du téléphone (voir [fr.streamia.tv.data.PhoneChatServer]) ; bloquant, appelé hors du thread principal. */
    fun handleRemoteMessage(text: String): String = kotlinx.coroutines.runBlocking { aiFeatures.handleRemote(text) }

    private fun openRemoteEntry(entry: MediaEntry) {
        liveZap.zapList = null
        detailsTrail.clear()
        _uiState.update { it.copy(contentReturnContext = null) }
        openEntryInternal(entry)
    }

    private fun openSearchWith(query: String) {
        showSearch()
        _uiState.update { it.copy(searchQuery = query, searchType = null) }
    }

    /** Contenu ouvert depuis un écran de l'assistant : Retour y ramène (voir [ContentReturnOrigin.Assistant]). */
    fun openAssistantEntry(entry: MediaEntry, mode: AssistantMode) {
        liveZap.zapList = null
        detailsTrail.clear()
        _uiState.update { it.copy(contentReturnContext = ContentReturnContext.assistant(mode, entry.key)) }
        openEntryInternal(entry)
    }

    // Accueil : météo et matchs du jour
    fun refreshWeatherIfStale() = weather.refreshWeatherIfStale()
    suspend fun searchCities(query: String): List<HomePlace> = weather.searchCities(query)
    fun refreshLiveOnSatIfStale() = liveOnSat.refreshLiveOnSatIfStale()
    fun refreshLiveOnSatMatches() = liveOnSat.refreshLiveOnSatMatches()

    // Guide TV
    fun onTrimMemory(level: Int) = epg.onTrimMemory(level)
    fun selectEpgDate(date: LocalDate) = epg.selectEpgDate(date)
    fun reloadEpg() = epg.reloadEpg()
    suspend fun epgDescription(program: EpgProgram): String? = epg.epgDescription(program)

    // Pages du catalogue
    fun ensureCategoryLoaded(type: MediaType, categoryId: String, order: VodSortOrder) =
        catalogPaging.ensureCategoryLoaded(type, categoryId, order)
    fun loadMoreInCategory(type: MediaType, categoryId: String, order: VodSortOrder) =
        catalogPaging.loadMoreInCategory(type, categoryId, order)

    // Bibliothèque : favoris, masquages, contrôle parental, historique, organisateur
    fun toggleEntryFavorite(entry: MediaEntry) = library.toggleEntryFavorite(entry)
    fun toggleCategoryFavorite(category: MediaCategory) = library.toggleCategoryFavorite(category)
    fun toggleEntryHidden(entry: MediaEntry) = library.toggleEntryHidden(entry)
    fun toggleCategoryHidden(category: MediaCategory) = library.toggleCategoryHidden(category)
    fun toggleCategoryLocked(category: MediaCategory) = library.toggleCategoryLocked(category)
    fun toggleEntryWatched(entry: MediaEntry) = library.toggleEntryWatched(entry)
    fun setParentalPin(pin: String) = library.setParentalPin(pin)
    fun disableParentalControl() = library.disableParentalControl()
    suspend fun verifyParentalPin(pin: String): Boolean = library.verifyParentalPin(pin)
    fun recordPlayback(entry: MediaEntry, positionMs: Long, durationMs: Long, final: Boolean = false) =
        library.recordPlayback(entry, positionMs, durationMs, final)
    fun clearHistory(type: MediaType? = null) = library.clearHistory(type)
    fun setCategoryOrder(type: MediaType, categoryKeys: List<String>) = library.setCategoryOrder(type, categoryKeys)
    fun moveEntries(entryKeys: Set<String>, targetCategoryId: String) = library.moveEntries(entryKeys, targetCategoryId)
    fun resetEntryMoves(entryKeys: Set<String>) = library.resetEntryMoves(entryKeys)

    private var connectionTestSequence = 0L
    /** Fiches quittées pour un contenu similaire, rouvertes une à une par Retour. */
    private val detailsTrail = ArrayDeque<MediaEntry>()
    private var secondaryLoadsJob: Job? = null
    /**
     * Ouverture d'une liste et son actualisation différée (téléchargement du catalogue). Annulée
     * au changement de liste : sinon l'ancienne liste continuait de se télécharger et d'écrire en
     * base pendant que la nouvelle s'ouvrait.
     */
    private var profileLoadJob: Job? = null
    private var catalogRetryJob: Job? = null
    private val catalogRefreshLock = Mutex()

    private val startupData = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Lien « Continuer à regarder » en cours de traitement : la restauration habituelle s'efface. */
    @Volatile var resumeLinkPending: Boolean = false
        private set

    init {
        updates.start()
        liveVersions.start()
        resetUiState(StreamiaUiState(booting = true, screen = StreamiaScreen.Login))
        // Listes (déchiffrement Android Keystore) et réglages lus hors du thread principal : ils
        // retardaient la première image. StreamiaTvRoot attend [awaitStartupData] avant de choisir
        // quoi rouvrir.
        viewModelScope.launch {
            val (profiles, settings) = withContext(Dispatchers.IO) { repository.profiles() to repository.appSettings().also { repository.syncAi(it) } }
            _uiState.update { state ->
                if (state.activeProfileId == null) state.copy(profiles = profiles, appSettings = settings)
                else state.copy(profiles = profiles)
            }
            startupData.complete(Unit)
        }
    }

    /** Listes et réglages chargés (voir init). */
    suspend fun awaitStartupData() = startupData.await()

    /**
     * Données du profil qui sera rouvert (bibliothèque, liste, réglages) lues une première fois hors
     * du thread principal : les appels suivants, eux sur le thread principal, les trouvent en mémoire.
     */
    suspend fun prewarmProfile(profileId: String) = withContext(Dispatchers.IO) {
        repository.profile(profileId)
        repository.library(profileId)
        repository.appSettings()
    }

    fun finishStartup() {
        if (_uiState.value.activeProfileId == null) showLogin()
    }

    /** Réponse de [canResumeLiveOnStartup] lue hors du thread principal. */
    suspend fun canResumeLiveOnStartupAsync(profileId: String, entry: MediaEntry): Boolean =
        withContext(Dispatchers.IO) { canResumeLiveOnStartup(profileId, entry) }

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
        resetUiState(StreamiaUiState(
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
        ))
        // Après une fermeture complète Android recrée le ViewModel, donc le petit cache RAM EPG
        // repart vide. La base SQLite, elle, est persistante : la relire immédiatement ici remet
        // la journée courante en mémoire sans aucun appel réseau, même quand le démarrage reprend
        // directement le dernier flux Live et contourne openProfile()/showCatalog().
        epg.warmEpgGuideCache(profileId)
        // Reprise directe dans le lecteur : la vidéo passe d'abord, les guides tiers attendent.
        scheduleSecondaryLoads(fromPlayer = true)
        profileLoadJob?.cancel()
        profileLoadJob = viewModelScope.launch {
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
                    if (it is CancellationException) throw it
                    _uiState.update { state ->
                        if (state.activeProfileId == profileId) {
                            state.copy(catalogHydrating = false, offline = true, message = it.safeMessage())
                        } else state
                    }
                }
        }
        if (entry.type == MediaType.Live) {
            liveZap.onChannelResumed(entry)
            epg.startPlayerEpg(entry)
        } else {
            epg.stopPlayerEpg()
        }
    }

    /** Chaîne du Direct regardée à la fermeture : reprise directe au démarrage si elle n'est ni masquée ni verrouillée. */
    fun canResumeLiveOnStartup(profileId: String, entry: MediaEntry): Boolean {
        if (entry.type != MediaType.Live || repository.profile(profileId)?.credentialsOrNull() == null) return false
        val library = repository.library(profileId)
        val categoryKey = "${MediaType.Live.name}:${entry.categoryId}"
        if (entry.key in library.hiddenEntries || categoryKey in library.hiddenCategories) return false
        // Pas d'écran de code au démarrage : une catégorie verrouillée ne se rouvre pas toute seule.
        return !(repository.appSettings().parentalControlEnabled && categoryKey in library.lockedCategories)
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
        profileLoadJob?.cancel()
        profileLoadJob = viewModelScope.launch {
            val profile = repository.profile(profileId)
            val credentials = profile?.credentialsOrNull()
            val cachedCatalog = if (credentials != null) repository.cachedCatalog(profileId) else null
            if (credentials != null && cachedCatalog != null) {
                resetUiState(StreamiaUiState(
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
                ))
                epg.warmEpgGuideCache(profileId)
                recommendations.refreshHomeRecommendations()
                scheduleSecondaryLoads()
                try {
                    val loaded = repository.openProfile(profileId, knownCache = cachedCatalog)
                    mergeCatalog(loaded)
                    if (loaded.source == CatalogSource.Cache) {
                        // Catalogue expiré : actualisation silencieuse une fois l'accueil et ses guides
                        // chargés, pour ne pas concurrencer le démarrage (lectures WAL non bloquées).
                        delay(CATALOG_BACKGROUND_REFRESH_DELAY_MS)
                        refreshSilently(profileId)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
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
            } catch (cancellation: CancellationException) {
                throw cancellation
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
                epg.clearProfileMemory(profileId)
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
                    epg.startEpgBackgroundSync(force = true)
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
                    epg.clearProfileMemory(profileId)
                    _uiState.update {
                        it.copy(
                            busy = false,
                            profiles = repository.profiles(),
                            message = "Liste supprimée.",
                            returnProfileId = it.returnProfileId?.takeUnless { id -> id == profileId },
                        )
                    }
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
            val presentation = withContext(Dispatchers.IO) { library.libraryPresentation(profileId) }
            library.applyLibraryPresentation(profileId, presentation)
        }
        return message
    }

    fun openEntry(entry: MediaEntry) {
        // Contenu similaire / autre version ouvert depuis une fiche : la fiche quittée est empilée,
        // Retour y reviendra. Depuis tout autre écran, la pile repart de zéro.
        when (val screen = _uiState.value.screen) {
            is StreamiaScreen.MovieDetails -> if (screen.movie.key != entry.key) detailsTrail.addLast(screen.movie)
            is StreamiaScreen.Series -> if (screen.series.key != entry.key) detailsTrail.addLast(screen.series)
            is StreamiaScreen.Player -> Unit
            else -> detailsTrail.clear()
        }
        while (detailsTrail.size > MAX_DETAILS_TRAIL) detailsTrail.removeFirst()
        // Le Browser reste le retour par défaut. Depuis un détail (contenu similaire / retry),
        // conserver le contexte qui a amené l'utilisateur jusque-là au lieu de l'écraser.
        when (_uiState.value.screen) {
            is StreamiaScreen.Browser -> _uiState.update { it.copy(contentReturnContext = ContentReturnContext.browser(entry.key)) }
            // Guide TV : Retour depuis la chaîne ramène au guide, pas dans TV en direct.
            is StreamiaScreen.Epg -> {
                liveZap.zapList = null
                _uiState.update { it.copy(contentReturnContext = ContentReturnContext.epg(entry.key)) }
            }
            else -> Unit
        }
        openEntryInternal(entry)
    }

    /**
     * Chaîne lancée depuis la liste du Direct : CH+/CH− parcourent ensuite cette même liste
     * (catégorie, Favoris, Historique, dans l'ordre affiché) plutôt que la catégorie d'origine
     * de la chaîne dans l'ordre du fournisseur.
     */
    fun openLiveFromList(entry: MediaEntry, list: List<MediaEntry>) {
        liveZap.zapList = list.takeIf { candidates -> candidates.any { it.key == entry.key } }
        openEntry(entry)
    }

    fun openHomeEntry(entry: MediaEntry, rowKey: String, itemKey: String = entry.key) {
        liveZap.zapList = null
        detailsTrail.clear()
        _uiState.update { it.copy(contentReturnContext = ContentReturnContext.home(rowKey, itemKey)) }
        openEntryInternal(entry)
    }

    fun openSearchEntry(entry: MediaEntry) {
        liveZap.zapList = null
        detailsTrail.clear()
        _uiState.update { it.copy(contentReturnContext = ContentReturnContext.search(entry.key)) }
        openEntryInternal(entry)
    }

    fun openLiveMatchChannel(entry: MediaEntry, matchKey: String) {
        liveZap.zapList = null
        detailsTrail.clear()
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
        liveZap.zapList = null
        detailsTrail.clear()
        _uiState.update { it.copy(contentReturnContext = ContentReturnContext.home(HomeRowKey.Resume, entry.key)) }
        if (entry.type == MediaType.Movie) openPlayer(entry, returnToSeries = false) else openEntryInternal(entry)
    }

    fun resumePlayback(entry: MediaEntry) {
        if (entry.type == MediaType.Movie) openPlayer(entry, returnToSeries = false) else openEntry(entry)
    }

    fun playMovie(movie: MediaEntry, fromStart: Boolean = false) =
        openPlayer(movie, returnToSeries = false, returnToDetails = true, fromStart = fromStart)

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
        recommendations.refreshHomeRecommendations()
        homeGuides.resolveAll()
        homeGuides.tvProgrammeNowGuide.load(forceRefresh = false)
        homeGuides.beinSportsGuide.load(forceRefresh = false)
        homeGuides.ukGuide.load(forceRefresh = false)
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

    /** Consommé par l'accueil une fois la carte d'origine refocalisée (retour depuis un contenu). */
    fun consumeHomeRestore() {
        _uiState.update {
            if (it.contentReturnContext?.origin == ContentReturnOrigin.Home) it.copy(contentReturnContext = null) else it
        }
    }

    /** Consommé par la recherche mobile une fois la liste replacée sur le résultat ouvert. */
    fun consumeSearchRestore() {
        _uiState.update {
            if (it.contentReturnContext?.origin == ContentReturnOrigin.Search) it.copy(contentReturnContext = null) else it
        }
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

    /** Onglet « Plus » du mobile : racine de navigation, la pile des menus repart de zéro. */
    fun showMore() {
        _uiState.update { it.copy(screen = StreamiaScreen.More, menuBackStack = emptyList(), message = null) }
    }

    fun showSettings() = navigateToMenu(StreamiaScreen.Settings, HomeFocusTarget.Settings)
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
        if (_homeState.value.liveOnSatMatches.isEmpty()) liveOnSat.loadLiveOnSatMatches(forceRefresh = false) else liveOnSat.refreshLiveOnSatIfStale()
    }

    fun refreshTvProgrammeNow() = homeGuides.tvProgrammeNowGuide.load(forceRefresh = false)

    fun refreshBeinSportsGuide() = homeGuides.beinSportsGuide.load(forceRefresh = false)

    fun refreshUkGuide() = homeGuides.ukGuide.load(forceRefresh = false)

    /** null = revenir à la détection d'après la connexion. */
    fun setHomePlace(place: HomePlace?) {
        updateAppSettings { it.copy(homePlace = place) }
        weather.forgetPending()
        _homeState.update { it.copy(weatherPlace = place, weather = null) }
        weather.refreshWeatherIfStale()
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

    fun cycleDisplayModeSwitch() {
        updateAppSettings { it.copy(displayModeSwitch = it.nextDisplayModeSwitch()) }
    }

    fun toggleAi() {
        updateAppSettings { it.copy(aiEnabled = !it.aiEnabled) }
    }

    fun toggleAiBilingual() {
        updateAppSettings { it.copy(aiBilingualSubtitles = !it.aiBilingualSubtitles) }
    }

    fun toggleAiNightly() {
        updateAppSettings { it.copy(aiNightly = !it.aiNightly) }
    }

    fun setAiLanguage(code: String) {
        updateAppSettings { it.copy(aiLanguage = code) }
    }

    fun setAiProvider(provider: AiProvider) {
        updateAppSettings { it.copy(aiProvider = provider) }
    }

    fun setAiModel(provider: AiProvider, model: String) {
        updateAppSettings { it.copy(aiModels = it.aiModels + (provider to model)) }
        repository.ai.usage.rememberInfo(provider, model)
    }

    suspend fun loadAiUsage(): List<AiUsage> = withContext(Dispatchers.IO) { repository.ai.usage.all() }

    fun resetAiUsage() {
        viewModelScope.launch(Dispatchers.IO) { repository.ai.usage.clear() }
    }

    fun hasAiKey(provider: AiProvider): Boolean = repository.hasAiKey(provider)

    fun saveAiKey(provider: AiProvider, key: String) = repository.saveAiKey(provider, key)

    suspend fun loadAiModels(provider: AiProvider): Result<List<String>> = repository.aiModels(provider)

    fun toggleTunneling() {
        updateAppSettings { it.copy(tunnelingEnabled = !it.tunnelingEnabled) }
    }

    fun cycleLiveStreamFormat() {
        updateAppSettings { it.copy(liveStreamFormat = it.nextLiveStreamFormat()) }
    }

    fun cycleLiveChannelSortOrder() {
        updateAppSettings { it.copy(liveChannelSortOrder = it.nextLiveChannelSortOrder()) }
    }

    fun cycleVodSortOrder() {
        // Les pages sont indexées par tri (vodPageKey) : le navigateur lit simplement celles du nouveau.
        updateAppSettings { it.copy(vodSortOrder = it.nextVodSortOrder()) }
    }

    fun cycleEpgTimeOffset() {
        updateAppSettings { it.copy(epgTimeOffsetHours = it.nextEpgTimeOffsetHours()) }
        val profileId = _uiState.value.activeProfileId ?: return
        epg.clearProfileMemory(profileId)
        _uiState.update {
            it.copy(
                epgGuide = null,
                todayEpgGuide = null,
                epgAvailableDates = emptyList(),
                epgSelectedDate = null,
                epgLoading = false,
            )
        }
        epg.warmEpgGuideCache(profileId)
    }

    fun toggleAutoPlayNextEpisode() {
        updateAppSettings { it.copy(autoPlayNextEpisode = !it.autoPlayNextEpisode) }
    }

    fun toggleLiveVersionFailover() {
        updateAppSettings { it.copy(liveVersionFailover = !it.liveVersionFailover) }
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
        // Bloc réactivé : ses données n'ont pas été chargées pendant qu'il était masqué.
        if (block !in _uiState.value.appSettings.disabledHomeBlocks) {
            homeGuides.loadAll()
            if (block == HomeBlock.Recommendations || JustWatchSection.entries.any { it.homeBlock == block }) recommendations.refreshHomeRecommendations(force = true)
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
        MediaType.entries.forEach(catalogPaging::ensureSectionLoaded)
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
        catalogPaging.ensureSectionLoaded(MediaType.Live)
        epg.loadEpgGuideFromCache(_uiState.value.epgSelectedDate)
        epg.startEpgBackgroundSync()
    }

    fun closeOrganizer() {
        backFromMenu()
        val profileId = _uiState.value.activeProfileId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            catalogPaging.catalogLayoutMutation.withLock {
                val presentation = library.libraryPresentation(profileId)
                library.applyLibraryPresentation(profileId, presentation)
            }
        }
    }

    fun closePlayer(forceBrowser: Boolean = false) {
        liveZap.cancelPendingZap()
        _playerState.value = PlayerUiState()
        val profileId = _uiState.value.activeProfileId
        val player = _uiState.value.screen as? StreamiaScreen.Player
        _uiState.update {
            val sourceDestination = it.contentReturnContext
                ?.takeIf { context -> context.origin != ContentReturnOrigin.Browser }
                ?.destinationScreen()
            // Gauche/OK depuis une chaîne ouvre la liste catégories/chaînes du Direct. Le navigateur
            // rouvrait sinon le dernier onglet visité (Films/Séries) quand la chaîne avait été lancée
            // depuis l'accueil ; la catégorie est restaurée par BrowserScreen (LiveBrowserReturnState).
            val openLiveBrowser = forceBrowser && player?.entry?.type == MediaType.Live
            it.copy(
                browserType = if (openLiveBrowser) MediaType.Live else it.browserType,
                browserCategoryId = if (openLiveBrowser && it.browserType != MediaType.Live) null else it.browserCategoryId,
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
        epg.stopPlayerEpg()
        if (profileId != null) library.refreshLibrarySnapshot(profileId)
    }

    /**
     * Film lu jusqu'au bout : retour sur sa fiche, quel que soit l'écran d'où il a été lancé
     * (fiche, accueil « Reprendre », recherche…). Retour depuis la fiche ramène ensuite à cet écran.
     */
    fun finishMovie(movie: MediaEntry) {
        if ((_uiState.value.screen as? StreamiaScreen.Player)?.entry?.key != movie.key) return
        _playerState.value = PlayerUiState()
        openEntryInternal(movie)
    }

    fun closeDetails() {
        if (reopenPreviousDetails()) return
        _uiState.update {
            it.copy(
                screen = it.contentReturnContext?.destinationScreen() ?: StreamiaScreen.Browser,
                mediaDetails = null,
                similarMedia = emptyList(), aiPlot = null, aiSimilarKeys = null, aiPlotLoading = false, aiSimilarLoading = false, aiReview = null, aiReviewLoading = false, aiRecap = null, aiRecapLoading = false, aiRecapAvailable = false, aiRecapError = null,
                similarLoading = false,
                message = null,
            )
        }
    }
    /** Fiche ouverte depuis une autre fiche (contenu similaire) : Retour rouvre la précédente. */
    private fun reopenPreviousDetails(): Boolean {
        val previous = detailsTrail.removeLastOrNull() ?: return false
        _uiState.update { it.copy(mediaDetails = null, seriesDetails = null, similarMedia = emptyList(), aiPlot = null, aiSimilarKeys = null, aiPlotLoading = false, aiSimilarLoading = false, aiReview = null, aiReviewLoading = false, aiRecap = null, aiRecapLoading = false, aiRecapAvailable = false, aiRecapError = null, similarLoading = false, message = null) }
        openEntryInternal(previous)
        return true
    }

    fun closeSeries() {
        if (reopenPreviousDetails()) return
        _uiState.update {
            it.copy(
                screen = it.contentReturnContext?.destinationScreen() ?: StreamiaScreen.Browser,
                seriesDetails = null,
                similarMedia = emptyList(), aiPlot = null, aiSimilarKeys = null, aiPlotLoading = false, aiSimilarLoading = false, aiReview = null, aiReviewLoading = false, aiRecap = null, aiRecapLoading = false, aiRecapAvailable = false, aiRecapError = null,
                similarLoading = false,
                message = null,
            )
        }
    }

    fun zap(delta: Int) = liveZap.zap(delta)

    fun switchLiveVersion(version: MediaEntry) = liveZap.switchLiveVersion(version)

    /**
     * Carte « Continuer à regarder » de Google TV (voir [WatchNextPublisher.resumeUri]) : reprend le
     * contenu à sa position, par le même chemin rapide que la reprise au démarrage.
     */
    fun openResumeLink(uri: Uri?): Boolean {
        if (uri?.scheme != WatchNextPublisher.SCHEME || uri.host != WatchNextPublisher.HOST_RESUME) return false
        val profileId = uri.getQueryParameter("profile") ?: return false
        val key = uri.getQueryParameter("key") ?: return false
        resumeLinkPending = true
        // Liste et historique lus hors du thread principal (appelé depuis onCreate).
        viewModelScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) {
                    if (repository.profile(profileId) == null) return@withContext null
                    repository.appSettings()
                    repository.library(profileId).history.firstOrNull { it.entry.key == key }?.entry
                } ?: return@launch
                awaitStartupData()
                resumeStartup(profileId, entry, returnToSeries = entry.type == MediaType.Series)
            } finally {
                resumeLinkPending = false
            }
        }
        return true
    }

    fun previousChannel() = liveZap.previousChannel()

    fun dismissMessage() { _uiState.update { it.copy(message = null) } }

    fun logout() {
        val previousProfileId = _uiState.value.activeProfileId
        viewModelScope.launch {
            repository.logout()
            showLogin()
            _uiState.update { it.copy(returnProfileId = previousProfileId) }
        }
    }

    /** Retour depuis le gestionnaire de listes : rouvre la liste quittée par « Changer de liste ». */
    fun returnToPreviousList() {
        val profileId = _uiState.value.returnProfileId ?: return
        if (_uiState.value.profiles.none { it.id == profileId }) return
        openProfile(profileId)
    }

    private fun openPlayer(entry: MediaEntry, returnToSeries: Boolean, returnToDetails: Boolean = false, fromStart: Boolean = false) {
        // Toute chaîne ouverte en plein écran compte, qu'on y arrive par zap ou via la liste Direct.
        if (entry.type == MediaType.Live) {
            liveZap.onChannelOpened(entry)
        }
        val profileId = _uiState.value.activeProfileId
        val resume = if (profileId != null && entry.type != MediaType.Live && !fromStart) repository.resumePosition(profileId, entry.key) else 0L
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
            epg.startPlayerEpg(entry)
        } else {
            epg.stopPlayerEpg()
        }
    }

    private fun loadMovie(movie: MediaEntry) {
        val credentials = _uiState.value.credentials ?: return
        val profileId = _uiState.value.activeProfileId
        _uiState.update {
            it.copy(
                busy = true,
                mediaDetails = null,
                similarMedia = emptyList(), aiPlot = null, aiSimilarKeys = null, aiPlotLoading = false, aiSimilarLoading = false, aiReview = null, aiReviewLoading = false, aiRecap = null, aiRecapLoading = false, aiRecapAvailable = false, aiRecapError = null,
                similarLoading = profileId != null,
                screen = StreamiaScreen.MovieDetails(movie),
                message = null,
            )
        }
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
                recommendations.loadSimilarMedia(profileId, movie, resolvedDetails)
                endSimilarLoading(movie)
                enrichWithAi(movie, resolvedDetails?.plot ?: movie.plot, resolvedDetails)
            }
        }
    }

    private fun loadSeries(series: MediaEntry) {
        val credentials = _uiState.value.credentials ?: return
        val profileId = _uiState.value.activeProfileId
        _uiState.update {
            it.copy(
                busy = true,
                message = null,
                seriesDetails = null,
                similarMedia = emptyList(), aiPlot = null, aiSimilarKeys = null, aiPlotLoading = false, aiSimilarLoading = false, aiReview = null, aiReviewLoading = false, aiRecap = null, aiRecapLoading = false, aiRecapAvailable = false, aiRecapError = null,
                similarLoading = profileId != null,
                screen = StreamiaScreen.Series(series),
            )
        }
        viewModelScope.launch {
            runCatching { repository.seriesDetails(credentials, series) }
                .onSuccess { details ->
                    _uiState.update { it.copy(busy = false, seriesDetails = details) }
                    if (profileId != null) {
                        details.details?.let { info -> runCatching { repository.cacheRecommendationDetails(profileId, info) } }
                        recommendations.loadSimilarMedia(profileId, series, details.details)
                    }
                }
                .onFailure { error -> _uiState.update { it.copy(busy = false, message = error.safeMessage()) } }
            endSimilarLoading(series)
            val loaded = _uiState.value.seriesDetails
            aiFeatures.prepareRecap(series, loaded)
            enrichWithAi(series, loaded?.details?.plot ?: series.plot, loaded?.details)
        }
    }

    /**
     * Fonctions IA d'une fiche Film/Série en **une seule requête** : description traduite, similaires reclassés et avis
     * rapide. Sans effet si l'assistant est désactivé ; un résultat qui arrive après la fermeture de la fiche (ou la
     * désactivation) est jeté.
     */
    private suspend fun enrichWithAi(entry: MediaEntry, plot: String?, details: MediaDetails? = null) {
        if (!repository.ai.isActive()) return
        fun onSameEntry(state: StreamiaUiState) = when (val screen = state.screen) {
            is StreamiaScreen.MovieDetails -> screen.movie.key == entry.key
            is StreamiaScreen.Series -> screen.series.key == entry.key
            else -> false
        }
        val similar = _uiState.value.similarMedia.map { it.entry }
        val info = FicheInfo(
            genre = details?.genre,
            year = details?.releaseDate?.take(4),
            director = details?.director,
            cast = details?.cast,
            country = details?.country,
            rating = details?.rating ?: entry.rating,
            plot = plot,
        ).takeIf(FicheInfo::hasMaterial)
        if (plot.isNullOrBlank() && similar.isEmpty() && info == null) return
        _uiState.update {
            if (onSameEntry(it)) {
                it.copy(aiPlotLoading = !plot.isNullOrBlank(), aiSimilarLoading = similar.isNotEmpty(), aiReviewLoading = info != null)
            } else {
                it
            }
        }
        try {
            // Une seule requête pour la traduction, les similaires et l'avis quand plusieurs manquent au cache.
            val result = repository.ai.enrichFiche(entry, plot, similar, info)
            _uiState.update {
                if (onSameEntry(it)) {
                    it.copy(aiPlot = result.plot ?: it.aiPlot, aiSimilarKeys = result.similarKeys ?: it.aiSimilarKeys, aiReview = result.review ?: it.aiReview)
                } else {
                    it
                }
            }
        } finally {
            _uiState.update { if (onSameEntry(it)) it.copy(aiPlotLoading = false, aiSimilarLoading = false, aiReviewLoading = false) else it }
        }
    }

    /**
     * Calcul des similaires terminé (ou abandonné) sans rien publier : les cartes fantômes
     * disparaissent. Sans effet si une autre fiche a été ouverte entre-temps (son propre calcul court).
     */
    private fun endSimilarLoading(entry: MediaEntry) {
        _uiState.update { state ->
            val onSameScreen = when (val screen = state.screen) {
                is StreamiaScreen.MovieDetails -> screen.movie.key == entry.key
                is StreamiaScreen.Series -> screen.series.key == entry.key
                else -> false
            }
            if (onSameScreen && state.similarLoading) state.copy(similarLoading = false) else state
        }
    }

    private suspend fun refreshSilently(profileId: String) {
        // Liste quittée pendant l'attente : rien à télécharger pour elle.
        if (_uiState.value.activeProfileId != profileId) return
        // Une seule actualisation à la fois (retour du réseau, nouvel essai, actualisation
        // différée de l'ouverture) : un second téléchargement du catalogue n'apporterait rien.
        if (!catalogRefreshLock.tryLock()) return
        try {
            mergeCatalog(repository.refreshProfile(profileId))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            if (_uiState.value.activeProfileId == profileId) {
                _uiState.update { state -> state.copy(offline = true, busy = false) }
            }
        } finally {
            catalogRefreshLock.unlock()
        }
    }

    /**
     * Liste affichée depuis le cache après un échec (« Mode cache ») : nouvel essai
     * d'actualisation. Sans effet si la liste est à jour, si une actualisation est déjà en cours
     * ou si l'ouverture de la liste doit encore la lancer elle-même.
     */
    fun retryOfflineCatalog() {
        val state = _uiState.value
        val profileId = state.activeProfileId ?: return
        if (!state.offline || state.busy || profileLoadJob?.isActive == true || catalogRetryJob?.isActive == true) return
        catalogRetryJob = viewModelScope.launch { refreshSilently(profileId) }
    }

    /**
     * Réseau revenu après une coupure (appelé seulement app visible) : chaque bloc resté sur un
     * échec ou sur des données de repli est rechargé tout de suite, au lieu d'attendre son
     * prochain créneau (jusqu'à 15 min) ou une relance de l'app. Les blocs encore frais ne
     * contactent aucun site (caches).
     */
    fun onNetworkRestored() {
        if (_uiState.value.activeProfileId == null) return
        retryOfflineCatalog()
        weather.retryNow()
        liveOnSat.retryNow()
        repository.guides.clearGuideFailureBackoffs()
        homeGuides.loadAll()
        recommendations.reloadJustWatchRows()
        epg.startEpgBackgroundSync()
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
                            // Catalogue relu (actualisation) : les pages Films/Séries sont relues au tri courant.
                            vodPages = emptyMap(),
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
                if (committed) {
                    val reloadedFromSource = loaded.source == CatalogSource.Network || loaded.source == CatalogSource.Import
                    if (reloadedFromSource) catalogPaging.invalidateLiveSection()
                    catalogPaging.ensureSectionLoaded(MediaType.Live)
                    recommendations.refreshHomeRecommendations()
                    if (reloadedFromSource) liveOnSat.reresolveLiveOnSat()
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

    /**
     * Nouvel état complet (ouverture d'une liste, déconnexion) : garde ce qui ne dépend d'aucune
     * liste — la météo de l'en-tête, sinon absente jusqu'à la prochaine requête (30 min).
     */
    private fun resetUiState(state: StreamiaUiState) {
        catalogPaging.invalidateLiveSection()
        // Nouvel état = rangées des guides vides : leur prochain rapprochement doit être republié.
        homeGuides.resetAll()
        _uiState.value = state
        // Nouvelle liste : rangées de l'accueil vides (squelettes), la météo de l'en-tête est gardée.
        _homeState.update { HomeUiState(weatherPlace = it.weatherPlace, weather = it.weather) }
    }

    private fun showLogin() {
        profileLoadJob?.cancel()
        profileLoadJob = null
        catalogRetryJob?.cancel()
        catalogRetryJob = null
        liveOnSat.reset()
        liveZap.reset()
        detailsTrail.clear()
        secondaryLoadsJob?.cancel()
        _playerState.value = PlayerUiState()
        epg.reset()
        recommendations.reset()
        aiFeatures.reset()
        homeGuides.resetAll()
        resetUiState(StreamiaUiState(
            booting = false,
            screen = StreamiaScreen.Login,
            profiles = repository.profiles(),
            appSettings = repository.appSettings(),
        ))
    }

    private suspend fun showCatalog(loaded: LoadedCatalog) {
        val presentation = repository.prepareCatalogPresentation(loaded.profileId, loaded.catalog)
        resetUiState(StreamiaUiState(
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
        ))
        epg.warmEpgGuideCache(loaded.profileId)
        catalogPaging.ensureSectionLoaded(MediaType.Live)
        recommendations.refreshHomeRecommendations()
        scheduleSecondaryLoads()
    }

    /**
     * Guides tiers (scraping + parsing Jsoup) lancés après le premier affichage et l'un après
     * l'autre plutôt que tous en même temps que le catalogue et la première image vidéo : sur un
     * boîtier à 4 petits cœurs, ils se disputaient le CPU au moment où l'utilisateur navigue.
     * Sur l'accueil, un guide dont le cache disque est encore frais n'a rien à scraper : il est
     * chargé tout de suite (lecture de fichier), sans squelette pendant le délai.
     */
    private fun scheduleSecondaryLoads(fromPlayer: Boolean = false) {
        secondaryLoadsJob?.cancel()
        secondaryLoadsJob = viewModelScope.launch {
            val loads = listOf<Pair<suspend () -> Boolean, () -> Unit>>(
                repository.guides::hasFreshTvProgrammeNowCache to { homeGuides.tvProgrammeNowGuide.load(forceRefresh = false) },
                repository.guides::hasFreshBeinSportsGuideCache to { homeGuides.beinSportsGuide.load(forceRefresh = false) },
                repository.guides::hasFreshUkGuideCache to { homeGuides.ukGuide.load(forceRefresh = false) },
                repository.guides::hasFreshTvProgrammeTonightCache to { homeGuides.tvProgrammeTonightGuide.load(forceRefresh = false) },
                repository.guides::hasFreshLiveOnSatCache to { liveOnSat.loadLiveOnSatMatches(forceRefresh = false) },
            )
            // Reprise directe dans le lecteur : la vidéo passe d'abord, même les lectures de cache attendent.
            val (cached, scraped) = loads.partition { (hasFreshCache, _) ->
                !fromPlayer && runCatching { hasFreshCache() }.getOrDefault(false)
            }
            cached.forEach { (_, load) -> load() }
            // Blocs à retélécharger : leur dernière version sur disque s'affiche déjà, le
            // téléchargement (étalé ci-dessous) la remplacera.
            if (!fromPlayer) {
                homeGuides.showCachedAll()
                liveOnSat.showCachedLiveOnSatMatches()
            }
            delay(if (fromPlayer) STARTUP_SECONDARY_LOADS_PLAYER_DELAY_MS else STARTUP_SECONDARY_LOADS_HOME_DELAY_MS)
            scraped.forEach { (_, load) ->
                load()
                delay(SECONDARY_LOADS_GAP_MS)
            }
        }
    }

    private fun showError(error: Throwable) {
        _uiState.update { it.copy(busy = false, message = error.safeMessage(), testSucceeded = false, profiles = repository.profiles()) }
    }

    private fun AccountInfo.toConnectionSuccessMessage(): String = buildString {
        append("Connexion réussie · compte $status")
        expiresAtEpochSeconds?.let { append(", expire le ${formatExpiry(it)}") }
    }
}

/** Fiches empilées au plus pour Retour (contenus similaires enchaînés). */
private const val MAX_DETAILS_TRAIL = 30

private const val STARTUP_SECONDARY_LOADS_HOME_DELAY_MS = 1_200L

private const val STARTUP_SECONDARY_LOADS_PLAYER_DELAY_MS = 8_000L

private const val SECONDARY_LOADS_GAP_MS = 600L

private const val CATALOG_BACKGROUND_REFRESH_DELAY_MS = 20_000L

class StreamiaViewModelFactory(private val repository: XtreamRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = StreamiaViewModel(repository) as T
}

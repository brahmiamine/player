package fr.streamia.tv.ui

import android.content.ComponentCallbacks2
import androidx.lifecycle.viewModelScope
import fr.streamia.tv.data.EpgCacheMetadata
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgGuide
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.EpgNowContext
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.epgNowContextAt
import fr.streamia.tv.domain.withTimeOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Guide TV : synchronisation XMLTV, journées lues depuis SQLite (avec un petit cache mémoire), programme en cours du lecteur Direct et navigation jour par jour de l'écran Guide TV.
 */
internal class EpgController(
    host: StreamiaStateHolder,
    /** Nouveau guide écrit en base : les rapprochements qui en dépendent (matchs) sont refaits. */
    private val onGuideReplaced: () -> Unit,
) : StreamiaController(host) {
    private var epgGuideLoadSequence = 0L

    private val epgGuideMemoryCache = EpgGuideMemoryCache()

    private var epgSyncJob: Job? = null

    private var epgSyncProfileId: String? = null

    private var epgPrefetchJob: Job? = null

    private var epgChannelJob: Job? = null

    private var epgTickerJob: Job? = null

    /**
     * Pression mémoire signalée par Android : les journées EPG gardées en RAM (des dizaines de Mo
     * sur un gros guide) sont relâchées, SQLite les redonnera au besoin. Le guide du jour affiché
     * reste dans l'état. Passage en arrière-plan simple (UI_HIDDEN) : rien à faire.
     */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            epgGuideMemoryCache.clear()
        }
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

    /** Description d'un programme du Guide TV, lue à la demande (voir EpgDatabase.loadDescription). */
    suspend fun epgDescription(program: EpgProgram): String? {
        program.description?.let { return it }
        val state = _uiState.value
        val profileId = state.activeProfileId ?: return null
        return runCatching { repository.epgDescription(profileId, program, state.appSettings.epgTimeOffsetHours) }.getOrNull()
    }

    fun reloadEpg() {
        // Une actualisation manuelle peut être longue : l'ancien guide reste visible jusqu'au
        // commit transactionnel du nouveau XMLTV.
        _uiState.update { it.copy(epgLoading = true, message = null) }
        startEpgBackgroundSync(force = true)
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

    suspend fun todayEpgGuide(profileId: String, offsetHours: Int): EpgGuide? =
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

    /** Chaîne Direct ouverte dans le lecteur : programme courant puis rafraîchissement périodique. */
    fun startPlayerEpg(entry: MediaEntry) {
        loadEpg(entry)
        startEpgTicker(entry)
    }

    /** Lecteur fermé ou contenu non Direct : plus rien à suivre. */
    fun stopPlayerEpg() {
        epgChannelJob?.cancel()
        epgTickerJob?.cancel()
    }

    /** Source XMLTV ou décalage horaire changé : les journées en RAM de ce profil sont périmées. */
    fun clearProfileMemory(profileId: String) = epgGuideMemoryCache.clearProfile(profileId)

    /** Déconnexion : caches RAM vidés et synchronisations arrêtées. */
    fun reset() {
        epgGuideMemoryCache.clear()
        epgPrefetchJob?.cancel()
        epgPrefetchJob = null
        epgSyncJob?.cancel()
        epgSyncJob = null
        epgSyncProfileId = null
        stopPlayerEpg()
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

    fun startEpgBackgroundSync(force: Boolean = false) {
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
                // Guide à retélécharger pendant une lecture : attend qu'elle se termine (voir
                // PlaybackActivity.awaitIdle). Une demande explicite (force) part tout de suite.
                if (!force && !repository.isEpgFresh(profileId)) fr.streamia.tv.player.PlaybackActivity.awaitIdle()
                if (_uiState.value.activeProfileId != profileId) return@launch
                val changed = repository.refreshEpg(
                    profileId = profileId,
                    credentials = credentials,
                    liveEntries = liveEntries,
                    force = force,
                )
                if (changed) {
                    epgGuideMemoryCache.clearProfile(profileId)
                    onGuideReplaced()
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

    fun loadEpgGuideFromCache(
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
    fun warmEpgGuideCache(profileId: String) {
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
}

private const val EPG_PLAYER_REFRESH_MS = 30_000L

private const val MAX_EPG_DAY_SPAN = 30L

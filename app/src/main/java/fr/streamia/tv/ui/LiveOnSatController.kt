package fr.streamia.tv.ui

import fr.streamia.tv.data.BackgroundWork
import androidx.lifecycle.viewModelScope
import fr.streamia.tv.data.HomeBlock
import fr.streamia.tv.data.HomeGuidesRepository
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgGuide
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.liveonsat.ChannelIndex
import fr.streamia.tv.liveonsat.ChannelMatcher
import fr.streamia.tv.data.LiveOnSatFetchResult
import fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch
import fr.streamia.tv.liveonsat.withEpgTiming
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Matchs du jour (liveonsat.com) : scrape en cache, rapprochement progressif des diffuseurs avec les chaînes Direct et horaires précisés par l'EPG.
 */
internal class LiveOnSatController(
    host: StreamiaStateHolder,
    private val todayEpgGuide: suspend (profileId: String, offsetHours: Int) -> EpgGuide?,
    private val liveSection: suspend (profileId: String) -> List<MediaEntry>,
) : StreamiaController(host) {
    private val liveOnSatChannelMatcher = ChannelMatcher()

    private var liveOnSatIndexCache: Triple<List<MediaEntry>, List<MediaCategory>, ChannelIndex>? = null

    private var liveOnSatLoadSequence = 0L

    private var liveOnSatLoadJob: Job? = null

    /** Échéance comptée depuis l'âge réel du cache ; après un échec, nouvel essai plus tôt. */
    private var liveOnSatNextCheckAtMillis = 0L

    /** Appelé en boucle par l'accueil et la page Matchs : recharge quand le cache atteint 2 h. */
    fun refreshLiveOnSatIfStale() {
        if (System.currentTimeMillis() < liveOnSatNextCheckAtMillis) return
        loadLiveOnSatMatches(forceRefresh = false)
    }

    fun refreshLiveOnSatMatches() = loadLiveOnSatMatches(forceRefresh = true)

    /** Réseau revenu : l'échéance après échec est oubliée. */
    fun retryNow() {
        liveOnSatNextCheckAtMillis = 0L
        refreshLiveOnSatIfStale()
    }

    /** Déconnexion : l'index des chaînes appartient au profil quitté. */
    fun reset() {
        liveOnSatIndexCache = null
    }

    /** Version de rapprochement (scrape + playlist + EPG) et rapprochement déjà enregistré pour elle. */
    private suspend fun liveOnSatSavedResolution(
        profileId: String?,
        fetch: LiveOnSatFetchResult,
    ): Pair<String?, List<ResolvedLiveOnSatMatch>?> {
        val version = profileId?.let { id -> runCatching { repository.guides.liveOnSatResolutionVersion(id, fetch) }.getOrNull() }
        val saved = if (profileId == null || version == null) null else {
            runCatching { repository.guides.cachedLiveOnSatResolution(profileId, version, fetch) }.getOrNull()
        }?.let { resolved ->
            val catalog = _uiState.value.catalog ?: return@let resolved
            resolved.map { match ->
                match.copy(matchedChannels = match.matchedChannels.mapValues { (_, channels) -> channels.map { catalog.entry(it.key) ?: it } })
            }
        }
        return version to saved
    }

    /**
     * Matchs déjà sur disque (et leurs chaînes, si ce scrape a déjà été rapproché pour cette liste)
     * affichés tout de suite, même si le cache a dépassé 2 h : la page et le bloc de l'accueil ne
     * restent plus vides pendant le scrape de liveonsat.com, qui les remplace ensuite.
     */
    fun showCachedLiveOnSatMatches() {
        if (_homeState.value.liveOnSatMatches.isNotEmpty()) return
        if (HomeBlock.LiveMatches in _uiState.value.appSettings.disabledHomeBlocks) return
        val sequence = liveOnSatLoadSequence
        viewModelScope.launch {
            val fetch = runCatching { repository.guides.cachedLiveOnSatMatches() }.getOrNull() ?: return@launch
            val profileId = _uiState.value.activeProfileId ?: return@launch
            val (_, saved) = liveOnSatSavedResolution(profileId, fetch)
            if (_uiState.value.activeProfileId != profileId) return@launch
            _homeState.update { state ->
                // Un chargement plus récent a déjà publié : il a priorité.
                if (state.liveOnSatMatches.isNotEmpty() || sequence != liveOnSatLoadSequence) state
                else state.copy(
                    liveOnSatMatches = saved ?: fetch.matches.map { match -> ResolvedLiveOnSatMatch(match, emptyMap()) },
                    liveOnSatFetchedAtEpochMillis = fetch.fetchedAtEpochMillis,
                    liveOnSatPending = false,
                )
            }
        }
    }

    /**
     * Charge (ou réutilise le cache si assez récent) les matchs du jour scrapés depuis
     * liveonsat.com, puis résout leurs diffuseurs contre les chaînes Direct de ce profil. Le scrape
     * lui-même ne dépend d'aucun profil ; seule cette résolution en dépend.
     */
    fun loadLiveOnSatMatches(forceRefresh: Boolean) {
        // Appelé à chaque ouverture de l'app (openProfile/resumeStartup/showCatalog) : un chargement
        // déjà en vol pour la même raison ne doit pas en déclencher un second en parallèle. Un
        // forceRefresh explicite (bouton Actualiser) passe toujours devant : il annule le chargement
        // en cours plutôt que d'en laisser deux tourner (double scrape de liveonsat.com).
        if (!forceRefresh && liveOnSatLoadJob?.isActive == true) return
        if (forceRefresh) liveOnSatLoadJob?.cancel()
        val sequence = ++liveOnSatLoadSequence
        liveOnSatNextCheckAtMillis = System.currentTimeMillis() + LIVE_ONSAT_RETRY_MS
        _homeState.update { it.copy(liveOnSatLoading = true, liveOnSatError = null) }
        liveOnSatLoadJob = viewModelScope.launch {
            // Scrape + rapprochement des chaînes terminés (ou en échec) : plus de squelette.
            coroutineContext.job.invokeOnCompletion {
                if (sequence == liveOnSatLoadSequence) _homeState.update { it.copy(liveOnSatPending = false, liveOnSatResolving = false) }
            }
            val result = runCatching { repository.guides.loadLiveOnSatMatches(forceRefresh) }
            if (sequence != liveOnSatLoadSequence) return@launch

            result.onSuccess { fetch ->
                // Cache encore valable : prochain rechargement quand il atteint 2 h. Cache expiré
                // renvoyé quand même (scrape en échec) : nouvel essai dans LIVE_ONSAT_RETRY_MS.
                val expiresAt = fetch.fetchedAtEpochMillis + HomeGuidesRepository.LIVE_ONSAT_CACHE_MAX_AGE_MS
                if (expiresAt > System.currentTimeMillis()) liveOnSatNextCheckAtMillis = expiresAt
                // Chaînes déjà rapprochées pour ce même scrape, cette même playlist et ce même EPG :
                // réutilisées telles quelles, sinon rapprochement refait puis réenregistré.
                val profileId = _uiState.value.activeProfileId
                val (version, cachedResolution) = liveOnSatSavedResolution(profileId, fetch)
                if (sequence != liveOnSatLoadSequence) return@launch
                // Phase 1 : afficher immédiatement tous les matchs du jour, sans attendre la
                // résolution des chaînes ni l'enrichissement EPG (les deux étapes coûteuses).
                _homeState.update {
                    it.copy(
                        liveOnSatLoading = false,
                        liveOnSatMatches = cachedResolution
                            ?: it.liveOnSatMatchesFor(fetch)
                            // Nouveau scrape : les matchs déjà affichés gardent leurs chaînes en
                            // attendant le rapprochement, au lieu de les perdre quelques instants.
                            ?: it.liveOnSatMatches.associateBy(ResolvedLiveOnSatMatch::match).let { previous ->
                                fetch.matches.map { match -> previous[match] ?: ResolvedLiveOnSatMatch(match, emptyMap()) }
                            },
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
                _homeState.update { it.copy(liveOnSatLoading = false, liveOnSatError = error.safeMessage()) }
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
        _homeState.update { if (sequence == liveOnSatLoadSequence) it.copy(liveOnSatResolving = true) else it }
        val catalog = state.catalog

        // Le matcher a besoin des catégories (bouquet AR pour "beIN Connect MENA"...), pas
        // seulement des chaînes : on réutilise le catalogue chargé, ou on en reconstitue un minimal
        // à partir des catégories déjà connues quand la section Direct n'est pas encore matérialisée.
        val matcherCatalog = if (catalog?.isCategoryLoaded(MediaType.Live, Catalog.ALL_CATEGORY_ID) == true) {
            catalog
        } else {
            val liveChannels = withContext(Dispatchers.IO) {
                runCatching { liveSection(profileId) }.getOrDefault(emptyList())
            }
            Catalog(categories = catalog?.categories.orEmpty(), entries = liveChannels)
        }
        val liveChannels = matcherCatalog.entriesFor(MediaType.Live)
        if (liveChannels.isEmpty()) return

        // Guide du jour (relu depuis SQLite s'il n'est pas en mémoire) chargé pendant la
        // construction de l'index, au lieu de la retarder.
        val (todayGuide, index) = coroutineScope {
            val guide = async { todayEpgGuide(profileId, state.appSettings.epgTimeOffsetHours) }
            // Index gardé tant que les chaînes Direct et les catégories sont les mêmes : il découpe
            // chaque nom de chaîne (des dizaines de milliers) et mémorise les diffuseurs déjà
            // résolus, réutilisés au scrape suivant.
            val cached = liveOnSatIndexCache?.takeIf { (channels, categories, _) ->
                channels === liveChannels && categories === matcherCatalog.categories
            }?.third
            val index = cached ?: withContext(BackgroundWork.light) { liveOnSatChannelMatcher.buildIndex(matcherCatalog) }
                .also { liveOnSatIndexCache = Triple(liveChannels, matcherCatalog.categories, it) }
            guide.await() to index
        }
        // Recalcul des mêmes matchs (playlist ou EPG renouvelés) : l'ancien résultat reste affiché
        // pour les paquets pas encore refaits, au lieu de faire disparaître les chaînes.
        val resolved = (_homeState.value.liveOnSatMatchesFor(fetch) ?: matches.map { ResolvedLiveOnSatMatch(it, emptyMap()) }).toMutableList()

        var offset = 0
        while (offset < matches.size) {
            if (sequence != liveOnSatLoadSequence) return
            val end = minOf(offset + LIVE_ONSAT_RESOLVE_BATCH, matches.size)
            val chunk = withContext(BackgroundWork.light) {
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
            _homeState.update { current ->
                if (sequence != liveOnSatLoadSequence) current
                else current.copy(liveOnSatMatches = resolved.toList())
            }
            offset = end
        }
        // Gardé jusqu'au prochain scrape : les prochaines ouvertures sautent tout ce calcul.
        repository.guides.saveLiveOnSatResolution(profileId, version, resolved)
    }

    /** Matchs affichés s'ils proviennent déjà de ce même scrape. */
    private fun HomeUiState.liveOnSatMatchesFor(fetch: LiveOnSatFetchResult): List<ResolvedLiveOnSatMatch>? =
        liveOnSatMatches.takeIf { liveOnSatFetchedAtEpochMillis == fetch.fetchedAtEpochMillis && it.size == fetch.matches.size }

    /**
     * Playlist actualisée ou EPG resynchronisé : relit les matchs (cache, sans scrape s'il a moins
     * de 2 h) pour refaire le rapprochement des chaînes, dont la version enregistrée ne correspond plus.
     */
    fun reresolveLiveOnSat() {
        if (_homeState.value.liveOnSatMatches.isNotEmpty()) loadLiveOnSatMatches(forceRefresh = false)
    }
}

private const val LIVE_ONSAT_RESOLVE_BATCH = 50

private const val LIVE_ONSAT_RETRY_MS = 15 * 60_000L

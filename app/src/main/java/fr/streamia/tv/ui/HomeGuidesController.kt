package fr.streamia.tv.ui

import fr.streamia.tv.data.BackgroundWork
import androidx.lifecycle.viewModelScope
import fr.streamia.tv.beinsports.BeinSportsChannelMatcher
import fr.streamia.tv.data.HomeBlock
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.tvprogramme.TvProgrammeChannelMatcher
import fr.streamia.tv.ukguide.UkGuideChannelMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Guides tiers de l'accueil (programme TV FR en direct et du soir, beIN SPORTS, UK) : chargement, rapprochement avec les chaînes Direct de la liste active et publication dans [HomeUiState].
 */
internal class HomeGuidesController(host: StreamiaStateHolder) : StreamiaController(host) {
    private val tvProgrammeChannelMatcher = TvProgrammeChannelMatcher()

    private val beinSportsChannelMatcher = BeinSportsChannelMatcher()

    private val ukGuideChannelMatcher = UkGuideChannelMatcher()

    // Guides tiers de l'accueil : même cycle chargement → rapprochement → publication, voir [HomeGuide].
    val tvProgrammeNowGuide = HomeGuide(
        blocks = setOf(HomeBlock.TvProgrammeNow),
        fetch = { repository.loadTvProgrammeNow(it) },
        cached = { repository.cachedTvProgrammeNow() },
        isEmpty = { it.programmes.isEmpty() },
    ) { fetch, catalog, visible ->
        val resolved = tvProgrammeChannelMatcher.resolveNow(fetch.programmes, catalog).filter { visible(it.channel) }
        ({ state -> state.copy(homeTvProgrammeNow = resolved) })
    }

    val tvProgrammeTonightGuide = HomeGuide(
        blocks = setOf(HomeBlock.TvProgrammeTonight),
        fetch = { repository.loadTvProgrammeTonight(it) },
        cached = { repository.cachedTvProgrammeTonight() },
        isEmpty = { it.programmes.isEmpty() },
    ) { fetch, catalog, visible ->
        val resolved = tvProgrammeChannelMatcher.resolve(fetch.programmes, catalog).filter { visible(it.channel) }
        ({ state -> state.copy(homeTvProgrammeTonight = resolved) })
    }

    /**
     * Charge la grille MENA beIN SPORTS et en extrait les émissions actuellement diffusées et
     * suivantes. Le scrape ne dépend pas du profil ; seule la résolution vers la playlist dépend
     * du catalogue Live courant.
     */
    val beinSportsGuide = HomeGuide(
        blocks = setOf(HomeBlock.BeinSportsNow, HomeBlock.BeinSportsNext),
        fetch = { repository.loadBeinSportsGuide(it) },
        cached = { repository.cachedBeinSportsGuide() },
        isEmpty = { it.rows.current.isEmpty() && it.rows.next.isEmpty() },
    ) { fetch, catalog, visible ->
        val current = beinSportsChannelMatcher.resolve(fetch.rows.current, catalog).filter { visible(it.channel) }
        val next = beinSportsChannelMatcher.resolve(fetch.rows.next, catalog).filter { visible(it.channel) }
        ({ state -> state.copy(homeBeinSportsNow = current, homeBeinSportsNext = next) })
    }

    val ukGuide = HomeGuide(
        blocks = setOf(HomeBlock.UkGuideNow, HomeBlock.UkGuideNext),
        fetch = { repository.loadUkGuide(it) },
        cached = { repository.cachedUkGuide() },
        isEmpty = { it.rows.current.isEmpty() && it.rows.next.isEmpty() },
    ) { fetch, catalog, visible ->
        val current = ukGuideChannelMatcher.resolve(fetch.rows.current, catalog).filter { visible(it.channel) }
        val next = ukGuideChannelMatcher.resolve(fetch.rows.next, catalog).filter { visible(it.channel) }
        ({ state -> state.copy(homeUkGuideNow = current, homeUkGuideNext = next) })
    }

    private val homeGuides = listOf(tvProgrammeNowGuide, tvProgrammeTonightGuide, beinSportsGuide, ukGuide)

    /** Catalogue Direct (re)chargé : chaque guide refait son rapprochement. */
    fun resolveAll() = homeGuides.forEach { it.resolve() }

    fun loadAll() = homeGuides.forEach { it.load(forceRefresh = false) }

    fun showCachedAll() = homeGuides.forEach { it.showCached() }

    fun resetAll() = homeGuides.forEach { it.reset() }

    /**
     * Un guide tiers de l'accueil (FR en direct / ce soir, beIN, UK). Le scrape ne dépend d'aucun
     * profil ; seul le rapprochement avec les chaînes Direct en dépend, fait hors thread UI et
     * publié seulement s'il est toujours le plus récent pour le profil actif (séquences).
     */
    inner class HomeGuide<Raw : Any>(
        /** Blocs de l'accueil alimentés par ce guide : squelette tant que le premier chargement n'est pas fini. */
        private val blocks: Set<HomeBlock>,
        private val fetch: suspend (forceRefresh: Boolean) -> Raw,
        /** Données déjà sur disque, même anciennes, affichées en attendant [fetch]. */
        private val cached: suspend () -> Raw?,
        private val isEmpty: (Raw) -> Boolean,
        private val match: (Raw, Catalog, visible: (MediaEntry) -> Boolean) -> (HomeUiState) -> HomeUiState,
    ) {
        private var loadJob: Job? = null
        private var loadSequence = 0L
        private var resolveSequence = 0L
        private var resolveJob: Job? = null
        /** Entrées du dernier rapprochement publié : identiques, rien à recalculer ni à republier. */
        @Volatile private var resolvedKey: List<Any?>? = null
        private var raw: Raw? = null

        fun load(forceRefresh: Boolean) {
            if (_uiState.value.activeProfileId == null) return
            // Bloc désactivé dans Paramètres : aucun scrape (rechargé à sa réactivation, voir toggleHomeBlock).
            if (blocks.all { it in _uiState.value.appSettings.disabledHomeBlocks }) return
            if (!forceRefresh && loadJob?.isActive == true) return
            if (forceRefresh) loadJob?.cancel()
            val sequence = ++loadSequence
            loadJob = viewModelScope.launch {
                runCatching { fetch(forceRefresh) }.onSuccess { fetched ->
                    // Scrape commun à toutes les listes : gardé même si la liste a changé entre-temps,
                    // resolve() le rapproche des chaînes de la liste active.
                    if (sequence != loadSequence) return@onSuccess
                    raw = fetched
                    resolve()
                }.onFailure { error ->
                    if (error !is CancellationException && sequence == loadSequence) settleHomeBlocks(blocks)
                }
            }
        }

        /**
         * Affiche tout de suite la grille déjà sur disque (même plus ancienne que la durée de
         * fraîcheur) tant que rien n'est encore chargé : le bloc apparaît dès l'ouverture au lieu
         * d'un squelette pendant le téléchargement et l'analyse du site, puis [load] le met à jour.
         */
        fun showCached() {
            if (raw != null || _uiState.value.activeProfileId == null) return
            if (blocks.all { it in _uiState.value.appSettings.disabledHomeBlocks }) return
            viewModelScope.launch {
                val stale = runCatching { cached() }.getOrNull()?.takeUnless(isEmpty) ?: return@launch
                // Chargement réseau arrivé entre-temps : il a priorité.
                if (raw != null) return@launch
                raw = stale
                resolve()
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
            // Retour à l'accueil, cache du guide relu, page Films chargée… : tant que le guide, les
            // chaînes Direct et les masquages sont les mêmes, le résultat publié est déjà le bon.
            // Refaire le rapprochement (toutes les chaînes FR) à chaque retour, sans annuler le
            // précédent, empilait des calculs lourds et ralentissait l'app à chaque aller-retour.
            val key = listOf(profileId, fetched, catalog.entriesFor(MediaType.Live), hiddenEntries, excludedCategoryIds)
            if (key == resolvedKey) {
                settleHomeBlocks(blocks)
                return
            }
            val sequence = ++resolveSequence
            resolveJob?.cancel()
            resolveJob = viewModelScope.launch(BackgroundWork.light) {
                val publish = match(fetched, catalog) { channel ->
                    channel.key !in hiddenEntries && channel.categoryId !in excludedCategoryIds
                }
                if (sequence != resolveSequence) return@launch
                if (_uiState.value.activeProfileId == profileId) {
                    _homeState.update { latest -> publish(latest).copy(homePendingBlocks = latest.homePendingBlocks - blocks) }
                }
                resolvedKey = key
            }
        }

        /**
         * Changement de liste : seul le rapprochement en cours (chaînes de l'ancienne liste) est
         * abandonné. Le scrape, commun à toutes les listes, est gardé : la nouvelle liste l'affiche
         * dès son catalogue chargé, sans relire le cache ni rescraper.
         */
        fun reset() {
            resolveSequence += 1
            resolveJob?.cancel()
            resolvedKey = null
        }
    }
}

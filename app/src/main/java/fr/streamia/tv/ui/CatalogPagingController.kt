package fr.streamia.tv.ui

import androidx.lifecycle.viewModelScope
import fr.streamia.tv.data.VodSortOrder
import fr.streamia.tv.data.XtreamRepository
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Chargement du catalogue par morceaux : pages Films/Séries par catégorie et tri, section Direct entière, et fusions dans le catalogue affiché.
 */
internal class CatalogPagingController(
    host: StreamiaStateHolder,
    /** Section Direct entière en mémoire : EPG et guides de l'accueil peuvent être rapprochés. */
    private val onLiveSectionReady: () -> Unit,
) : StreamiaController(host) {
    val catalogLayoutMutation = Mutex()

    // Lu/écrit uniquement depuis le thread principal (appelants Compose) : évite de relancer une
    // requête SQLite déjà en vol pour la même page quand plusieurs recompositions déclenchent le
    // même chargement (ex. sélection rapide de catégories, LaunchedEffect qui se relance).
    private val categoryLoadsInFlight = mutableSetOf<String>()

    /**
     * Charge la première page d'une catégorie depuis SQLite si elle n'est pas déjà matérialisée.
     * Le catalogue affiché reste léger (catégories + comptes + quelques entrées de contexte) tant
     * que l'utilisateur n'a pas réellement ouvert une catégorie : c'est cet appel — déclenché par
     * [fr.streamia.tv.ui.BrowserScreen] à la sélection — qui va chercher ses chaînes/films/séries.
     * `Catalog.ALL_CATEGORY_ID` (« Tout ») est une catégorie comme une autre pour
     * [XtreamRepository.loadCategoryPage] : elle charge simplement les premières entrées du type
     * sans filtrer par category_id.
     */
    fun ensureCategoryLoaded(type: MediaType, categoryId: String, order: VodSortOrder = defaultVodOrder(type)) {
        val profileId = _uiState.value.activeProfileId ?: return
        val catalog = _uiState.value.catalog ?: return
        if (!needsFirstPage(catalog, type, categoryId, order)) return
        loadCategoryPage(profileId, type, categoryId, offset = 0, order = order)
        prefetchNeighborCategories(profileId, type, categoryId)
    }

    /** Tri des Films/Séries sans choix propre à la catégorie : celui de Paramètres. */
    private fun defaultVodOrder(type: MediaType): VodSortOrder =
        if (type == MediaType.Live) VodSortOrder.Provider else _uiState.value.appSettings.vodSortOrder

    /**
     * Films/Séries paginés : l'ordre affiché est celui des pages lues en base (déjà triées), donc
     * une catégorie sans pages pour le tri courant doit repartir de la première page — même si
     * certaines de ses entrées sont déjà en mémoire (favoris, autre tri, autre catégorie).
     */
    private fun needsFirstPage(catalog: Catalog, type: MediaType, categoryId: String, order: VodSortOrder): Boolean =
        if (type != MediaType.Live && catalog.isPaged) vodPageKey(type, categoryId, order) !in _uiState.value.vodPages
        else !catalog.isCategoryLoaded(type, categoryId)

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
            // Voisines préchargées au tri par défaut (une voisine triée autrement relira sa page).
            if (needsFirstPage(catalog, type, neighbor.id, defaultVodOrder(type))) {
                loadCategoryPage(profileId, type, neighbor.id, offset = 0, order = defaultVodOrder(type))
            }
        }
    }

    /**
     * Charge la page suivante d'une catégorie déjà ouverte, à appeler quand la liste/grille
     * approche de sa fin. L'offset se déduit du nombre d'entrées déjà matérialisées pour cette
     * catégorie : les pages s'enchaînent sans trou tant qu'aucun appel ne saute une page.
     */
    fun loadMoreInCategory(type: MediaType, categoryId: String, order: VodSortOrder = defaultVodOrder(type)) {
        val profileId = _uiState.value.activeProfileId ?: return
        val catalog = _uiState.value.catalog ?: return
        // Paginé : l'offset est le nombre d'entrées déjà lues pour CETTE catégorie et ce tri —
        // entriesIn() compterait aussi des entrées chargées ailleurs (favoris, autres catégories
        // dans « Tout ») et ferait sauter des pages.
        val page = if (type != MediaType.Live && catalog.isPaged) {
            _uiState.value.vodPages[vodPageKey(type, categoryId, order)] ?: return ensureCategoryLoaded(type, categoryId, order)
        } else {
            null
        }
        val loaded = page?.size ?: catalog.entriesIn(type, categoryId).size
        if (loaded > 0 && loaded >= catalog.countIn(type, categoryId)) return
        // Films/Séries : la page suivante part de la dernière entrée affichée (curseur d'index).
        loadCategoryPage(profileId, type, categoryId, offset = loaded, order = order, afterKey = page?.lastOrNull()?.key)
    }

    private fun loadCategoryPage(
        profileId: String,
        type: MediaType,
        categoryId: String,
        offset: Int,
        order: VodSortOrder,
        afterKey: String? = null,
    ) {
        val categoryKey = Catalog.categoryKey(type, categoryId)
        val pageKey = vodPageKey(type, categoryId, order)
        val loadKey = "$profileId:$categoryKey:$order:$offset"
        if (!categoryLoadsInFlight.add(loadKey)) return
        setCategoryLoading(type, categoryId, loading = true)
        _uiState.update { if (categoryKey in it.categoryLoadErrors) it.copy(categoryLoadErrors = it.categoryLoadErrors - categoryKey) else it }
        viewModelScope.launch {
            try {
                val page = runCatching { repository.loadCategoryPage(profileId, type, categoryId, offset, order, afterKey = afterKey) }.getOrElse {
                    // Page en échec : signalée à la grille (message + nouvel essai) au lieu d'une fin de liste muette.
                    _uiState.update { state -> state.copy(categoryLoadErrors = state.categoryLoadErrors + categoryKey) }
                    return@launch
                }
                if (page.entries.isEmpty() && offset > 0) return@launch
                if (type == MediaType.Live) {
                    mergeIntoCatalog(profileId) { base ->
                        base.withMaterializedEntries(page.entries, type, categoryId)
                    }
                } else {
                    mergeVodPage(profileId, pageKey, offset, page.entries)
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
    suspend fun mergeIntoCatalog(profileId: String, merge: (Catalog) -> Catalog) {
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
     * Publie une page Films/Séries dans [StreamiaUiState.vodPages] — une liste autonome par
     * catégorie et par tri — puis libère les pages les moins récemment ouvertes au-delà de
     * [MAX_MATERIALIZED_VOD_ENTRIES]. Le catalogue global n'est plus touché : fusionner chaque page
     * recopiait et réindexait toutes les entrées matérialisées (dont les chaînes Direct).
     * Les déplacements de l'organisateur sont appliqués aux seules entrées de la page.
     */
    private suspend fun mergeVodPage(
        profileId: String,
        pageKey: String,
        offset: Int,
        entries: List<MediaEntry>,
    ) {
        catalogLayoutMutation.withLock {
            val moves = _uiState.value.library.movedEntries
            val placed = if (moves.isEmpty()) entries else entries.map { entry -> moves[entry.key]?.let { entry.copy(categoryId = it) } ?: entry }
            _uiState.update { state ->
                if (state.activeProfileId != profileId) return@update state
                val known = state.vodPages[pageKey]
                val page = when {
                    offset == 0 -> placed
                    known != null && known.size == offset -> {
                        val merged = LinkedHashMap<String, MediaEntry>(known.size + placed.size)
                        known.forEach { merged[it.key] = it }
                        placed.forEach { merged.putIfAbsent(it.key, it) }
                        merged.values.toList()
                    }
                    else -> known
                } ?: return@update state
                // Page (re)lue : passe en fin de liste, la plus récemment utilisée.
                val pages = state.vodPages - pageKey + (pageKey to page)
                state.copy(vodPages = retainedVodPages(pages, protectedVodCategoryKey(state), MAX_MATERIALIZED_VOD_ENTRIES))
            }
        }
    }

    /** Catégorie Films/Séries affichée dans le navigateur : ses pages ne sont jamais évincées. */
    private fun protectedVodCategoryKey(state: StreamiaUiState): String? {
        val type = state.browserType?.takeIf { it != MediaType.Live } ?: return null
        return state.browserCategoryId?.let { Catalog.categoryKey(type, it) }
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
    fun ensureSectionLoaded(type: MediaType) {
        val profileId = _uiState.value.activeProfileId ?: return
        val catalog = _uiState.value.catalog ?: return
        if (catalog.isCategoryLoaded(type, Catalog.ALL_CATEGORY_ID)) {
            if (type == MediaType.Live) {
                onLiveSectionReady()
            }
            return
        }
        val loadKey = "$profileId:section:${type.name}"
        if (!categoryLoadsInFlight.add(loadKey)) return
        viewModelScope.launch {
            try {
                val section = runCatching {
                    if (type == MediaType.Live) liveSection(profileId) else repository.loadSection(profileId, type)
                }.getOrNull() ?: return@launch
                mergeIntoCatalog(profileId) { base -> base.withFullSectionMaterialized(section, type) }
                if (type == MediaType.Live) {
                    onLiveSectionReady()
                }
            } finally {
                categoryLoadsInFlight.remove(loadKey)
            }
        }
    }

    /**
     * Section Direct entière lue une seule fois depuis la base par version du catalogue et partagée
     * par le catalogue, l'index des versions et le rapprochement des matchs, qui la relisaient
     * chacun de leur côté au démarrage (des dizaines de milliers de lignes, jusqu'à trois fois).
     */
    private var liveSectionLoad: Pair<String, kotlinx.coroutines.Deferred<List<MediaEntry>>>? = null

    suspend fun liveSection(profileId: String): List<MediaEntry> {
        val current = liveSectionLoad?.takeIf { (id, load) ->
            id == profileId && !(load.isCompleted && load.getCompletionExceptionOrNull() != null)
        }?.second
        val load = current ?: viewModelScope.async(Dispatchers.IO) { repository.loadSection(profileId, MediaType.Live) }
            .also { liveSectionLoad = profileId to it }
        return load.await()
    }

    /** Catalogue remplacé (actualisation, import, autre liste) : la section Direct sera relue. */
    fun invalidateLiveSection() {
        liveSectionLoad = null
    }
}

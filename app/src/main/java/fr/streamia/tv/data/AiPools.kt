package fr.streamia.tv.data

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType

/** Liste courte de candidats réels envoyée au modèle, et l'entrée du catalogue derrière chaque identifiant. */
data class AiPool(val candidates: List<AiCandidate>, val entries: Map<String, MediaEntry>) {
    val isEmpty: Boolean get() = candidates.isEmpty()
}

/**
 * Filtre des contenus que l'assistant a le droit de voir et de proposer : jamais un contenu masqué, ni une catégorie
 * masquée, ni une catégorie verrouillée tant que le contrôle parental n'est pas levé. Ces titres ne sont donc même
 * pas envoyés au fournisseur d'IA.
 */
internal fun allowedEntries(
    categories: List<MediaCategory>,
    library: UserLibrarySnapshot,
    parentalControlEnabled: Boolean,
    parentalUnlocked: Boolean,
): (MediaEntry) -> Boolean {
    val excludedKeys = if (parentalControlEnabled && !parentalUnlocked) {
        library.hiddenCategories + library.lockedCategories
    } else {
        library.hiddenCategories
    }
    val excluded = categories.filter { it.key in excludedKeys }
        .groupBy(MediaCategory::type)
        .mapValues { (_, list) -> list.mapTo(HashSet(), MediaCategory::id) }
    return { entry -> entry.key !in library.hiddenEntries && entry.categoryId !in excluded[entry.type].orEmpty() }
}

/** Numérote [entries] (F1…, S1…) et décrit chacun par [label]. L'ordre donné est conservé. */
internal fun poolOf(entries: List<MediaEntry>, label: (MediaEntry) -> String): AiPool {
    var movies = 0
    var series = 0
    val candidates = ArrayList<AiCandidate>()
    val byId = LinkedHashMap<String, MediaEntry>()
    for (entry in entries.distinctBy(MediaEntry::key)) {
        val id = when (entry.type) {
            MediaType.Movie -> "F${++movies}"
            MediaType.Series -> "S${++series}"
            MediaType.Live -> continue
        }
        candidates += AiCandidate(id, label(entry))
        byId[id] = entry
    }
    return AiPool(candidates, byId)
}

/** « Titre|7.4 » (note si connue), éventuellement « Titre|genre|7.4 » quand la fiche est enrichie. */
internal fun candidateLabel(entry: MediaEntry, genre: String? = null): String =
    listOfNotNull(
        entry.displayName.replace('|', '/'),
        genre?.takeIf(String::isNotBlank)?.let { shorten(it, 40).replace('|', '/') },
        entry.rating?.takeIf { it in 0.1..10.0 }?.let { "%.1f".format(java.util.Locale.ROOT, it) },
    ).joinToString("|")

/**
 * Construit les listes de candidats à partir de l'index local : lectures bornées, aucun parcours du catalogue.
 * Partagé par l'écran (Ce soir, Collections) et le pré-calcul de nuit : les deux envoient exactement la même liste,
 * donc la réponse calculée la nuit est celle que l'écran trouve en cache.
 */
internal class AiPoolBuilder(private val repository: XtreamRepository) {

    /** Nouveautés, mieux notés et favoris : de quoi former des sagas et des thèmes, valable toute la semaine. */
    suspend fun collectionsPool(profileId: String, library: UserLibrarySnapshot, allowed: (MediaEntry) -> Boolean): AiPool {
        val found = LinkedHashMap<String, MediaEntry>()
        for (type in listOf(MediaType.Movie, MediaType.Series)) {
            runCatching { repository.homeRecommendationCandidates(profileId, type, COLLECTION_RECENT) }.getOrDefault(emptyList())
                .forEach { found.putIfAbsent(it.key, it) }
            runCatching {
                repository.loadCategoryPage(profileId, type, Catalog.ALL_CATEGORY_ID, 0, order = VodSortOrder.Rating, limit = COLLECTION_TOP).entries
            }.getOrDefault(emptyList()).forEach { found.putIfAbsent(it.key, it) }
        }
        runCatching { repository.entriesByKeys(profileId, library.favoriteEntries.take(COLLECTION_FAVORITES).toSet()) }
            .getOrDefault(emptyList()).forEach { found.putIfAbsent(it.key, it) }
        val usable = found.values.filter { it.type != MediaType.Live && allowed(it) }
            // Les titres d'une même saga se suivent : le modèle les voit côte à côte.
            .sortedBy { normalizeForMatch(it.displayName) }
        return poolOf(usable) { candidateLabel(it) }
    }

    /** Titres déjà aimés : films bien regardés (récents d'abord) puis favoris. Sert de goût au modèle. */
    suspend fun tastes(profileId: String, library: UserLibrarySnapshot, allowed: (MediaEntry) -> Boolean): List<String> {
        val watchedMovies = library.history.asSequence()
            .filter { it.entry.type == MediaType.Movie && (it.progress >= 0.5f || it.entry.key in library.watchedEntries) }
            .sortedByDescending { it.updatedAt }
            .map { it.entry }
        val favorites = runCatching { repository.entriesByKeys(profileId, library.favoriteEntries.take(TASTE_FAVORITES).toSet()) }
            .getOrDefault(emptyList()).asSequence().filter { it.type != MediaType.Live }
        return (watchedMovies + favorites).filter(allowed).map { it.displayName }.distinct().take(TASTE_LIMIT).toList()
    }

    /**
     * Candidats pour « Ce soir ? » : des catégories qui correspondent à l'humeur (les mieux notées), les
     * recommandations déjà calculées pour l'accueil, puis les nouveautés. Films ou séries selon la durée choisie.
     */
    suspend fun tonightPool(
        profileId: String,
        answers: TonightAnswers,
        categories: List<MediaCategory>,
        library: UserLibrarySnapshot,
        recommended: List<MediaEntry>,
        allowed: (MediaEntry) -> Boolean,
    ): AiPool {
        val genres = if (answers.company == TonightCompany.Family) FAMILY_GENRES else answers.mood.genres
        val moodCategories = if (genres.isEmpty()) emptyList() else matchingCategories(
            AiSearchPlan(null, genres, emptyList(), emptyList(), emptyList(), null, null, SearchSort.Rating, ""),
            categories,
        )
        val (movieQuota, seriesQuota) = when (answers.length) {
            TonightLength.Series -> 4 to 16
            TonightLength.Short, TonightLength.Film -> 16 to 4
            TonightLength.Any -> 10 to 9
        }
        val found = LinkedHashMap<String, MediaEntry>()
        if (moodCategories.isNotEmpty()) {
            val byType = moodCategories.groupBy(MediaCategory::type).mapValues { (_, list) -> list.mapTo(HashSet(), MediaCategory::id) }
            runCatching { repository.searchInCategories(profileId, byType, SearchSort.Rating, 120) }.getOrDefault(emptyList())
                .filter(allowed).let { top ->
                    top.filter { it.type == MediaType.Movie }.take(movieQuota).forEach { found.putIfAbsent(it.key, it) }
                    top.filter { it.type == MediaType.Series }.take(seriesQuota).forEach { found.putIfAbsent(it.key, it) }
                }
        }
        recommended.filter { it.type != MediaType.Live && allowed(it) }.take(RECOMMENDED_IN_POOL).forEach { found.putIfAbsent(it.key, it) }
        for (type in listOf(MediaType.Movie, MediaType.Series)) {
            runCatching { repository.homeRecommendationCandidates(profileId, type, RECENT_IN_POOL) }.getOrDefault(emptyList())
                .filter(allowed).forEach { found.putIfAbsent(it.key, it) }
        }
        val finished = library.history.filter { it.progress >= 0.9f }.mapTo(HashSet()) { it.entry.key } + library.watchedEntries
        val usable = found.values.filter { it.key !in finished }.take(TONIGHT_POOL_LIMIT)
        val features = runCatching { repository.recommendationContentFeatures(profileId, usable) }.getOrDefault(emptyMap())
        return poolOf(usable) { candidateLabel(it, features[it.key]?.genre) }
    }

    private companion object {
        const val COLLECTION_RECENT = 24
        const val COLLECTION_TOP = 24
        const val COLLECTION_FAVORITES = 12
        const val TASTE_FAVORITES = 8
        const val TASTE_LIMIT = 10
        const val RECOMMENDED_IN_POOL = 6
        const val RECENT_IN_POOL = 5
        const val TONIGHT_POOL_LIMIT = 34
        val FAMILY_GENRES = listOf("famille", "family", "enfants", "kids", "animation", "dessin", "cartoon")
    }
}

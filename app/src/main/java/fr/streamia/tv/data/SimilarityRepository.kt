package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.recommendation.ContentCandidateIndex
import fr.streamia.tv.recommendation.ContentFeatures
import fr.streamia.tv.recommendation.IndexedContent
import fr.streamia.tv.recommendation.MetadataSimilarityEngine
import fr.streamia.tv.recommendation.MovieLensNeighbors
import fr.streamia.tv.recommendation.SimilarityBoost
import fr.streamia.tv.recommendation.releaseYear
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Candidats et liens forts des contenus similaires : index des contenus enrichis, recommandations
 * TMDB retrouvées dans la playlist, sagas Wikidata et voisins MovieLens.
 */
class SimilarityRepository internal constructor(
    context: Context,
    private val cache: CatalogCache,
    private val recommendationStore: RecommendationStore,
) {
    /**
     * Candidats « similaires » : d'abord les plus proches dans TOUT le catalogue enrichi (genre,
     * intrigue, personnes, saga — voir [ContentCandidateIndex]), puis les voisins de catégorie.
     * Sert la fiche détail et les rangées « Parce que vous avez regardé » de l'accueil.
     */
    suspend fun similarityCandidates(
        profileId: String,
        source: MediaEntry,
        limit: Int,
        sourceFeatures: ContentFeatures? = null,
    ): List<MediaEntry> {
        val neighbours = cache.loadSimilarityCandidates(profileId, source, limit)
        val tmdbKeys = tmdbRelated(profileId, source).keys
        val index = runCatching { candidateIndex(profileId) }.getOrNull()
            ?: return (cache.loadEntriesByKeys(profileId, LinkedHashSet(tmdbKeys)) + neighbours).distinctBy(MediaEntry::key).take(limit)
        val indexed = indexedSource(source, sourceFeatures)
        val matchedKeys = withContext(BackgroundWork.light) {
            // Liens forts (saga, MovieLens, TMDB) d'abord, puis proximité genre / intrigue / mots-clés / personnes.
            (index.related(indexed, movieLens.of(indexed.tmdbId)).keys + tmdbKeys + index.topMatches(indexed, limit = (limit / 2).coerceAtLeast(1)))
                .distinct()
                .take(limit)
        }
        if (matchedKeys.isEmpty()) return neighbours
        val matched = cache.loadEntriesByKeys(profileId, LinkedHashSet(matchedKeys))
        return (matched + neighbours).distinctBy(MediaEntry::key).take(limit)
    }

    /** Liens forts (même saga Wikidata, voisins MovieLens) à faire remonter dans le classement. */
    suspend fun similarityBoosts(
        profileId: String,
        source: MediaEntry,
        sourceFeatures: ContentFeatures? = null,
    ): Map<String, SimilarityBoost> {
        val tmdb = tmdbRelated(profileId, source)
        val index = runCatching { candidateIndex(profileId) }.getOrNull() ?: return tmdb
        val indexed = indexedSource(source, sourceFeatures)
        val related = withContext(BackgroundWork.light) { index.related(indexed, movieLens.of(indexed.tmdbId)) }
        // Saga / MovieLens gardent la priorité quand ils sont plus sûrs que la recommandation TMDB.
        return tmdb + related.filter { (key, boost) -> (tmdb[key]?.score ?: 0.0) < boost.score }
    }

    private val tmdb = TmdbClient()
    private val wikidata = WikidataClient()
    // LRU borné : une entrée par fiche ouverte, la session pouvant durer des jours sur une TV.
    private val tmdbRelatedCache = android.util.LruCache<String, Map<String, SimilarityBoost>>(TMDB_RELATED_CACHE_SIZE)

    /**
     * Données TMDB d'un contenu (résumé anglais, genres, mots-clés) substituées aux métadonnées du
     * fournisseur pour la comparaison ; interroge TMDB une fois si le contenu n'a jamais été vu.
     */
    suspend fun withTmdb(profileId: String, features: ContentFeatures): ContentFeatures = withContext(Dispatchers.IO) {
        val key = features.entry.key
        val info = recommendationStore.tmdb(profileId, key) ?: run {
            val tmdbId = features.tmdbId?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it != "0" }
            if (!tmdb.enabled || tmdbId == null) return@withContext features
            val fetched = runCatching { tmdb.info(features.entry.type, tmdbId) }.getOrElse { return@withContext features }
            recommendationStore.saveTmdb(profileId, key, fetched)
            fetched
        } ?: return@withContext features
        features.copy(
            plot = info.overview ?: features.plot,
            genre = info.genres.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: features.genre,
            keywords = info.keywords,
        )
    }

    /**
     * Recommandations TMDB retrouvées dans le catalogue : par TMDB ID pour les contenus enrichis,
     * sinon par titre + année (recherche en base, comme « Autres versions »). Bonus modéré : elles
     * amènent des candidats, mais la ressemblance de contenu reste décisive dans le classement.
     */
    private suspend fun tmdbRelated(profileId: String, source: MediaEntry): Map<String, SimilarityBoost> {
        val cacheKey = "$profileId|${source.key}"
        tmdbRelatedCache.get(cacheKey)?.let { return it }
        val info = withContext(Dispatchers.IO) { recommendationStore.tmdb(profileId, source.key) } ?: return emptyMap()
        val titles = info.recommendations.take(TMDB_RELATED_LIMIT)
        val byId = withContext(Dispatchers.IO) { recommendationStore.keysByTmdbId(profileId, source.type, titles.map { it.id }) }
        val tokenizer = MetadataSimilarityEngine()
        val result = LinkedHashMap<String, SimilarityBoost>()
        titles.forEachIndexed { rank, title ->
            val key = byId[title.id] ?: listOfNotNull(title.title, title.originalTitle).firstNotNullOfOrNull { name ->
                val wanted = tokenizer.titleTokens(name).takeIf { it.isNotEmpty() } ?: return@firstNotNullOfOrNull null
                cache.search(profileId, wanted.joinToString(" "), source.type, TMDB_SEARCH_LIMIT).firstOrNull { entry ->
                    val year = ContentFeatures(entry).releaseYear()
                    tokenizer.titleTokens(entry.displayName) == wanted && (year == null || title.year == null || year == title.year)
                }?.key
            } ?: return@forEachIndexed
            if (key != source.key) result.putIfAbsent(key, SimilarityBoost(TMDB_TOP_SCORE - rank * TMDB_RANK_STEP, "Recommandé par TMDB"))
        }
        tmdbRelatedCache.put(cacheKey, result)
        return result
    }

    /** Interroge TMDB pour un lot de contenus enrichis jamais vus. Renvoie le nombre traité (0 = fini). */
    suspend fun fetchTmdbBatch(
        profileId: String,
        batchSize: Int,
        pauseMs: Long,
        shouldStop: () -> Boolean = { false },
    ): Int = withContext(Dispatchers.IO) {
        if (!tmdb.enabled) return@withContext 0
        val pending = recommendationStore.missingTmdbLookups(profileId, batchSize)
        var failures = 0
        for ((key, tmdbId) in pending) {
            if (shouldStop()) break
            val type = MediaType.entries.first { it.name == key.substringBefore(':') }
            // Échec réseau : non mémorisé, retenté au prochain passage ; plusieurs de suite = TMDB injoignable.
            runCatching { tmdb.info(type, tmdbId) }
                .onSuccess { recommendationStore.saveTmdb(profileId, key, it); failures = 0 }
                .onFailure { if (++failures >= TMDB_MAX_FAILURES) throw it }
            kotlinx.coroutines.delay(pauseMs)
        }
        pending.size
    }

    private fun indexedSource(source: MediaEntry, features: ContentFeatures?) = IndexedContent(
        source.key,
        source.displayName,
        features?.plot ?: source.plot,
        features?.genre,
        features?.cast,
        features?.director,
        tmdbId = features?.tmdbId,
        keywords = features?.keywords.orEmpty(),
    )

    private val movieLens = MovieLensNeighbors.fromAssets(context)

    private val candidateIndexLock = Mutex()
    @Volatile private var candidateIndexState: CandidateIndexState? = null
    private class CandidateIndexState(val profileId: String, val sourceCount: Int, val index: ContentCandidateIndex)

    /**
     * Index reconstruit seulement quand l'enrichissement a nettement progressé (+5 %, au moins 200
     * fiches) : l'enrichissement en arrière-plan ajoute des fiches en continu, reconstruire à chaque
     * ouverture de fiche coûterait une seconde de CPU sur un petit boîtier.
     */
    private suspend fun candidateIndex(profileId: String): ContentCandidateIndex? {
        val count = withContext(Dispatchers.IO) { recommendationStore.featureCount(profileId) + recommendationStore.sagaCount(profileId) }
        if (count == 0) return null
        fun fresh() = candidateIndexState?.takeIf {
            it.profileId == profileId && count - it.sourceCount < maxOf(INDEX_REBUILD_MIN_DELTA, it.sourceCount / 20)
        }?.index
        fresh()?.let { return it }
        return candidateIndexLock.withLock {
            fresh() ?: run {
                val features = withContext(Dispatchers.IO) { recommendationStore.features(profileId) }
                val sagas = withContext(Dispatchers.IO) { recommendationStore.sagas(profileId) }
                // Seulement les contenus enrichis : charger tout Films + Séries (plus de 200 000
                // entrées avec résumés) saturait la mémoire et faisait planter l'app (OOM).
                val entries = cache.loadEntriesByKeys(profileId, features.keys).associateBy(MediaEntry::key)
                val index = withContext(BackgroundWork.light) {
                    ContentCandidateIndex(
                        features.mapNotNull { (key, stored) ->
                            val entry = entries[key] ?: return@mapNotNull null
                            IndexedContent(
                                key,
                                entry.displayName,
                                stored.plot ?: entry.plot,
                                stored.genre,
                                stored.cast,
                                stored.director,
                                tmdbId = stored.tmdbId,
                                sagas = sagas[key].orEmpty(),
                                keywords = stored.keywords,
                            )
                        },
                    )
                }
                candidateIndexState = CandidateIndexState(profileId, count, index)
                index
            }
        }
    }

    /**
     * Interroge Wikidata pour un lot de contenus enrichis jamais vérifiés. Renvoie le nombre traité
     * (0 = plus rien à faire). Les contenus sans saga sont mémorisés vides pour ne pas redemander.
     */
    suspend fun fetchSagaBatch(profileId: String, batchSize: Int): Int = withContext(Dispatchers.IO) {
        val pending = recommendationStore.missingSagaLookups(profileId, batchSize)
        if (pending.isEmpty()) return@withContext 0
        val found = pending.entries.groupBy { MediaType.entries.first { t -> t.name == it.key.substringBefore(':') } }
            .flatMap { (type, rows) ->
                val byTmdb = wikidata.sagas(type, rows.map { it.value }.distinct())
                rows.map { it.key to byTmdb[it.value].orEmpty() }
            }
            .toMap()
        recommendationStore.saveSagas(profileId, found)
        pending.size
    }
}

private const val TMDB_RELATED_LIMIT = 20
private const val TMDB_SEARCH_LIMIT = 20
private const val TMDB_TOP_SCORE = 0.70
private const val TMDB_RANK_STEP = 0.01
private const val TMDB_MAX_FAILURES = 5
private const val INDEX_REBUILD_MIN_DELTA = 200
private const val TMDB_RELATED_CACHE_SIZE = 64

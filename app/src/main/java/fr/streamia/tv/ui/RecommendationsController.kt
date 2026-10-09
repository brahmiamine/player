package fr.streamia.tv.ui

import fr.streamia.tv.data.BackgroundWork
import androidx.lifecycle.viewModelScope
import fr.streamia.tv.data.HomeBlock
import fr.streamia.tv.data.JustWatchSection
import fr.streamia.tv.data.homeBlock
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaDetails
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.recommendation.ContentFeatures
import fr.streamia.tv.recommendation.RecommendationBuildContext
import fr.streamia.tv.recommendation.RecommendationEngine
import fr.streamia.tv.recommendation.RecommendationProfileInput
import fr.streamia.tv.recommendation.RecommendationRow
import fr.streamia.tv.recommendation.RecommendationRowKind
import fr.streamia.tv.recommendation.RecommendedMedia
import fr.streamia.tv.recommendation.ViewingRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Recommandations : rangées « Pour vous » et JustWatch de l'accueil, contenus similaires des fiches Film et Série.
 */
internal class RecommendationsController(host: StreamiaStateHolder) : StreamiaController(host) {
    private val recommendationEngine = RecommendationEngine()

    private var homeRecommendationBuildSequence = 0L

    private var homeRecommendationJob: Job? = null

    private var justWatchJob: Job? = null

    private var justWatchRowsProfileId: String? = null

    private var homeRecommendationLastBuiltProfileId: String? = null

    private var homeRecommendationLastBuiltAtMillis = 0L

    /**
     * Construit les rangées « Recommandé pour vous »/« Parce que vous avez regardé… » de l'accueil.
     * Lecture bornée depuis SQLite, calcul CPU sur Dispatchers.Default, résultat jeté si le profil
     * actif a changé pendant le calcul.
     */
    fun refreshHomeRecommendations(force: Boolean = false) {
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
        val excludedCategoryIds = excludedVodCategoryIds(state, catalog)

        loadJustWatchRows(profileId, library.hiddenEntries, excludedCategoryIds)

        val sequence = ++homeRecommendationBuildSequence
        homeRecommendationJob?.cancel()

        // Comme pour les Matchs : le scoring (similarité texte, décroissance des signaux) est du
        // CPU pur sur potentiellement plusieurs centaines de candidats, donc jamais sur Main.
        homeRecommendationJob = viewModelScope.launch(BackgroundWork.light) {
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
                        repository.similarity.similarityCandidates(profileId, source, HOME_RECOMMENDATION_PER_SOURCE_LIMIT)
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
                if (_uiState.value.activeProfileId == profileId) _homeState.update { it.copy(homeRecommendationRows = emptyList()) }
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
                    repository.similarity.similarityBoosts(profileId, source, detailsByKey[source.key])
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
            val rows = snapshot.rows

            if (sequence != homeRecommendationBuildSequence || _uiState.value.activeProfileId != profileId) return@launch
            homeRecommendationLastBuiltProfileId = profileId
            homeRecommendationLastBuiltAtMillis = System.currentTimeMillis()
            if (_uiState.value.activeProfileId == profileId) _homeState.update { it.copy(homeRecommendationRows = rows) }
            // Titres des cartes de l'accueil nettoyés par l'IA (sans effet si elle est désactivée).
            runCatching { repository.ai.cleanTitles(rows.flatMap { row -> row.items.map { it.entry } }) }
        }
    }

    /** Déconnexion : calculs en cours abandonnés, la prochaine ouverture reconstruit tout. */
    fun reset() {
        homeRecommendationJob?.cancel()
        homeRecommendationJob = null
        justWatchJob?.cancel()
        homeRecommendationBuildSequence += 1
        homeRecommendationLastBuiltProfileId = null
        homeRecommendationLastBuiltAtMillis = 0L
    }

    /** Catégories Films/Séries masquées (et verrouillées tant que le contrôle parental n'est pas levé). */
    private fun excludedVodCategoryIds(state: StreamiaUiState, catalog: Catalog): Set<String> {
        val library = state.library
        val excludedCategoryKeys = if (state.appSettings.parentalControlEnabled && !state.parentalUnlocked) {
            library.hiddenCategories + library.lockedCategories
        } else {
            library.hiddenCategories
        }
        return catalog.categories.asSequence()
            .filter { it.type != MediaType.Live && it.key in excludedCategoryKeys }
            .mapTo(mutableSetOf()) { it.id }
    }

    /**
     * Rangées JustWatch, indépendantes des recommandations IA (plus besoin d'attendre leur calcul).
     * Celles gardées sur disque s'affichent tout de suite ; les expirées sont recalculées (réseau +
     * rapprochement avec la playlist) en parallèle, l'ancienne rangée restant affichée en attendant.
     */
    fun reloadJustWatchRows() {
        val state = _uiState.value
        val profileId = state.activeProfileId ?: return
        val catalog = state.catalog ?: return
        loadJustWatchRows(profileId, state.library.hiddenEntries, excludedVodCategoryIds(state, catalog))
    }

    private fun loadJustWatchRows(profileId: String, hiddenEntries: Set<String>, excludedCategoryIds: Set<String>) {
        justWatchJob?.cancel()
        // Autre liste : ses rangées ne doivent pas rester affichées sous les contenus de la nouvelle.
        if (justWatchRowsProfileId != profileId) _homeState.update { it.copy(homeJustWatchRows = emptyList()) }
        justWatchRowsProfileId = profileId
        val disabledBlocks = _uiState.value.appSettings.disabledHomeBlocks
        justWatchJob = viewModelScope.launch(BackgroundWork.light) {
            JustWatchSection.entries.filter { it.homeBlock !in disabledBlocks }.forEach { section ->
                launch {
                    fun publish(entries: List<MediaEntry>) = publishJustWatchRow(profileId, section, entries, hiddenEntries, excludedCategoryIds)
                    val cached = runCatching { repository.trending.cachedJustWatch(profileId, section) }.getOrNull()
                    cached?.let { publish(it.entries) }
                    if (cached?.fresh == true) return@launch
                    // Échec réseau : la rangée gardée sur disque reste affichée.
                    runCatching { repository.trending.justWatch(profileId, section, JUSTWATCH_ROW_LIMIT * 2) }.onSuccess(::publish)
                }
            }
        }
    }

    private fun publishJustWatchRow(
        profileId: String,
        section: JustWatchSection,
        entries: List<MediaEntry>,
        hiddenEntries: Set<String>,
        excludedCategoryIds: Set<String>,
    ) {
        val items = entries.filterNot { it.key in hiddenEntries || it.categoryId in excludedCategoryIds }.take(JUSTWATCH_ROW_LIMIT)
        // Un Top 10 n'a que 10 titres : quelques-uns suffisent à former une rangée.
        val minimum = when (section) {
            JustWatchSection.TopMoviesWeek, JustWatchSection.TopSeriesWeek -> 1
            else -> JUSTWATCH_ROW_MIN
        }
        val kind = when (section) {
            JustWatchSection.TopMoviesWeek -> RecommendationRowKind.JustWatchTopMoviesWeek
            JustWatchSection.TopSeriesWeek -> RecommendationRowKind.JustWatchTopSeriesWeek
            JustWatchSection.PopularMovies -> RecommendationRowKind.JustWatchPopularMovies
            JustWatchSection.PopularSeries -> RecommendationRowKind.JustWatchPopularSeries
            JustWatchSection.NewMovies -> RecommendationRowKind.JustWatchNewMovies
            JustWatchSection.NewSeries -> RecommendationRowKind.JustWatchNewSeries
        }
        val row = items.takeIf { it.size >= minimum }?.let { RecommendationRow(kind, section.title, it.map { entry -> RecommendedMedia(entry, score = 0.0) }) }
        if (_uiState.value.activeProfileId != profileId) return
        _homeState.update { current ->
            // Ordre JustWatch fixe (ordre de RecommendationRowKind), quel que soit l'ordre d'arrivée.
            current.copy(homeJustWatchRows = (current.homeJustWatchRows.filterNot { it.kind == kind } + listOfNotNull(row)).sortedBy { it.kind.ordinal })
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
    suspend fun loadSimilarMedia(profileId: String, entry: MediaEntry, details: MediaDetails?) {
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
        val sourceFeatures = repository.similarity.withTmdb(profileId, ContentFeatures.from(entry, details))
        val candidates = runCatching {
            repository.similarity.similarityCandidates(profileId, entry, SIMILAR_CANDIDATE_LIMIT, sourceFeatures)
        }.getOrDefault(emptyList())
        if (candidates.isEmpty()) return
        val boosts = runCatching { repository.similarity.similarityBoosts(profileId, entry, sourceFeatures) }.getOrDefault(emptyMap())

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
                if (onSameScreen) state.copy(similarMedia = items, similarLoading = false) else state
            }
        }

        suspend fun rank(): List<RecommendedMedia> = withContext(BackgroundWork.light) {
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
}

private const val HOME_RECOMMENDATION_CANDIDATE_LIMIT = 400

private const val JUSTWATCH_ROW_LIMIT = 20

private const val JUSTWATCH_ROW_MIN = 5

private const val HOME_RECOMMENDATION_RECENT_LIMIT = 240

private const val HOME_RECOMMENDATION_TASTE_SOURCE_LIMIT = 4

private const val HOME_RECOMMENDATION_PER_SOURCE_LIMIT = 80

private const val HOME_RECOMMENDATION_REBUILD_INTERVAL_MS = 5 * 60_000L

private const val SIMILAR_CANDIDATE_LIMIT = 300

private const val SIMILAR_RESULT_LIMIT = 12

private const val SIMILAR_TARGET_COUNT = 8

private const val SIMILAR_ENRICH_LIMIT = 4

private const val SIMILAR_ENRICH_CONCURRENCY = 4

private const val SIMILAR_DETAIL_MIN_SCORE = 0.28

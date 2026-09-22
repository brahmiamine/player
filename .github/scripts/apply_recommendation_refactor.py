from pathlib import Path
from textwrap import dedent, indent


def kotlin(text: str, spaces: int) -> str:
    return indent(dedent(text).strip("\n"), " " * spaces) + "\n\n"


def insert_before(path: str, marker: str, unique: str, addition: str) -> None:
    file = Path(path)
    text = file.read_text()
    if unique in text:
        return
    position = text.index(marker)
    file.write_text(text[:position] + addition + text[position:])


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Expected text not found in {path}: {old[:120]!r}")
    file.write_text(text.replace(old, new, 1))


def replace_between(path: str, start: str, end: str, replacement: str) -> None:
    file = Path(path)
    text = file.read_text()
    start_index = text.index(start)
    end_index = text.index(end, start_index)
    file.write_text(text[:start_index] + replacement + text[end_index:])


insert_before(
    "app/src/test/java/fr/streamia/tv/recommendation/ContentSimilarityTest.kt",
    "    private fun features(\n",
    "same broad genre without related plot is rejected",
    kotlin(
        '''
        @Test
        fun `same broad genre without related plot is rejected`() {
            val source = features(
                10,
                "Front Line",
                "Des soldats isolés derrière les lignes ennemies organisent une mission de sauvetage.",
                genre = "Action, Thriller",
            )
            val unrelated = features(
                11,
                "Summer Kitchen",
                "Une cheffe ouvre un restaurant familial et tombe amoureuse au bord de la mer.",
                genre = "Action, Thriller",
            )

            val result = engine.compare(source, unrelated)

            assertFalse(result.substantive)
            assertEquals(0.0, result.score, 0.0001)
        }
        ''',
        4,
    ),
)

insert_before(
    "app/src/test/java/fr/streamia/tv/recommendation/RecommendationEngineTest.kt",
    "    private fun movie(\n",
    "detail similarity keeps candidates matching both genre and story",
    kotlin(
        '''
        @Test
        fun `detail similarity keeps candidates matching both genre and story`() {
            val sourceEntry = movie(90, "Deep Orbit", 8.4, "science", "équipage spatial mission planète inconnue")
            val source = ContentFeatures(
                entry = sourceEntry,
                plot = sourceEntry.plot,
                genre = "Science-fiction, Thriller",
            )
            val close = movie(1, "Silent Planet", 8.0, "science", "astronautes en mission sur une planète inconnue")
            val unrelated = movie(2, "Kitchen Rush", 8.8, "science", "restaurant familial cuisine amour vacances")
            val details = mapOf(
                close.key to ContentFeatures(close, plot = close.plot, genre = "Science-fiction, Thriller"),
                unrelated.key to ContentFeatures(unrelated, plot = unrelated.plot, genre = "Science-fiction, Thriller"),
            )

            val result = engine.similarTo(
                source = source,
                candidates = listOf(unrelated, close),
                detailsByKey = details,
            )

            assertEquals(listOf(close.key), result.map { it.entry.key })
        }
        ''',
        4,
    ),
)

similarity = "app/src/main/java/fr/streamia/tv/recommendation/ContentSimilarity.kt"
replace_between(
    similarity,
    "        // Les signaux descriptifs dominent.",
    "        var metadataScore",
    kotlin(
        '''
        // Pour une fiche détail, l'histoire et le genre doivent dominer nettement.
        // Catégorie IPTV, année et note restent seulement des critères de départage.
        add(0.06, titleSimilarity, sourceTitleTokens.isNotEmpty() && candidateTitleTokens.isNotEmpty())
        add(0.46, plotSimilarity, sourcePlotTokens.isNotEmpty() && candidatePlotTokens.isNotEmpty())
        add(0.34, genreSimilarity, sourceGenres.isNotEmpty() && candidateGenres.isNotEmpty())
        add(0.04, castSimilarity, sourceCast.isNotEmpty() && candidateCast.isNotEmpty())
        add(0.05, directorSimilarity, sourceDirector.isNotEmpty() && candidateDirector.isNotEmpty())
        add(0.01, countrySimilarity, sourceCountries.isNotEmpty() && candidateCountries.isNotEmpty())
        add(0.02, yearSimilarity, extractYear(source) != null && extractYear(candidate) != null)
        add(0.01, ratingSimilarity, source.rating != null && candidate.rating != null)
        add(0.01, if (categoryMatch) 1.0 else 0.0, true)
        ''',
        8,
    ),
)
replace_between(
    similarity,
    "        val strongTitleRelation = titleSimilarity >= 0.34",
    "        // Deux genres explicitement incompatibles",
    kotlin(
        '''
        val strongTitleRelation = titleSimilarity >= 0.45
        val plotAndGenreRelation = plotSimilarity >= 0.08 && genreSimilarity >= 0.34
        val strongPlotRelation = plotSimilarity >= 0.18
        val strongGenreUniverse = genreSimilarity >= 0.66 &&
            (titleSimilarity >= 0.34 || castSimilarity >= 0.20 || directorSimilarity >= 0.72)
        val peopleRelation = directorSimilarity >= 0.72 || castSimilarity >= 0.30
        val semanticRelation = semantic != null && semantic >= 0.62
        val substantive = strongTitleRelation || plotAndGenreRelation || strongPlotRelation ||
            strongGenreUniverse || peopleRelation || semanticRelation
        ''',
        8,
    ),
)
replace_between(
    similarity,
    "        val reason = when {",
    "\n\n        return SimilarityScore",
    kotlin(
        '''
        val reason = when {
            genreSimilarity >= 0.66 && plotSimilarity >= 0.10 -> "Genre, intrigue et univers très proches"
            plotAndGenreRelation -> "Genre et histoire similaires"
            semantic != null && semantic >= 0.72 -> "Intrigue et ambiance similaires"
            directorSimilarity >= 0.72 -> "Même réalisateur"
            plotSimilarity >= 0.18 -> "Intrigue et thèmes similaires"
            castSimilarity >= 0.30 -> "Distribution similaire"
            titleSimilarity >= 0.45 -> "Même saga ou univers"
            else -> null
        }
        ''',
        8,
    ).rstrip() + "\n",
)

replace_between(
    "app/src/main/java/fr/streamia/tv/data/CatalogDatabase.kt",
    "    fun loadRecommendationCandidates(",
    "    private fun loadRecent(",
    kotlin(
        '''
        fun loadHomeRecommendationCandidates(profileId: String, type: MediaType, limit: Int): List<MediaEntry> =
            loadRecent(profileId, type, limit)

        /**
         * Pool de fiche détail centré sur le média : la majorité des candidats provient de
         * sa catégorie fournisseur et de sa position voisine dans la playlist. Les requêtes
         * utilisent idx_catalog_category, donc elles restent rapides sur un très gros catalogue.
         */
        fun loadSimilarityCandidates(profileId: String, source: MediaEntry, limit: Int): List<MediaEntry> {
            if (limit <= 0) return emptyList()
            val categoryTarget = (limit * 3 / 4).coerceAtLeast(1)
            val forward = loadCategoryNeighbors(profileId, source, forward = true, limit = categoryTarget)
            val backward = loadCategoryNeighbors(profileId, source, forward = false, limit = categoryTarget)
            val categoryCandidates = interleave(forward, backward, categoryTarget)
            val seen = categoryCandidates.mapTo(mutableSetOf()) { it.key }
            return buildList {
                addAll(categoryCandidates)
                loadRecent(profileId, source.type, limit).forEach { candidate ->
                    if (candidate.key != source.key && seen.add(candidate.key)) add(candidate)
                }
            }.take(limit)
        }

        private fun loadCategoryNeighbors(
            profileId: String,
            source: MediaEntry,
            forward: Boolean,
            limit: Int,
        ): List<MediaEntry> {
            if (limit <= 0) return emptyList()
            val comparison = if (forward) ">" else "<"
            val direction = if (forward) "ASC" else "DESC"
            return readableDatabase.rawQuery(
                """
                SELECT ${ENTRY_COLUMNS.joinToString()} FROM catalog_entries
                WHERE profile_id = ? AND media_type = ? AND category_id = ? AND navigable = 1
                  AND (number $comparison ? OR (number = ? AND media_id $comparison ?))
                ORDER BY number $direction, media_id $direction
                LIMIT ?
                """.trimIndent(),
                arrayOf(
                    profileId,
                    source.type.name,
                    source.categoryId,
                    source.number.toString(),
                    source.number.toString(),
                    source.id.toString(),
                    limit.toString(),
                ),
            ).use(::readEntries)
        }

        private fun interleave(
            first: List<MediaEntry>,
            second: List<MediaEntry>,
            limit: Int,
        ): List<MediaEntry> {
            val result = ArrayList<MediaEntry>(limit)
            var index = 0
            while (result.size < limit && (index < first.size || index < second.size)) {
                if (index < first.size) result += first[index]
                if (result.size < limit && index < second.size) result += second[index]
                index += 1
            }
            return result
        }
        ''',
        4,
    ),
)

replace_between(
    "app/src/main/java/fr/streamia/tv/data/CatalogCache.kt",
    "    /**\n     * Pool borné pour l'IA",
    "    suspend fun search(",
    kotlin(
        '''
        /** Pool récent borné, utilisé pour « À découvrir » et « Nouveautés ». */
        suspend fun loadHomeRecommendationCandidates(
            profileId: String,
            type: MediaType,
            limit: Int,
        ): List<MediaEntry> = withContext(Dispatchers.IO) {
            ensureMigrated(profileId)
            database.loadHomeRecommendationCandidates(profileId, type, limit)
        }

        /** Pool indexé centré sur le film ou la série actuellement ouvert. */
        suspend fun loadSimilarityCandidates(
            profileId: String,
            source: MediaEntry,
            limit: Int,
        ): List<MediaEntry> = withContext(Dispatchers.IO) {
            ensureMigrated(profileId)
            database.loadSimilarityCandidates(profileId, source, limit)
        }
        ''',
        4,
    ),
)

replace_between(
    "app/src/main/java/fr/streamia/tv/data/XtreamRepository.kt",
    "    /** Pool borné de candidats VOD",
    "    /**\n     * Fusionne",
    kotlin(
        '''
        /** Contenus récents pour l'accueil ; les goûts complètent ce pool dans le ViewModel. */
        suspend fun homeRecommendationCandidates(profileId: String, type: MediaType, limit: Int): List<MediaEntry> =
            cache.loadHomeRecommendationCandidates(profileId, type, limit)

        /** Candidats rapides centrés sur la catégorie du média affiché dans une fiche détail. */
        suspend fun similarityCandidates(profileId: String, source: MediaEntry, limit: Int): List<MediaEntry> =
            cache.loadSimilarityCandidates(profileId, source, limit)

        /** Retours explicites déjà persistés : « plus comme ça » / « moins comme ça ». */
        suspend fun recommendationFeedback(profileId: String) =
            withContext(Dispatchers.IO) { recommendationStore.feedback(profileId) }
        ''',
        4,
    ),
)

view_model = "app/src/main/java/fr/streamia/tv/ui/StreamiaViewModel.kt"
replace_between(
    view_model,
    "            val candidates = listOf(MediaType.Movie, MediaType.Series).flatMap { type ->",
    "            if (sequence != homeRecommendationBuildSequence",
    kotlin(
        '''
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
            (recent + tasteCandidates)
                .distinctBy(MediaEntry::key)
                .take(HOME_RECOMMENDATION_CANDIDATE_LIMIT)
        }
        ''',
        12,
    ),
)
replace_between(
    view_model,
    "            val detailsByKey = runCatching {\n                repository.recommendationContentFeatures(profileId, detailsSource)",
    "            if (sequence != homeRecommendationBuildSequence",
    kotlin(
        '''
        val detailsByKey = runCatching {
            repository.recommendationContentFeatures(profileId, detailsSource)
        }.getOrDefault(emptyMap())
        val feedback = runCatching { repository.recommendationFeedback(profileId) }.getOrDefault(emptyMap())
        ''',
        12,
    ),
)
replace_once(
    view_model,
    "                    knownEntriesByKey = knownEntriesByKey,\n                    hiddenEntries = library.hiddenEntries,",
    "                    knownEntriesByKey = knownEntriesByKey,\n                    feedback = feedback,\n                    hiddenEntries = library.hiddenEntries,",
)
replace_once(
    view_model,
    "repository.recommendationCandidates(profileId, entry.type, SIMILAR_CANDIDATE_LIMIT)",
    "repository.similarityCandidates(profileId, entry, SIMILAR_CANDIDATE_LIMIT)",
)
replace_once(
    view_model,
    "private const val HOME_RECOMMENDATION_CANDIDATE_LIMIT = 400",
    "private const val HOME_RECOMMENDATION_CANDIDATE_LIMIT = 400\nprivate const val HOME_RECOMMENDATION_RECENT_LIMIT = 240\nprivate const val HOME_RECOMMENDATION_TASTE_SOURCE_LIMIT = 4\nprivate const val HOME_RECOMMENDATION_PER_SOURCE_LIMIT = 80",
)
replace_once(view_model, "private const val SIMILAR_CANDIDATE_LIMIT = 400", "private const val SIMILAR_CANDIDATE_LIMIT = 300")
replace_once(view_model, "private const val SIMILAR_ENRICH_LIMIT = 15", "private const val SIMILAR_ENRICH_LIMIT = 12")
replace_once(view_model, "private const val SIMILAR_ENRICH_CONCURRENCY = 3", "private const val SIMILAR_ENRICH_CONCURRENCY = 4")
replace_once(view_model, "private const val SIMILAR_DETAIL_MIN_SCORE = 0.18", "private const val SIMILAR_DETAIL_MIN_SCORE = 0.28")

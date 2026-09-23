package fr.streamia.tv.recommendation

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    private val engine = RecommendationEngine()

    @Test
    fun `cold start shows a selection then recently added`() {
        val snapshot = engine.buildSnapshot(
            "p1",
            RecommendationBuildContext(
                candidates = (1..20).map { movie(it, "Movie $it", 6.0 + it % 4) },
                nowMillis = 1_000_000L,
            ),
        )

        assertTrue(snapshot.confidence < 0.3)
        assertEquals(RecommendationRowKind.Discover, snapshot.rows.first().kind)
        assertEquals(listOf("Sélection pour vous", "Récemment ajoutés"), snapshot.rows.map { it.title })
    }

    @Test
    fun `strong viewing signals switch primary row to personalized recommendations`() {
        val watched = movie(10, "Space One", 8.0, category = "science", plot = "astronautes mission espace terre")
        val candidates = (1..8).map { id ->
            movie(id, "Science $id", 7.5, category = "science", plot = "mission espace astronautes planète")
        }
        val history = listOf(
            ViewingRecord(watched, 7_000_000, 7_200_000, 900_000L),
            ViewingRecord(movie(11, "Space Two", 8.0, "science", "voyage espace planète"), 6_000_000, 6_200_000, 800_000L),
        )
        val feedback = RecommendationFeedback(
            entry = movie(12, "Space Three", 8.0, "science", "mission spatiale"),
            kind = RecommendationFeedbackKind.MoreLikeThis,
            occurredAtMillis = 950_000L,
        )

        val snapshot = engine.buildSnapshot(
            "p1",
            RecommendationBuildContext(
                candidates = candidates,
                profile = RecommendationProfileInput(
                    history = history,
                    feedback = mapOf(feedback.entry.key to feedback),
                ),
                nowMillis = 1_000_000L,
            ),
        )

        assertEquals(RecommendationRowKind.ForYou, snapshot.rows.first().kind)
        assertEquals("Recommandé pour vous", snapshot.rows.first().title)
        assertTrue(snapshot.confidence >= 0.3)
    }

    @Test
    fun `more like this wins the secondary slot over a strong playback`() {
        val heist = movie(900, "Heist", 8.0, "crime", HEIST_PLOT)
        val space = movie(901, "Interstellar", 8.0, "science", SPACE_PLOT)

        val snapshot = engine.buildSnapshot(
            "p1",
            RecommendationBuildContext(
                candidates = spaceAndHeistMovies(),
                profile = RecommendationProfileInput(
                    history = listOf(ViewingRecord(heist, 7_000_000, 7_200_000, 990_000L)),
                    feedback = mapOf(
                        space.key to RecommendationFeedback(space, RecommendationFeedbackKind.MoreLikeThis, 900_000L),
                    ),
                ),
                nowMillis = 1_000_000L,
            ),
        )

        val secondary = snapshot.rows[1]
        assertEquals("Parce que vous aimez Interstellar", secondary.title)
        assertTrue(secondary.items.none { it.entry.key == space.key })
    }

    @Test
    fun `strong playback drives the secondary slot when more like this has nothing similar`() {
        val heist = movie(900, "Heist", 8.0, "crime", HEIST_PLOT)
        val cooking = movie(902, "Chef", 8.0, "food", "restaurant familial cuisine chef recette gastronomie")

        val snapshot = engine.buildSnapshot(
            "p1",
            RecommendationBuildContext(
                candidates = spaceAndHeistMovies(),
                profile = RecommendationProfileInput(
                    history = listOf(ViewingRecord(heist, 7_000_000, 7_200_000, 990_000L)),
                    feedback = mapOf(
                        cooking.key to RecommendationFeedback(cooking, RecommendationFeedbackKind.MoreLikeThis, 995_000L),
                    ),
                ),
                nowMillis = 1_000_000L,
            ),
        )

        val secondary = snapshot.rows[1]
        assertEquals("Parce que vous avez regardé Heist", secondary.title)
        assertTrue(secondary.items.none { it.entry.key == heist.key })
    }

    @Test
    fun `a few minutes of playback is not a strong source`() {
        val heist = movie(900, "Heist", 8.0, "crime", HEIST_PLOT)

        val snapshot = engine.buildSnapshot(
            "p1",
            RecommendationBuildContext(
                candidates = spaceAndHeistMovies(),
                profile = RecommendationProfileInput(
                    history = listOf(ViewingRecord(heist, 60_000, 7_200_000, 990_000L)),
                ),
                nowMillis = 1_000_000L,
            ),
        )

        assertEquals("Récemment ajoutés", snapshot.rows[1].title)
    }

    @Test
    fun `personalized profile without similarity source gets recently added for you`() {
        val favorites = listOf(movie(900, "Fav A", 8.0), movie(901, "Fav B", 8.0))

        val snapshot = engine.buildSnapshot(
            "p1",
            RecommendationBuildContext(
                candidates = (1..20).map { movie(it, "Movie $it", 7.0) },
                profile = RecommendationProfileInput(
                    favoriteEntries = favorites.mapTo(mutableSetOf()) { it.key },
                    knownEntriesByKey = favorites.associateBy { it.key },
                ),
                nowMillis = 1_000_000L,
            ),
        )

        assertTrue(snapshot.confidence >= 0.3)
        assertEquals(listOf("Recommandé pour vous", "Récemment ajoutés pour vous"), snapshot.rows.map { it.title })
    }

    @Test
    fun `without added date recent releases uses the release year only`() {
        val now = 1_790_000_000_000L // septembre 2026
        val recent = (1..10).map { movie(it, "Recent $it (MULTI) FHD ${if (it % 2 == 0) 2026 else 2025}", 7.0, addedAt = null) }
        val old = (11..30).map { movie(it, "Old $it 2019", 8.0, addedAt = null) }

        val snapshot = engine.buildSnapshot("p1", RecommendationBuildContext(candidates = recent + old, nowMillis = now))

        val secondary = snapshot.rows[1]
        assertEquals("Sorties récentes", secondary.title)
        assertTrue(secondary.items.all { it.entry.name.startsWith("Recent") })
    }

    @Test
    fun `no freshness data means no misleading secondary row`() {
        val snapshot = engine.buildSnapshot(
            "p1",
            RecommendationBuildContext(
                candidates = (1..20).map { movie(it, "Movie $it", 7.0, addedAt = null) },
                nowMillis = 1_790_000_000_000L,
            ),
        )

        assertEquals(listOf("Sélection pour vous"), snapshot.rows.map { it.title })
    }

    @Test
    fun `engine only recommends movies and series never live tv`() {
        val live = (1..10).map { MediaEntry(id = it, name = "Live $it", categoryId = "live", iconUrl = null, number = it) }

        val snapshot = engine.buildSnapshot(
            "p1",
            RecommendationBuildContext(candidates = live + (1..20).map { movie(it, "Movie $it", 7.0) }, nowMillis = 1_000_000L),
        )

        assertTrue(snapshot.rows.none { it.title == "À la TV maintenant" })
        assertTrue(snapshot.rows.flatMap { it.items }.none { it.entry.type == MediaType.Live })
    }

    @Test
    fun `hidden, hidden category and rejected content never appear in any row`() {
        val source = movie(900, "Interstellar", 8.0, "science", SPACE_PLOT)
        val candidates = spaceAndHeistMovies()
        val hidden = candidates[0]
        val rejected = candidates[1]

        val snapshot = engine.buildSnapshot(
            "p1",
            RecommendationBuildContext(
                candidates = candidates,
                profile = RecommendationProfileInput(
                    feedback = mapOf(
                        source.key to RecommendationFeedback(source, RecommendationFeedbackKind.MoreLikeThis, 990_000L),
                        rejected.key to RecommendationFeedback(rejected, RecommendationFeedbackKind.LessLikeThis, 990_000L),
                    ),
                    hiddenEntries = setOf(hidden.key),
                    hiddenCategoryIds = setOf("crime"),
                ),
                nowMillis = 1_000_000L,
            ),
        )

        val shown = snapshot.rows.flatMap { it.items }.map { it.entry }
        assertTrue(shown.isNotEmpty())
        assertTrue(shown.none { it.key == hidden.key || it.key == rejected.key || it.categoryId == "crime" })
    }

    @Test
    fun `same title and year under two ids is shown once`() {
        val candidates = listOf(
            movie(1, "Dune (MULTI) FHD 2021", 9.0),
            movie(2, "Dune 2021", 9.0),
        ) + (3..20).map { movie(it, "Movie $it", 6.0) }

        val snapshot = engine.buildSnapshot("p1", RecommendationBuildContext(candidates = candidates, nowMillis = 1_000_000L))

        val shown = snapshot.rows.flatMap { it.items }.map { it.entry.id }
        assertEquals(1, shown.count { it == 1 || it == 2 })
    }

    @Test
    fun `snapshot has at most two rows and no duplicate item across them`() {
        val source = movie(50, "Source", 8.5, "science", "astronautes espace mission terre")
        val candidates = (1..20).map { id ->
            movie(id, "Movie $id", 7.0 + (id % 3), if (id % 2 == 0) "science" else "thriller", "mission espace mystère terre")
        }
        val snapshot = engine.buildSnapshot(
            "p1",
            RecommendationBuildContext(
                candidates = candidates,
                profile = RecommendationProfileInput(
                    history = listOf(ViewingRecord(source, 7_000_000, 7_100_000, 990_000L)),
                    feedback = mapOf(
                        source.key to RecommendationFeedback(source, RecommendationFeedbackKind.MoreLikeThis, 995_000L),
                    ),
                ),
                nowMillis = 1_000_000L,
            ),
        )

        assertEquals(2, snapshot.rows.size)
        val allKeys = snapshot.rows.flatMap { it.items }.map { it.entry.key }
        assertEquals(allKeys.distinct().size, allKeys.size)
    }

    @Test
    fun `hidden content never reaches similar results`() {
        val source = ContentFeatures.from(movie(99, "Source", 8.0, "science", "mission espace terre"))
        val hidden = movie(1, "Hidden", 9.0, "science", "mission espace terre")
        val visible = movie(2, "Visible", 8.0, "science", "mission espace terre")

        val result = engine.similarTo(
            source = source,
            candidates = listOf(hidden, visible),
            hiddenEntries = setOf(hidden.key),
        )

        assertFalse(result.any { it.entry.key == hidden.key })
        assertTrue(result.any { it.entry.key == visible.key })
    }

    @Test
    fun `same IPTV category alone is not enough for detail similarity`() {
        val sourceEntry = movie(
            99,
            "Valiant One (MULTI) FHD 2025",
            6.7,
            "vod-multi",
            "soldats crash territoire ennemi guerre",
        )
        val source = ContentFeatures(
            entry = sourceEntry,
            plot = sourceEntry.plot,
            genre = "Guerre, Thriller, Action",
            releaseDate = "2025-01-30",
        )
        val unrelated = movie(
            1,
            "The Diary (MULTI) FHD 2025",
            7.0,
            "vod-multi",
            "journal famille amour mariage",
        )

        val result = engine.similarTo(
            source = source,
            candidates = listOf(unrelated),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `similar results stay within the source media type`() {
        val source = ContentFeatures.from(movie(99, "Source", 8.0, "science", "mission espace terre"))
        val similarMovie = movie(1, "Movie", 8.0, "science", "mission espace terre")
        val series = MediaEntry(
            id = 2,
            name = "Series",
            displayName = "Series",
            type = MediaType.Series,
            categoryId = "science",
            iconUrl = null,
            number = 2,
            plot = "mission espace terre",
            rating = 8.0,
        )

        val result = engine.similarTo(
            source = source,
            candidates = listOf(series, similarMovie),
        )

        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.entry.type == MediaType.Movie })
        assertTrue(result.any { it.entry.key == similarMovie.key })
    }

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

    private fun spaceAndHeistMovies() = (1..20).map { id ->
        movie(id, "Space $id", 7.0 + id % 3, "science", SPACE_PLOT)
    } + (21..40).map { id ->
        movie(id, "Heist $id", 7.0 + id % 3, "crime", HEIST_PLOT)
    }

    private fun movie(
        id: Int,
        name: String,
        rating: Double,
        category: String = "movies",
        plot: String? = null,
        addedAt: Long? = 1_000L + id,
    ) = MediaEntry(
        id = id,
        name = name,
        displayName = name,
        type = MediaType.Movie,
        categoryId = category,
        iconUrl = null,
        number = id,
        plot = plot,
        rating = rating,
        addedAtEpochSeconds = addedAt,
    )

    private companion object {
        const val SPACE_PLOT = "astronautes mission espace planète station orbite"
        const val HEIST_PLOT = "braquage banque voleurs cambriolage coffre police"
    }
}

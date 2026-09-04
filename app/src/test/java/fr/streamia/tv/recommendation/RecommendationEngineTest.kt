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
    fun `cold start shows discover instead of pretending to know the user`() {
        val snapshot = engine.buildSnapshot(
            "p1",
            RecommendationBuildContext(
                candidates = listOf(movie(1, "A", 8.9), movie(2, "B", 7.0)),
                nowMillis = 1_000_000L,
            ),
        )

        assertEquals(RecommendationRowKind.Discover, snapshot.rows.first().kind)
        assertEquals("À découvrir", snapshot.rows.first().title)
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
        assertTrue(snapshot.confidence >= 0.3)
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

        assertTrue(snapshot.rows.size <= 2)
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

    private fun movie(
        id: Int,
        name: String,
        rating: Double,
        category: String = "movies",
        plot: String? = null,
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
        addedAtEpochSeconds = 1_000L + id,
    )
}

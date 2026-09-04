package fr.streamia.tv.recommendation

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationPolicyTest {
    @Test
    fun `signal loses half its weight after one half life`() {
        assertEquals(
            0.5,
            decayedSignalWeight(initialWeight = 1.0, ageMillis = 60_000, halfLifeMillis = 60_000),
            0.0001,
        )
    }

    @Test
    fun `hidden entries and categories are excluded before ranking`() {
        val visible = candidate(id = 1, categoryId = "science", score = 0.3)
        val hiddenEntry = candidate(id = 2, categoryId = "science", score = 1.0)
        val hiddenCategory = candidate(id = 3, categoryId = "blocked", score = 0.9)

        val result = filterRecommendationCandidates(
            candidates = listOf(hiddenEntry, hiddenCategory, visible),
            hiddenEntryKeys = setOf(hiddenEntry.entry.key),
            hiddenCategoryIds = setOf("blocked"),
        )

        assertEquals(listOf(visible), result)
    }

    @Test
    fun `ranking is deterministic and uses score then stable media key`() {
        val low = candidate(id = 3, categoryId = "science", score = 0.4)
        val highB = candidate(id = 2, categoryId = "science", score = 0.9)
        val highA = candidate(id = 1, categoryId = "science", score = 0.9)

        val first = rankRecommendations(listOf(low, highB, highA))
        val second = rankRecommendations(listOf(highA, low, highB))

        assertEquals(listOf(highA.entry.key, highB.entry.key, low.entry.key), first.map { it.entry.key })
        assertEquals(first.map { it.entry.key }, second.map { it.entry.key })
    }

    @Test
    fun `secondary slot prefers strong recent event over other valid candidates`() {
        val selected = chooseSecondarySlot(
            listOf(
                SecondarySlotCandidate(SecondarySlotKind.LiveNow, quality = 0.95),
                SecondarySlotCandidate(SecondarySlotKind.NewForYou, quality = 0.99),
                SecondarySlotCandidate(SecondarySlotKind.RecentStrongEvent, quality = 0.70),
            ),
            minimumQuality = 0.60,
        )

        assertEquals(SecondarySlotKind.RecentStrongEvent, selected?.kind)
    }

    @Test
    fun `secondary slot stays empty when every candidate is below quality threshold`() {
        assertNull(
            chooseSecondarySlot(
                listOf(SecondarySlotCandidate(SecondarySlotKind.LiveNow, quality = 0.39)),
                minimumQuality = 0.40,
            ),
        )
    }

    @Test
    fun `confidence rewards strong explicit signals more than weak playback signals`() {
        val now = 1_000_000L
        val weak = profileConfidence(
            listOf(RecommendationSignal("Movie:1", weight = 0.1, occurredAtMillis = now)),
        )
        val strong = profileConfidence(
            listOf(
                RecommendationSignal("Movie:1", weight = 1.0, occurredAtMillis = now, explicit = true),
                RecommendationSignal("Movie:2", weight = 1.0, occurredAtMillis = now, explicit = true),
            ),
        )

        assertTrue(strong > weak)
        assertTrue(strong > 0.2)
    }

    private fun candidate(id: Int, categoryId: String, score: Double) = RecommendationCandidate(
        entry = MediaEntry(
            id = id,
            name = "Media $id",
            displayName = "Media $id",
            type = MediaType.Movie,
            categoryId = categoryId,
            iconUrl = null,
            number = id,
        ),
        score = score,
    )
}

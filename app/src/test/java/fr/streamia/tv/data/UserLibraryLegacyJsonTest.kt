package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class UserLibraryLegacyJsonTest {
    @Test
    fun `backup JSON round-trips every field and keeps orders`() {
        val movie = MediaEntry(id = 7, name = "Dune", type = MediaType.Movie, categoryId = "3", iconUrl = "http://x/p.jpg", number = 7, plot = "Sable", rating = 8.1)
        val live = MediaEntry(id = 1, name = "TF1", categoryId = "1", iconUrl = null, number = 1)
        val snapshot = UserLibrarySnapshot(
            favoriteEntries = linkedSetOf("Movie:9", "Movie:7", "Live:1"),
            favoriteCategories = setOf("Live:1"),
            hiddenEntries = setOf("Live:5"),
            hiddenCategories = setOf("Movie:8"),
            lockedCategories = setOf("Movie:9"),
            watchedEntries = setOf("Movie:7"),
            categoryOrder = mapOf("Live" to listOf("Live:2", "Live:1")),
            movedEntries = mapOf("Live:1" to "2"),
            history = listOf(
                PlaybackHistoryItem(movie, positionMs = 60_000, durationMs = 7_200_000, updatedAt = 2_000),
                PlaybackHistoryItem(live, positionMs = 0, durationMs = 0, updatedAt = 1_000),
            ),
        )

        val restored = parseLegacySnapshot(snapshot.toLegacyJson())

        assertEquals(snapshot, restored)
        assertEquals(listOf("Movie:9", "Movie:7", "Live:1"), restored.favoriteEntries.toList())
    }
}

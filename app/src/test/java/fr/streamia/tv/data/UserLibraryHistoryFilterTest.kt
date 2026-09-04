package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserLibraryHistoryFilterTest {
    private fun item(id: Int, type: MediaType) = PlaybackHistoryItem(
        entry = MediaEntry(
            id = id,
            name = "item-$id",
            displayName = "item-$id",
            type = type,
            categoryId = "1",
            playable = true,
        ),
        positionMs = 1_000L,
        durationMs = 10_000L,
        updatedAt = id.toLong(),
    )

    @Test
    fun `clearing one media type preserves the others`() {
        val history = listOf(
            item(1, MediaType.Live),
            item(2, MediaType.Movie),
            item(3, MediaType.Series),
            item(4, MediaType.Live),
        )

        val kept = historyAfterClearingType(history, MediaType.Live)

        assertEquals(listOf(MediaType.Movie, MediaType.Series), kept.map { it.entry.type })
    }

    @Test
    fun `clearing all history returns an empty list`() {
        assertTrue(historyAfterClearingType(listOf(item(1, MediaType.Movie)), null).isEmpty())
    }
}

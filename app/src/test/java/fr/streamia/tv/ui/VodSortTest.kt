package fr.streamia.tv.ui

import fr.streamia.tv.data.VodSortOrder
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class VodSortTest {
    private fun movie(id: Int, name: String, addedAt: Long?, rating: Double?) = MediaEntry(
        id = id,
        name = name,
        displayName = name,
        type = MediaType.Movie,
        categoryId = "1",
        iconUrl = null,
        number = id,
        rating = rating,
        addedAtEpochSeconds = addedAt,
    )

    private val movies = listOf(
        movie(1, "Zorro", addedAt = 100L, rating = 6.0),
        movie(2, "Été 85", addedAt = 300L, rating = null),
        movie(3, "Avatar", addedAt = null, rating = 8.5),
    )

    @Test
    fun `provider order is untouched`() {
        assertEquals(listOf(1, 2, 3), sortedForVodDisplay(movies, VodSortOrder.Provider).map { it.id })
    }

    @Test
    fun `alphabetical order is locale-aware`() {
        assertEquals(
            listOf("Avatar", "Été 85", "Zorro"),
            sortedForVodDisplay(movies, VodSortOrder.Alphabetical).map { it.displayName },
        )
    }

    @Test
    fun `recently added puts the missing date last, not first`() {
        assertEquals(listOf(2, 1, 3), sortedForVodDisplay(movies, VodSortOrder.RecentlyAdded).map { it.id })
    }

    @Test
    fun `rating puts the unrated entry last, not first`() {
        assertEquals(listOf(3, 1, 2), sortedForVodDisplay(movies, VodSortOrder.Rating).map { it.id })
    }
}

package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMetadataTest {
    @Test
    fun totalCountsRemainAccurateWhenOnlyOneCategoryPageIsMaterialized() {
        val liveCategory = MediaCategory("fr", "France", MediaType.Live)
        val movieCategory = MediaCategory("action", "Action", MediaType.Movie)
        val loadedLive = MediaEntry(
            id = 1,
            name = "TF1",
            type = MediaType.Live,
            categoryId = liveCategory.id,
            iconUrl = null,
            number = 1,
        )
        val catalog = Catalog(
            categories = listOf(liveCategory, movieCategory),
            entries = listOf(loadedLive),
            totalCounts = mapOf(MediaType.Live to 56_725, MediaType.Movie to 177_969, MediaType.Series to 47_645),
            categoryCounts = mapOf(liveCategory.key to 120, movieCategory.key to 8_000),
            loadedCategoryKeys = setOf(liveCategory.key),
        )

        assertEquals(56_725, catalog.count(MediaType.Live))
        assertEquals(177_969, catalog.count(MediaType.Movie))
        assertEquals(120, catalog.countIn(MediaType.Live, liveCategory.id))
        assertEquals(8_000, catalog.countIn(MediaType.Movie, movieCategory.id))
        assertTrue(catalog.isCategoryLoaded(MediaType.Live, liveCategory.id))
        assertFalse(catalog.isCategoryLoaded(MediaType.Movie, movieCategory.id))
        assertEquals(listOf(loadedLive), catalog.entriesIn(MediaType.Live, liveCategory.id))
    }

    @Test
    fun pagedRowsMergeByKeyAndPreserveMetadata() {
        val category = MediaCategory("fr", "France", MediaType.Live)
        val first = entry(1, "TF1", category.id)
        val second = entry(2, "France 2", category.id)
        val catalog = Catalog(
            categories = listOf(category),
            entries = listOf(first),
            totalCounts = mapOf(MediaType.Live to 120),
            categoryCounts = mapOf(category.key to 120),
        )

        val merged = catalog.withMaterializedEntries(
            newEntries = listOf(first.copy(displayName = "TF1 HD"), second),
            loadedType = MediaType.Live,
            loadedCategoryId = category.id,
        )

        assertEquals(2, merged.entries.size)
        assertEquals("TF1 HD", merged.entry(first.key)?.displayName)
        assertEquals(120, merged.count(MediaType.Live))
        assertEquals(120, merged.countIn(MediaType.Live, category.id))
        assertTrue(merged.isCategoryLoaded(MediaType.Live, category.id))
    }

    @Test
    fun fullInMemoryCatalogKeepsLegacyCountBehaviorWithoutMetadata() {
        val movie = MediaEntry(
            id = 2,
            name = "Movie",
            type = MediaType.Movie,
            categoryId = "movies",
            iconUrl = null,
            number = 1,
        )
        val catalog = Catalog(emptyList(), listOf(movie))

        assertEquals(1, catalog.count(MediaType.Movie))
        assertEquals(1, catalog.countIn(MediaType.Movie, "movies"))
        assertTrue(catalog.isCategoryLoaded(MediaType.Movie, "movies"))
    }

    private fun entry(id: Int, name: String, categoryId: String) = MediaEntry(
        id = id,
        name = name,
        type = MediaType.Live,
        categoryId = categoryId,
        iconUrl = null,
        number = id,
    )
}
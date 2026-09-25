package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogVodEvictionTest {
    private val live = MediaCategory("fr", "France", MediaType.Live)
    private val action = MediaCategory("action", "Action", MediaType.Movie)
    private val drama = MediaCategory("drama", "Drame", MediaType.Movie)

    private fun paged(vararg entries: MediaEntry) = Catalog(
        categories = listOf(live, action, drama),
        entries = entries.toList(),
        totalCounts = mapOf(MediaType.Live to 1, MediaType.Movie to 1_000),
        loadedCategoryKeys = setOf(live.key),
    )

    @Test
    fun evictsUnretainedMoviesButKeepsLiveAndRetainedPages() {
        val channel = entry(1, MediaType.Live, live.id)
        val kept = entry(10, MediaType.Movie, action.id)
        val evicted = entry(20, MediaType.Movie, drama.id)
        val catalog = paged(channel)
            .withMaterializedEntries(listOf(kept), MediaType.Movie, action.id)
            .withMaterializedEntries(listOf(evicted), MediaType.Movie, drama.id)

        val trimmed = catalog.retainingVodEntries(setOf(kept.key), setOf(action.key))

        assertEquals(listOf(channel, kept), trimmed.entries)
        assertNull(trimmed.entry(evicted.key))
        assertEquals(listOf(kept), trimmed.entriesIn(MediaType.Movie, action.id))
        assertTrue(trimmed.isCategoryLoaded(MediaType.Movie, action.id))
        assertFalse(trimmed.isCategoryLoaded(MediaType.Movie, drama.id))
        assertTrue(trimmed.isCategoryLoaded(MediaType.Live, live.id))
        assertEquals(1_000, trimmed.count(MediaType.Movie))
    }

    @Test
    fun fullyMaterializedSectionsAreNeverEvicted() {
        val movie = entry(10, MediaType.Movie, action.id)
        val catalog = paged().withFullSectionMaterialized(listOf(movie), MediaType.Movie)

        assertSame(catalog, catalog.retainingVodEntries(emptySet(), emptySet()))
    }

    @Test
    fun inMemoryCatalogueIsLeftUntouched() {
        val catalog = Catalog(listOf(action), listOf(entry(10, MediaType.Movie, action.id)))

        assertSame(catalog, catalog.retainingVodEntries(emptySet(), emptySet()))
    }

    private fun entry(id: Int, type: MediaType, categoryId: String) = MediaEntry(
        id = id,
        name = "Entry $id",
        type = type,
        categoryId = categoryId,
        iconUrl = null,
        number = id,
    )
}

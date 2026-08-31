package fr.streamia.tv.data

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UserLibraryStoreApplyToCatalogTest {

    @Test
    fun `short-circuits and returns the exact same catalog instance when nothing to personalize`() {
        val catalog = Catalog(
            categories = listOf(MediaCategory("1", "Sport", MediaType.Live)),
            entries = listOf(entry(1, "TF1", MediaType.Live, categoryId = "1")),
        )

        val result = applyUserLibraryToCatalog(catalog, UserLibrarySnapshot())

        assertSame("empty moves/order must skip rebuilding and return the identical instance", catalog, result)
    }

    @Test
    fun `moved entries are reassigned to their destination category`() {
        val moved = entry(1, "TF1", MediaType.Live, categoryId = "1")
        val untouched = entry(2, "France 2", MediaType.Live, categoryId = "1")
        val catalog = Catalog(
            categories = listOf(MediaCategory("1", "Sport", MediaType.Live), MediaCategory("2", "News", MediaType.Live)),
            entries = listOf(moved, untouched),
        )
        val snapshot = UserLibrarySnapshot(movedEntries = mapOf(moved.key to "2"))

        val result = applyUserLibraryToCatalog(catalog, snapshot)

        assertEquals("2", result.entry(moved.key)?.categoryId)
        assertEquals("1", result.entry(untouched.key)?.categoryId)
    }

    @Test
    fun `category order applies preferred ordering and appends unmentioned categories at the end`() {
        val sport = MediaCategory("1", "Sport", MediaType.Live)
        val news = MediaCategory("2", "News", MediaType.Live)
        val kids = MediaCategory("3", "Kids", MediaType.Live)
        val catalog = Catalog(categories = listOf(sport, news, kids), entries = emptyList())
        val snapshot = UserLibrarySnapshot(
            categoryOrder = mapOf(MediaType.Live.name to listOf(news.key, sport.key)),
        )

        val result = applyUserLibraryToCatalog(catalog, snapshot)

        assertEquals(listOf(news, sport, kids), result.categoriesFor(MediaType.Live))
    }

    @Test
    fun `category order ignores stale preferred keys no longer present in the catalog`() {
        val sport = MediaCategory("1", "Sport", MediaType.Live)
        val catalog = Catalog(categories = listOf(sport), entries = emptyList())
        val snapshot = UserLibrarySnapshot(
            categoryOrder = mapOf(MediaType.Live.name to listOf("stale-key-from-a-removed-category", sport.key)),
        )

        val result = applyUserLibraryToCatalog(catalog, snapshot)

        assertEquals(listOf(sport), result.categoriesFor(MediaType.Live))
    }

    @Test
    fun `sections without moves or ordering preferences keep their categories and entries untouched`() {
        val liveCategory = MediaCategory("1", "Sport", MediaType.Live)
        val movieCategory = MediaCategory("1", "Action", MediaType.Movie)
        val movieEntry = entry(10, "Movie A", MediaType.Movie, categoryId = "1")
        val catalog = Catalog(
            categories = listOf(liveCategory, movieCategory),
            entries = listOf(movieEntry),
        )
        // Seul le Live a une préférence de tri ; le Movie n'a ni déplacement ni ordre défini.
        val snapshot = UserLibrarySnapshot(
            categoryOrder = mapOf(MediaType.Live.name to listOf(liveCategory.key)),
        )

        val result = applyUserLibraryToCatalog(catalog, snapshot)

        assertEquals(listOf(movieCategory), result.categoriesFor(MediaType.Movie))
        assertEquals(movieEntry, result.entry(movieEntry.key))
    }

    private fun entry(id: Int, name: String, type: MediaType, categoryId: String) = MediaEntry(
        id = id,
        name = name,
        type = type,
        categoryId = categoryId,
        iconUrl = null,
        number = id,
    )
}

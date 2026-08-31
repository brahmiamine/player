package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogNavigationIndexTest {
    @Test
    fun `visual separators are removed from every navigation index`() {
        val regular = entry(1, "France 2")
        val separator = entry(2, "### CHAÎNES FRANÇAISES ###")
        val catalog = Catalog(
            categories = listOf(MediaCategory("fr", "France", MediaType.Live)),
            entries = listOf(separator, regular),
        )

        assertEquals(listOf(regular), catalog.entriesFor(MediaType.Live))
        assertEquals(listOf(regular), catalog.entriesIn(MediaType.Live, "fr"))
        assertEquals(1, catalog.count(MediaType.Live))
        assertNull(catalog.entry(separator.key))
    }

    private fun entry(id: Int, name: String) = MediaEntry(
        id = id,
        name = name,
        type = MediaType.Live,
        categoryId = "fr",
        iconUrl = null,
        number = id,
    )
}

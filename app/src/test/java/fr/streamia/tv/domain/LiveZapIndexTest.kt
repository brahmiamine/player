package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveZapIndexTest {
    private fun live(id: Int, category: String) = MediaEntry(id = id, name = "C$id", type = MediaType.Live, categoryId = category, iconUrl = null, number = id)

    @Test
    fun `zaps inside category, wraps, skips hidden and falls back to all when alone`() {
        val entries = listOf(live(1, "a"), live(2, "a"), live(3, "a"), live(4, "b"), live(5, "c"))
        val catalog = Catalog(emptyList(), entries)
        val index = LiveZapIndex(catalog, hiddenEntries = setOf(entries[1].key), excludedCategoryIds = setOf("c"))

        assertEquals(3, index.adjacent(entries[0], 1)?.id)
        assertEquals(1, index.adjacent(entries[2], 1)?.id)
        assertEquals(3, index.adjacent(entries[0], -1)?.id)
        // Seule chaîne de "b" : repli sur toutes les chaînes visibles (1, 3, 4).
        assertEquals(1, index.adjacent(entries[3], 1)?.id)
    }
}

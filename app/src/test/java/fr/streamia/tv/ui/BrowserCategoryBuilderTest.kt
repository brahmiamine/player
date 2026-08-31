package fr.streamia.tv.ui

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserCategoryBuilderTest {
    @Test
    fun `all remains first for every media section`() {
        MediaType.entries.forEach { type ->
            val provider = listOf(MediaCategory("1", "Sport", type))
            val result = buildBrowserCategories(type, provider, emptySet(), true, true)

            assertEquals(Catalog.ALL_CATEGORY_ID, result.first().id)
            assertEquals(listOf(Catalog.ALL_CATEGORY_ID, "__favorites__", "__history__", "1"), result.map { it.id })
        }
    }
}

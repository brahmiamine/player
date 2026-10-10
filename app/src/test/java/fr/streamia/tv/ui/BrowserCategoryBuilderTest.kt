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

    @Test
    fun `uhd category comes right after all in live`() {
        val provider = listOf(MediaCategory("1", "Sport", MediaType.Live))
        val result = buildBrowserCategories(MediaType.Live, provider, emptySet(), true, true, hasUhdEntries = true)

        assertEquals(listOf(Catalog.ALL_CATEGORY_ID, UHD_CATEGORY_ID, "__favorites__", "__history__", "1"), result.map { it.id })
        assertEquals("UHD 4K", result[1].name)
    }

    @Test
    fun `uhd category only exists in live once a channel was seen in 4k`() {
        val live = buildBrowserCategories(MediaType.Live, emptyList(), emptySet(), false, false, hasUhdEntries = false)
        assertEquals(listOf(Catalog.ALL_CATEGORY_ID), live.map { it.id })

        listOf(MediaType.Movie, MediaType.Series).forEach { type ->
            val result = buildBrowserCategories(type, emptyList(), emptySet(), false, false, hasUhdEntries = true)
            assertEquals(listOf(Catalog.ALL_CATEGORY_ID), result.map { it.id })
        }
    }
}

package fr.streamia.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentReturnContextTest {
    @Test
    fun `content opened from home returns to home and preserves row and item keys`() {
        val context = ContentReturnContext.home(rowKey = "recommendation:BecauseYouWatched", itemKey = "movie:42")

        assertEquals(ContentReturnOrigin.Home, context.origin)
        assertEquals("recommendation:BecauseYouWatched", context.homeRowKey)
        assertEquals("movie:42", context.itemKey)
        assertEquals(StreamiaScreen.Home, context.destinationScreen())
    }

    @Test
    fun `content opened from search returns to search with selected item`() {
        val context = ContentReturnContext.search(itemKey = "series:7")

        assertEquals(ContentReturnOrigin.Search, context.origin)
        assertEquals("series:7", context.itemKey)
        assertEquals(StreamiaScreen.Search, context.destinationScreen())
    }

    @Test
    fun `channel opened from matches returns to matches and preserves match key`() {
        val context = ContentReturnContext.liveMatches(matchKey = "psg|om|1234", itemKey = "live:9")

        assertEquals(ContentReturnOrigin.LiveMatches, context.origin)
        assertEquals("psg|om|1234", context.liveMatchKey)
        assertEquals(StreamiaScreen.LiveMatches, context.destinationScreen())
    }
}

package fr.streamia.tv.ui

import fr.streamia.tv.data.LiveChannelSortOrder
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphabeticalSortTest {
    private fun channel(id: Int, name: String) =
        MediaEntry(id = id, name = name, type = MediaType.Live, categoryId = "1", iconUrl = null, number = id)

    @Test
    fun ignoresAccentsCaseAndLigaturesLikeFrenchOrder() {
        val sorted = sortedAlphabetically(
            listOf(channel(1, "zèbre"), channel(2, "Œuvre"), channel(3, "école"), channel(4, "Arte"), channel(5, "ecran"), channel(6, "Oeil")),
        )
        assertEquals(listOf("Arte", "école", "ecran", "Oeil", "Œuvre", "zèbre"), sorted.map { it.displayName })
    }

    @Test
    fun sortsTensOfThousandsOfChannelsQuickly() {
        val channels = (1..30_000).map { channel(it, "Chaîne ${(it * 7919) % 30_000} HD") }
        val start = System.nanoTime()
        val sorted = sortedForLiveDisplay(channels, LiveChannelSortOrder.Alphabetical)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(channels.size, sorted.size)
        // Ancien tri Collator : plusieurs secondes sur le thread principal pour une telle liste.
        assertTrue("tri trop lent : $elapsedMs ms", elapsedMs < 3_000)
    }

    @Test
    fun memoReturnsTheSameResultForTheSameSourceAndFilters() {
        val source = listOf(channel(1, "b"), channel(2, "a"))
        val hidden = emptySet<String>()
        val result = sortedAlphabetically(source)
        BrowserSortMemo.put(source, hidden, setOf("x"), LiveChannelSortOrder.Alphabetical, result)
        assertSame(result, BrowserSortMemo.get(source, hidden, setOf("x"), LiveChannelSortOrder.Alphabetical))
        assertEquals(null, BrowserSortMemo.get(source.toList(), hidden, setOf("x"), LiveChannelSortOrder.Alphabetical))
        assertEquals(null, BrowserSortMemo.get(source, hidden, setOf("y"), LiveChannelSortOrder.Alphabetical))
    }
}

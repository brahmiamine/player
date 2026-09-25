package fr.streamia.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VodPageRetentionTest {
    private fun keys(prefix: String, count: Int) = (1..count).map { "$prefix:$it" }

    @Test
    fun underBudgetEverythingIsKept() {
        val pages = linkedMapOf("Movie:a|Provider" to keys("a", 3), "Movie:b|Provider" to keys("b", 3))

        assertSame(pages, retainedVodPages(pages, protectedCategoryKey = null, maxEntries = 10))
    }

    @Test
    fun oldestPagesAreDroppedFirst() {
        val pages = linkedMapOf(
            "Movie:a|Provider" to keys("a", 4),
            "Movie:b|Provider" to keys("b", 4),
            "Movie:c|Provider" to keys("c", 4),
        )

        val retained = retainedVodPages(pages, protectedCategoryKey = null, maxEntries = 8)

        assertEquals(listOf("Movie:b|Provider", "Movie:c|Provider"), retained.keys.toList())
    }

    @Test
    fun displayedCategoryAndNewestPageSurviveEvenOverBudget() {
        val pages = linkedMapOf(
            "Movie:shown|Provider" to keys("s", 6),
            "Movie:shown|Title" to keys("t", 6),
            "Movie:b|Provider" to keys("b", 6),
            "Movie:newest|Provider" to keys("n", 12),
        )

        val retained = retainedVodPages(pages, protectedCategoryKey = "Movie:shown", maxEntries = 10)

        assertEquals(
            listOf("Movie:shown|Provider", "Movie:shown|Title", "Movie:newest|Provider"),
            retained.keys.toList(),
        )
    }
}

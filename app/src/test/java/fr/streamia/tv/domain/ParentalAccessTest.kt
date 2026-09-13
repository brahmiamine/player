package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentalAccessTest {
    private val adult = MediaCategory(id = "99", name = "Adult", type = MediaType.Live)
    private val news = MediaCategory(id = "1", name = "News", type = MediaType.Live)
    private val adultChannel = MediaEntry(
        id = 42,
        name = "Adult 1",
        type = MediaType.Live,
        categoryId = adult.id,
        iconUrl = null,
        number = 42,
    )
    private val newsChannel = MediaEntry(
        id = 7,
        name = "News 1",
        type = MediaType.Live,
        categoryId = news.id,
        iconUrl = null,
        number = 7,
    )

    @Test
    fun `locked live channel is blocked until the session is unlocked`() {
        assertTrue(
            isParentalBlocked(
                entry = adultChannel,
                lockedCategoryKeys = setOf(adult.key),
                parentalControlEnabled = true,
                parentalUnlocked = false,
            ),
        )
        assertFalse(
            isParentalBlocked(
                entry = adultChannel,
                lockedCategoryKeys = setOf(adult.key),
                parentalControlEnabled = true,
                parentalUnlocked = true,
            ),
        )
        assertFalse(
            isParentalBlocked(
                entry = newsChannel,
                lockedCategoryKeys = setOf(adult.key),
                parentalControlEnabled = true,
                parentalUnlocked = false,
            ),
        )
    }

    @Test
    fun `disabled parental control never blocks`() {
        assertFalse(
            isParentalBlocked(
                entry = adultChannel,
                lockedCategoryKeys = setOf(adult.key),
                parentalControlEnabled = false,
                parentalUnlocked = false,
            ),
        )
    }

    @Test
    fun `locked category ids are omitted from aggregated live lists`() {
        assertEquals(
            setOf("99"),
            parentalLockedCategoryIds(
                categories = listOf(news, adult),
                lockedCategoryKeys = setOf(adult.key),
                parentalControlEnabled = true,
                parentalUnlocked = false,
                type = MediaType.Live,
            ),
        )
        assertEquals(
            emptySet<String>(),
            parentalLockedCategoryIds(
                categories = listOf(news, adult),
                lockedCategoryKeys = setOf(adult.key),
                parentalControlEnabled = true,
                parentalUnlocked = true,
                type = MediaType.Live,
            ),
        )
    }

    @Test
    fun `hidden and locked categories are both excluded while locked`() {
        val hidden = MediaCategory(id = "2", name = "Hidden", type = MediaType.Live)
        assertEquals(
            setOf("99", "2"),
            parentalExcludedCategoryIds(
                categories = listOf(news, adult, hidden),
                lockedCategoryKeys = setOf(adult.key),
                hiddenCategoryKeys = setOf(hidden.key),
                parentalControlEnabled = true,
                parentalUnlocked = false,
                type = MediaType.Live,
            ),
        )
        assertEquals(
            setOf("2"),
            parentalExcludedCategoryIds(
                categories = listOf(news, adult, hidden),
                lockedCategoryKeys = setOf(adult.key),
                hiddenCategoryKeys = setOf(hidden.key),
                parentalControlEnabled = true,
                parentalUnlocked = true,
                type = MediaType.Live,
            ),
        )
    }
}

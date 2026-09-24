package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendingMatchTest {
    private fun movie(name: String) = MediaEntry(1, name, type = MediaType.Movie, categoryId = "1", iconUrl = null, number = 1)

    @Test
    fun matchesFrenchOrOriginalTitleWithinOneYear() {
        val title = TrendingTitle(MediaType.Movie, "Avatar : De feu et de cendres", "Avatar: Fire and Ash", 2025)
        assertTrue(matchesTrending(movie("FR - Avatar : De feu et de cendres (2025) 4K"), title))
        assertTrue(matchesTrending(movie("Avatar: Fire and Ash"), title))
        assertFalse(matchesTrending(movie("Avatar (2009)"), title))
        assertFalse(matchesTrending(movie("Avatar : De feu et de cendres (2019)"), title))
    }
}

package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesNextEpisodeTest {
    private fun episode(id: Int, season: Int, number: Int) = SeriesEpisode(
        id = id,
        season = season,
        number = number,
        title = "S${season}E$number",
        extension = "mp4",
    )

    private val series = MediaEntry(
        id = 1,
        name = "Show",
        displayName = "Show",
        type = MediaType.Series,
        categoryId = "1",
        iconUrl = null,
        number = 1,
        playable = false,
    )

    private val details = SeriesDetails(
        series = series,
        episodes = listOf(
            episode(101, season = 1, number = 1),
            episode(102, season = 1, number = 2),
            episode(201, season = 2, number = 1),
        ),
    )

    @Test
    fun `next episode within the same season`() {
        assertEquals(102, details.nextEpisode(101)?.id)
    }

    @Test
    fun `crossing a season boundary picks the next season's first episode`() {
        assertEquals(201, details.nextEpisode(102)?.id)
    }

    @Test
    fun `last episode of the series has no next episode`() {
        assertNull(details.nextEpisode(201))
    }

    @Test
    fun `unknown episode id has no next episode`() {
        assertNull(details.nextEpisode(999))
    }
}

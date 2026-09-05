package fr.streamia.tv.liveonsat

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelMatcherTest {
    private val matcher = ChannelMatcher()

    @Test
    fun `matches a broadcaster name against the closest user channel`() {
        val userChannels = listOf(
            channel(1, "beIN SPORTS 1 HD"),
            channel(2, "beIN SPORTS 2 HD"),
            channel(3, "Sky Sports Football"),
        )
        val index = matcher.buildIndex(userChannels)

        assertEquals("beIN SPORTS 1 HD", matcher.match(index, "beIN Sports 1 HD")?.displayName)
        assertEquals("beIN SPORTS 2 HD", matcher.match(index, "beIN Sports 2 HD")?.displayName)
    }

    @Test
    fun `plural mismatch between provider and broadcaster names does not block a match`() {
        val userChannels = listOf(channel(1, "BEIN SPORT 1"))
        val index = matcher.buildIndex(userChannels)

        assertEquals("BEIN SPORT 1", matcher.match(index, "beIN Sports 1 HD")?.displayName)
    }

    @Test
    fun `unrelated channel names are never returned`() {
        val userChannels = listOf(channel(1, "France 2"), channel(2, "TF1"))
        val index = matcher.buildIndex(userChannels)

        assertNull(matcher.match(index, "SuperSport ESPN 2 HD"))
    }

    @Test
    fun `resolve keeps every liveonsat channel while only mapping the ones that matched`() {
        val userChannels = listOf(channel(1, "DAZN 1 HD"))
        val match = LiveOnSatMatch(
            competition = "Test League",
            participantA = "A",
            participantB = "B",
            startEpochSeconds = 0L,
            channels = listOf(
                LiveOnSatChannel("DAZN 1 HD", free = false),
                LiveOnSatChannel("Apple TV ($/geo/R)", free = false),
            ),
        )

        val resolved = matcher.resolve(listOf(match), userChannels)

        assertEquals(1, resolved.size)
        assertEquals(2, resolved.first().match.channels.size)
        assertEquals(1, resolved.first().matchedChannels.size)
        assertEquals("DAZN 1 HD", resolved.first().matchedChannels["DAZN 1 HD"]?.displayName)
    }

    private fun channel(id: Int, name: String) = MediaEntry(
        id = id,
        name = name,
        displayName = name,
        type = MediaType.Live,
        categoryId = "sport",
        iconUrl = null,
        number = id,
    )
}

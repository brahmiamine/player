package fr.streamia.tv.liveonsat

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelMatcherTest {
    private val matcher = ChannelMatcher()

    @Test
    fun `matches a broadcaster name against the closest user channel`() {
        val index = matcher.buildIndex(
            catalogOf(
                channel(1, "beIN SPORTS 1 HD"),
                channel(2, "beIN SPORTS 2 HD"),
                channel(3, "Sky Sports Football"),
            ),
        )

        assertEquals("beIN SPORTS 1 HD", matcher.match(index, "beIN Sports 1 HD")?.displayName)
        assertEquals("beIN SPORTS 2 HD", matcher.match(index, "beIN Sports 2 HD")?.displayName)
    }

    @Test
    fun `plural mismatch between provider and broadcaster names does not block a match`() {
        val index = matcher.buildIndex(catalogOf(channel(1, "BEIN SPORT 1")))

        assertEquals("BEIN SPORT 1", matcher.match(index, "beIN Sports 1 HD")?.displayName)
    }

    @Test
    fun `unrelated channel names are never returned`() {
        val index = matcher.buildIndex(catalogOf(channel(1, "France 2"), channel(2, "TF1")))

        assertNull(matcher.match(index, "SuperSport ESPN 2 HD"))
    }

    @Test
    fun `every resolution of the same channel is returned, not just one`() {
        val index = matcher.buildIndex(
            catalogOf(
                channel(1, "beIN SPORTS 1 HD"),
                channel(2, "beIN SPORTS 1 FHD"),
                channel(3, "beIN SPORTS 1 4K"),
                channel(4, "beIN SPORTS 2 HD"),
            ),
        )

        val matched = matcher.matchAll(index, "beIN Sports 1 HD")

        assertEquals(listOf(1, 2, 3), matched.map { it.id })
    }

    @Test
    fun `generic beIN Connect MENA broadcaster resolves to a single leading Arabic beIN channel`() {
        val category = MediaCategory("ar-bein", "AR | beIN SPORTS", MediaType.Live)
        val index = matcher.buildIndex(
            Catalog(
                categories = listOf(category),
                entries = listOf(
                    channel(2, "AR | beIN SPORTS 2 HD", category.id),
                    channel(1, "AR | beIN SPORTS 1 HD", category.id),
                    channel(3, "AR | beIN SPORTS 3 HD", category.id),
                    channel(4, "FR | beIN Sports 1", "fr-bein"),
                ),
            ),
        )

        val matched = matcher.matchAll(index, "beIN Connect MENA HD")

        assertEquals(listOf(1), matched.map { it.id })
    }

    @Test
    fun `a numbered broadcaster is never widened to the whole bouquet`() {
        val category = MediaCategory("ar-bein", "AR | beIN SPORTS", MediaType.Live)
        val index = matcher.buildIndex(
            Catalog(
                categories = listOf(category),
                entries = listOf(
                    channel(1, "AR | beIN SPORTS 1 HD", category.id),
                    channel(2, "AR | beIN SPORTS 2 HD", category.id),
                ),
            ),
        )

        val matched = matcher.matchAll(index, "beIN Sports 1 MENA HD")

        assertEquals(listOf(1), matched.map { it.id })
    }

    @Test
    fun `a numbered MENA broadcaster only matches the Arabic bein channel, never other regions`() {
        val arCategory = MediaCategory("ar-bein", "AR | beIN SPORTS", MediaType.Live)
        val index = matcher.buildIndex(
            Catalog(
                categories = listOf(arCategory),
                entries = listOf(
                    channel(1, "AR | beIN SPORTS 3 HD", arCategory.id),
                    channel(2, "FR: BEIN SPORTS 3 HEVC", "fr-bein"),
                    channel(3, "FR: BEIN SPORTS 3 4K", "fr-bein"),
                    channel(4, "AU: BEIN SPORTS 3 HD", "au-bein"),
                    channel(5, "V+: BEIN SPORTS 3", "vplus-bein"),
                ),
            ),
        )

        val matched = matcher.matchAll(index, "beIN Sports MENA 3 HD")

        assertEquals(listOf(1), matched.map { it.id })
    }

    @Test
    fun `same number in different regional categories is never merged into one result`() {
        val index = matcher.buildIndex(
            Catalog(
                categories = emptyList(),
                entries = listOf(
                    channel(1, "FR: Eurosport 1", "fr"),
                    channel(2, "UK: Eurosport 1", "uk"),
                ),
            ),
        )

        val matched = matcher.matchAll(index, "Eurosport 1 HD")

        assertEquals(1, matched.size)
    }

    @Test
    fun `resolve keeps every liveonsat channel while only mapping the ones that matched`() {
        val catalog = catalogOf(channel(1, "DAZN 1 HD"))
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

        val resolved = matcher.resolve(listOf(match), catalog)

        assertEquals(1, resolved.size)
        assertEquals(2, resolved.first().match.channels.size)
        assertEquals(1, resolved.first().matchedChannels.size)
        assertTrue(resolved.first().matchedChannels.getValue("DAZN 1 HD").any { it.displayName == "DAZN 1 HD" })
    }

    private fun catalogOf(vararg entries: MediaEntry) = Catalog(categories = emptyList(), entries = entries.toList())

    private fun channel(id: Int, name: String, categoryId: String = "sport") = MediaEntry(
        id = id,
        name = name,
        displayName = name,
        type = MediaType.Live,
        categoryId = categoryId,
        iconUrl = null,
        number = id,
    )
}

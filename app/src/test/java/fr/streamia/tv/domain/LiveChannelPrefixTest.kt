package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChannelPrefixTest {
    @Test
    fun matchesPrefixIgnoringCaseAndPlaylistDecoration() {
        assertTrue(LiveChannelPrefix.FR.matches("FR | Généralistes"))
        assertTrue(LiveChannelPrefix.FR.matches("fr: TF1"))
        assertTrue(LiveChannelPrefix.UK.matches("|UK| Sky Sports"))
        assertTrue(LiveChannelPrefix.AR.matches("[AR] beIN SPORTS 1"))
        assertFalse(LiveChannelPrefix.FR.matches("Sport FR"))
        assertFalse(LiveChannelPrefix.UK.matches("BBC One"))
    }

    @Test
    fun stripsOnlyAStandalonePrefix() {
        assertEquals("TF1 HD", LiveChannelPrefix.FR.strip("FR | TF1 HD"))
        assertEquals("Arte", LiveChannelPrefix.FR.strip("|FR| Arte"))
        assertEquals("beIN SPORTS 1", LiveChannelPrefix.AR.strip("AR: beIN SPORTS 1"))
        assertEquals("France 2", LiveChannelPrefix.FR.strip("France 2"))
        assertEquals("Arena Sport", LiveChannelPrefix.AR.strip("Arena Sport"))
    }

    @Test
    fun keepsLiveChannelsWhoseCategoryOrNameHasThePrefix() {
        val uk = MediaCategory("1", "UK | Entertainment", MediaType.Live)
        val other = MediaCategory("2", "Sports", MediaType.Live)
        val vod = MediaCategory("3", "UK | Movies", MediaType.Movie)
        val catalog = Catalog(
            categories = listOf(uk, other, vod),
            entries = listOf(
                entry(1, "BBC One", uk.id, MediaType.Live),
                entry(2, "UK: Sky Sports 1", other.id, MediaType.Live),
                entry(3, "Eurosport 1", other.id, MediaType.Live),
                entry(4, "Some film", vod.id, MediaType.Movie),
            ),
        )

        assertEquals(listOf(1, 2), LiveChannelPrefix.UK.channels(catalog).map { it.id })
    }

    private fun entry(id: Int, name: String, categoryId: String, type: MediaType) = MediaEntry(
        id = id,
        name = name,
        type = type,
        categoryId = categoryId,
        iconUrl = null,
        number = id,
    )
}

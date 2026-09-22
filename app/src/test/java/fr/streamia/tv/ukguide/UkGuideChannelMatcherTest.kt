package fr.streamia.tv.ukguide

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class UkGuideChannelMatcherTest {
    private val matcher = UkGuideChannelMatcher()

    @Test
    fun selectsOnlyLiveCategoriesStartingWithUk() {
        val uk = category("1", "UK | Entertainment")
        val other = category("2", "FR | Généralistes")
        val catalog = Catalog(
            categories = listOf(uk, other),
            entries = listOf(
                channel(1, "UK | BBC One HD", uk.id),
                channel(2, "BBC One HD", other.id),
            ),
        )

        val result = matcher.ukLiveChannels(catalog)

        assertEquals(listOf(1), result.map { it.id })
    }

    @Test
    fun picksHighestQualityVariantInRequestedOrder() {
        val channels = listOf(
            channel(1, "UK | BBC One SD", "uk"),
            channel(2, "UK | BBC One HD", "uk"),
            channel(3, "UK | BBC One FHD", "uk"),
            channel(4, "UK | BBC One UHD", "uk"),
            channel(5, "UK | BBC One 4K", "uk"),
        )

        val result = matcher.resolve(
            programmes = listOf(programme("BBC One", "DIY SOS")),
            channels = channels,
        )

        assertEquals(5, result.single().channel.id)
    }

    @Test
    fun acceptsUkPrefixWithoutSeparator() {
        val result = matcher.resolve(
            programmes = listOf(programme("BBC Two", "Newsnight")),
            channels = listOf(
                channel(1, "UK BBC Two HD", "uk"),
                channel(2, "UK BBC Two 4K", "uk"),
            ),
        )

        assertEquals(2, result.single().channel.id)
    }

    @Test
    fun doesNotMatchUnrelatedChannels() {
        val result = matcher.resolve(
            programmes = listOf(programme("ITV1", "Emmerdale")),
            channels = listOf(channel(1, "UK | Channel 4 HD", "uk")),
        )

        assertEquals(0, result.size)
    }

    private fun programme(channelName: String, title: String) = UkProgrammeItem(
        channelName = channelName,
        startTime = "20:00",
        endTime = "20:30",
        title = title,
    )

    private fun category(id: String, name: String) = MediaCategory(id, name, MediaType.Live)

    private fun channel(id: Int, name: String, categoryId: String) = MediaEntry(
        id = id,
        name = name,
        displayName = name,
        type = MediaType.Live,
        categoryId = categoryId,
        iconUrl = null,
        number = id,
    )
}

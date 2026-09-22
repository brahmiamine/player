package fr.streamia.tv.beinsports

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class BeinSportsChannelMatcherTest {
    private val matcher = BeinSportsChannelMatcher()

    @Test
    fun matchesBeinVariantsAndPrefersHighestQuality() {
        val category = MediaCategory("bein", "AR | beIN SPORTS", MediaType.Live)
        val catalog = Catalog(
            categories = listOf(category),
            entries = listOf(
                channel(1, "AR | beIN SPORTS 1 HD", category.id),
                channel(2, "AR | beIN SPORTS 1 FHD", category.id),
                channel(3, "AR | beIN SPORTS 1 4K", category.id),
                channel(4, "AR | beIN SPORTS ENGLISH 1 HD", category.id),
                channel(5, "AR | beIN SPORTS XTRA 2 FHD", category.id),
            ),
        )

        val result = matcher.resolve(
            programmes = listOf(
                programme("beIN SPORTS 1"),
                programme("beIN SPORTS EN 1"),
                programme("beIN SPORTS XTRA 2"),
            ),
            catalog = catalog,
        )

        assertEquals(listOf(3, 4, 5), result.map { it.channel.id })
    }

    @Test
    fun doesNotMatchDifferentNumberedChannel() {
        val category = MediaCategory("bein", "AR | Sports", MediaType.Live)
        val catalog = Catalog(
            categories = listOf(category),
            entries = listOf(
                channel(1, "beIN SPORTS 1 HD", category.id),
                channel(2, "beIN SPORTS 2 HD", category.id),
            ),
        )

        val result = matcher.resolve(
            programmes = listOf(programme("beIN SPORTS 2")),
            catalog = catalog,
        )

        assertEquals(2, result.single().channel.id)
    }

    @Test
    fun onlyUsesChannelsWhoseCategoryOrNameStartsWithAr() {
        val arabic = MediaCategory("ar", "AR | Sports", MediaType.Live)
        val other = MediaCategory("fr", "FR | Sport", MediaType.Live)
        val catalog = Catalog(
            categories = listOf(arabic, other),
            entries = listOf(
                channel(1, "FR | beIN SPORTS 1 4K", other.id),
                channel(2, "AR: beIN SPORTS 2 HD", other.id),
                channel(3, "beIN SPORTS 3 HD", arabic.id),
            ),
        )

        val result = matcher.resolve(
            programmes = listOf(
                programme("beIN SPORTS 1"),
                programme("beIN SPORTS 2"),
                programme("beIN SPORTS 3"),
            ),
            catalog = catalog,
        )

        assertEquals(listOf(2, 3), result.map { it.channel.id })
    }

    @Test
    fun keepsAfcNbaAndHdrVariantsApartFromMainChannels() {
        val category = MediaCategory("bein", "AR | beIN", MediaType.Live)
        val catalog = Catalog(
            categories = listOf(category),
            entries = listOf(
                channel(1, "AR | beIN SPORTS 1 FHD", category.id),
                channel(2, "AR | beIN SPORTS 1 AFC HD", category.id),
                channel(3, "AR | beIN SPORTS NBA", category.id),
                channel(4, "AR | beIN 4K", category.id),
                channel(5, "AR | beIN SPORTS 4K HDR", category.id),
            ),
        )

        val result = matcher.resolve(
            programmes = listOf(
                programme("beIN SPORTS 1"),
                programme("beIN SPORTS 1 AFC"),
                programme("beIN SPORTS NBA"),
                programme("beIN 4K"),
                programme("beIN SPORTS 4K HDR"),
            ),
            catalog = catalog,
        )

        assertEquals(listOf(1, 2, 3, 4, 5), result.map { it.channel.id })
    }

    private fun programme(channelName: String) = BeinProgrammeItem(
        channelName = channelName,
        category = "Football",
        title = "Programme",
        startEpochMillis = 1_790_000_000_000L,
        endEpochMillis = 1_790_007_200_000L,
    )

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

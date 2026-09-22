package fr.streamia.tv.tvprogramme

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvProgrammeChannelMatcherTest {
    private val matcher = TvProgrammeChannelMatcher()

    @Test
    fun selectsOnlyLiveCategoriesStartingWithFr() {
        val french = category("1", "FR | Généralistes")
        val other = category("2", "UK | Entertainment")
        val catalog = Catalog(
            categories = listOf(french, other),
            entries = listOf(
                channel(1, "FR | TF1 HD", french.id),
                channel(2, "TF1 HD", other.id),
            ),
        )

        val result = matcher.frenchLiveChannels(catalog)

        assertEquals(listOf(1), result.map { it.id })
    }

    @Test
    fun picksHighestQualityVariantInRequestedOrder() {
        val channels = listOf(
            channel(1, "FR | TF1 SD", "fr"),
            channel(2, "FR | TF1 HD", "fr"),
            channel(3, "FR | TF1 FHD", "fr"),
            channel(4, "FR | TF1 UHD", "fr"),
            channel(5, "FR | TF1 4K", "fr"),
        )

        val result = matcher.resolve(
            programmes = listOf(TvProgrammeItem("TF1", "21:10", "Koh-Lanta")),
            channels = channels,
        )

        assertEquals(5, result.single().channel.id)
    }

    @Test
    fun matchesCommonNamingVariantsButDoesNotConfuseTf1WithTf1SeriesFilms() {
        val channels = listOf(
            channel(1, "FR | Canal Plus UHD", "fr"),
            channel(2, "FR | TF1 Séries Films FHD", "fr"),
            channel(3, "FR | TF1 HD", "fr"),
        )
        val result = matcher.resolve(
            programmes = listOf(
                TvProgrammeItem("Canal+", "21:10", "Film"),
                TvProgrammeItem("TF1", "21:10", "Emission"),
            ),
            channels = channels,
        )

        assertEquals(listOf(1, 3), result.map { it.channel.id })
        assertTrue(result.none { it.channel.id == 2 })
    }

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

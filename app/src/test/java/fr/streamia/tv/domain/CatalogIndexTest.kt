package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogIndexTest {
    @Test
    fun searchUsesNormalizedIndexAndTypeFilter() {
        val live = entry(1, "France Télévisions", MediaType.Live)
        val movie = entry(2, "FRANCE 2030", MediaType.Movie)
        val catalog = Catalog(emptyList(), listOf(live, movie))

        assertEquals(listOf(live, movie), catalog.search("france"))
        assertEquals(listOf(movie), catalog.search("FRANCE", MediaType.Movie))
    }

    @Test
    fun epgResolvesCaseInsensitiveAliasesWithoutScanning() {
        val program = EpgProgram("Journal", null, null, null)
        val guide = EpgGuide(mapOf("TF1.fr" to EpgChannel("TF1.fr", "TF1 HD", programs = listOf(program))))

        assertEquals(listOf(program), guide.forEntry(entry(1, "tf1 hd", MediaType.Live)))
    }

    @Test
    fun epgResolvesProviderDecoratedLiveNameAgainstXmlTvAlias() {
        val program = EpgProgram("Journal de 20h", null, 100, 200)
        val guide = EpgGuide(
            mapOf(
                "TF1.fr" to EpgChannel(
                    channelId = "TF1.fr",
                    displayName = "TF1 HD",
                    programs = listOf(program),
                ),
            ),
        )
        val decorated = entry(14592, "FR: TF1 4K", MediaType.Live)

        assertEquals(listOf(program), guide.forEntry(decorated))
    }

    @Test
    fun epgAliasCleanupDoesNotCollapseFranceNamedChannels() {
        val france2 = EpgProgram("Télématin", null, 100, 200)
        val france3 = EpgProgram("Régions", null, 100, 200)
        val guide = EpgGuide(
            mapOf(
                "france2" to EpgChannel("france2", "France 2 HD", programs = listOf(france2)),
                "france3" to EpgChannel("france3", "France 3 HD", programs = listOf(france3)),
            ),
        )

        assertEquals(listOf(france2), guide.forEntry(entry(2, "FR: France 2 4K", MediaType.Live)))
        assertEquals(listOf(france3), guide.forEntry(entry(3, "FR: France 3 4K", MediaType.Live)))
    }

    @Test
    fun providerVisualSeparatorsAreDetectedButNormalChannelsRemain() {
        assertEquals(true, entry(1, "### MUSIC LOW INTERNET ###", MediaType.Live).isVisualSeparator())
        assertEquals(false, entry(2, "AR: MAZZIKA LQ", MediaType.Live).isVisualSeparator())
    }

    private fun entry(id: Int, name: String, type: MediaType) = MediaEntry(
        id = id,
        name = name,
        type = type,
        categoryId = "1",
        iconUrl = null,
        number = id,
    )
}

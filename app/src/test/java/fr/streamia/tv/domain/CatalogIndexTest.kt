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

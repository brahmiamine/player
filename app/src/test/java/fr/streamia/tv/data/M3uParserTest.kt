package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class M3uParserTest {
    @Test
    fun `parses live movie and series entries without loading credentials into entries`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="483974" tvg-name="France Info" tvg-logo="http://logos.test/info.png" group-title="FR | Infos",France Info
            provider.test:443/live/my%20user/p%40ss/483974.ts
            #EXTINF:-1 tvg-id="2133594" tvg-name="Marry Me, My Evil Lord" tvg-logo="https://logos.test/movie.jpg" group-title="TOP MOVIES 4K",Marry Me, My Evil Lord
            provider.test:443/movie/my%20user/p%40ss/2133594.mkv
            #EXTINF:-1 tvg-id="49774" tvg-name="مسلسل عربي" tvg-logo="https://logos.test/series.jpg" group-title="AR | مسلسلات",مسلسل عربي
            provider.test:443/series/my%20user/p%40ss/49774.mp4
        """.trimIndent()

        val result = M3uParser().parse(StringReader(playlist))

        assertEquals("https://provider.test:443", result.credentials.serverUrl)
        assertEquals("my user", result.credentials.username)
        assertEquals("p@ss", result.credentials.password)
        assertEquals(3, result.parsedEntries)
        assertEquals(0, result.skippedEntries)
        assertEquals(listOf(MediaType.Live, MediaType.Movie, MediaType.Series), result.catalog.entries.map { it.type })
        assertEquals("Marry Me, My Evil Lord", result.catalog.entries[1].displayName)
        assertEquals("مسلسل عربي", result.catalog.entries[2].name)
        assertTrue(result.catalog.entries.all { it.playable })
    }

    @Test
    fun `keeps the first comma outside quotes as the metadata separator`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-name="Title, with comma" group-title="Movies, Recent",Display, with comma
            https://provider.test/movie/u/p/42.mp4
        """.trimIndent()

        val entry = M3uParser().parse(StringReader(playlist)).catalog.entries.single()

        assertEquals("Title, with comma", entry.name)
        assertEquals("Display, with comma", entry.displayName)
    }

    @Test
    fun `skips entries from another account instead of mixing credentials`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-name="One" group-title="Live",One
            https://provider.test/live/u/p/1.ts
            #EXTINF:-1 tvg-name="Two" group-title="Live",Two
            https://provider.test/live/other/secret/2.ts
        """.trimIndent()

        val result = M3uParser().parse(StringReader(playlist))

        assertEquals(1, result.parsedEntries)
        assertEquals(1, result.skippedEntries)
    }
}

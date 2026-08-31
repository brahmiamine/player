package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class M3uParserTest {
    @Test
    fun `parses live movie and series entries with full EXTINF metadata`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="483974" tvg-name="France Info" tvg-logo="http://logos.test/info.png" group-title="FR | Infos",France Info HD
            provider.test:443/live/my%20user/p%40ss/483974.ts
            #EXTINF:-1 tvg-id="2133594" tvg-name="Marry Me, My Evil Lord" tvg-logo="https://logos.test/movie.jpg" group-title="TOP MOVIES 4K",Marry Me, My Evil Lord
            provider.test:443/movie/my%20user/p%40ss/2133594.ts
            #EXTINF:-1 tvg-id="49774" tvg-name="مسلسل عربي" tvg-logo="https://logos.test/series.jpg" group-title="AR | مسلسلات",مسلسل عربي
            provider.test:443/series/my%20user/p%40ss/49774.ts
        """.trimIndent()

        val result = M3uParser().parse(StringReader(playlist))

        // Les URL sans schéma commencent en HTTP ; le client/lecteur sait essayer HTTPS si nécessaire.
        assertEquals("http://provider.test:443", result.credentials.serverUrl)
        assertEquals("my user", result.credentials.username)
        assertEquals("p@ss", result.credentials.password)
        assertEquals(3, result.parsedEntries)
        assertEquals(0, result.skippedEntries)
        assertEquals(listOf(MediaType.Live, MediaType.Movie, MediaType.Series), result.catalog.entries.map { it.type })

        val live = result.catalog.entries[0]
        assertEquals("France Info", live.name)
        assertEquals("France Info HD", live.displayName)
        assertEquals("483974", live.tvgId)
        assertEquals("http://logos.test/info.png", live.iconUrl)
        assertTrue(live.playable)

        val movie = result.catalog.entries[1]
        assertEquals("Marry Me, My Evil Lord", movie.displayName)
        assertEquals("ts", movie.extension)
        assertTrue(movie.playable)

        val series = result.catalog.entries[2]
        assertEquals("مسلسل عربي", series.name)
        assertEquals("49774", series.tvgId)
        assertTrue(series.playable)

        assertEquals(3, result.catalog.categories.size)
        assertEquals(setOf("tvg-id", "tvg-name", "tvg-logo", "group-title"), result.detectedAttributes)
    }

    @Test
    fun `can retain only missing VOD sections for Xtream fallback`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-name="Live One" group-title="Live",Live One
            https://provider.test/live/u/p/1.ts
            #EXTINF:-1 tvg-name="Movie One" group-title="Movies",Movie One
            https://provider.test/movie/u/p/2.ts
            #EXTINF:-1 tvg-name="Episode One" group-title="Series",Episode One
            https://provider.test/series/u/p/3.ts
        """.trimIndent()

        val result = M3uParser().parse(
            StringReader(playlist),
            setOf(MediaType.Movie, MediaType.Series),
        )

        assertEquals(2, result.parsedEntries)
        assertEquals(0, result.catalog.count(MediaType.Live))
        assertEquals(1, result.catalog.count(MediaType.Movie))
        assertEquals(1, result.catalog.count(MediaType.Series))
        assertTrue(result.catalog.entriesFor(MediaType.Series).single().playable)
    }

    @Test
    fun `preserves explicit HTTPS transport`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="1" tvg-name="One" group-title="Live",One
            https://provider.test:8443/live/u/p/1.ts
        """.trimIndent()

        val result = M3uParser().parse(StringReader(playlist))
        assertEquals("https://provider.test:8443", result.credentials.serverUrl)
    }

    @Test
    fun `preserves explicit HTTP transport`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="1" tvg-name="One" group-title="Live",One
            http://provider.test:8080/live/u/p/1.ts
        """.trimIndent()

        val result = M3uParser().parse(StringReader(playlist))
        assertEquals("http://provider.test:8080", result.credentials.serverUrl)
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
    fun `uses supplied channel number when available`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="7" tvg-name="Seven" tvg-chno="107" group-title="Live",Seven
            https://provider.test/live/u/p/7.ts
        """.trimIndent()

        val entry = M3uParser().parse(StringReader(playlist)).catalog.entries.single()
        assertEquals(107, entry.number)
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

package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSubtitlesTest {
    @Test fun cleansIptvTitles() {
        assertEquals("Avatar" to 2009, cleanSubtitleTitle("FR | Avatar (2009) 4K MULTI"))
        assertEquals("The Boys", cleanSubtitleTitle("[FR] The Boys S02E03 HD").first)
    }

    @Test fun buildsEpisodeQueryFromSeriesTitle() {
        val q = buildSubtitleQuery(MediaType.Series, "Pilot", "S01E02 · Pilot", "FR - Dark (2017)", listOf("fra", "en", "fr"))
        assertEquals(Triple("Dark", 1, 2), Triple(q.title, q.season, q.episode))
        assertEquals(listOf("fr", "en"), q.languages)
    }

    @Test fun normalizesLanguages() {
        assertEquals("fr", normalizeLanguage("fra")); assertEquals("ar", normalizeLanguage("Arabic")); assertEquals("pt", normalizeLanguage("pt-BR"))
    }

    @Test fun parsesProviders() {
        val subdl = parseSubDl(JSONObject("""{"subtitles":[{"release_name":"A","lang":"FR","url":"/subtitle/1-2.zip","season":1,"episode":2}]}"""))
        assertEquals("https://dl.subdl.com/subtitle/1-2.zip", subdl.single().url)
        val os = parseOpenSubtitles(JSONObject("""{"data":[{"attributes":{"language":"fr","download_count":9,"release":"R","files":[{"file_id":42}]}}]}"""))
        assertEquals("42", os.single().id)
        val w = parseWyzie(JSONArray("""[{"id":"1","url":"https://x/y.srt","language":"fr","downloadCount":3}]"""))
        assertEquals("fr", w.single().language)
    }

    @Test fun ranksPreferredLanguageAndDropsWrongEpisode() {
        val q = SubtitleQuery("Dark", season = 1, episode = 2, languages = listOf("fr", "en"))
        val ranked = rank(listOf(
            SubtitleResult("a", "1", "en", "x", downloads = 99, season = 1, episode = 2),
            SubtitleResult("a", "2", "fr", "x", downloads = 1, season = 1, episode = 2),
            SubtitleResult("a", "3", "fr", "x", downloads = 500, season = 1, episode = 5),
        ), q)
        assertEquals(listOf("2", "1"), ranked.map { it.id })
    }

    @Test fun prefersReleaseCloseToTheFile() {
        val q = SubtitleQuery("Dune", languages = listOf("fr"), releaseHint = "Dune.2021.1080p.BluRay.x264-GRP")
        val ranked = rank(listOf(
            SubtitleResult("a", "1", "fr", "Dune 2021 720p WEB-DL", downloads = 900),
            SubtitleResult("a", "2", "fr", "Dune.2021.1080p.BluRay.x264-GRP", downloads = 5),
        ), q)
        assertEquals(listOf("2", "1"), ranked.map { it.id })
    }

    @Test fun shiftsSubtitleTimings() {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\nHello 00:00:09,000\n"
        assertEquals("1\n00:00:03,000 --> 00:00:04,500\nHello 00:00:09,000\n", shiftSubtitleTimings(srt, 2_000))
        assertEquals("1\n00:00:00,000 --> 00:00:01,000\nHello 00:00:09,000\n", shiftSubtitleTimings(srt, -1_500))
        assertEquals("WEBVTT\n\n00:03.000 --> 01:00.000\nx", shiftSubtitleTimings("WEBVTT\n\n00:01.000 --> 00:58.000\nx", 2_000))
    }

    @Test fun picksEpisodeFromZipPackAndDecodesLegacyCharset() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("Show.S01E01.srt")); z.write("one".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("Show.S01E02.srt")); z.write("two".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        assertEquals("two", String(extractSubtitle(zip, 1, 2).second))
        assertEquals("é", decodeSubtitle(byteArrayOf(0xE9.toByte()), "fr"))
        assertTrue(decodeSubtitle("هلا".toByteArray(Charsets.UTF_8), "ar") == "هلا")
    }
}

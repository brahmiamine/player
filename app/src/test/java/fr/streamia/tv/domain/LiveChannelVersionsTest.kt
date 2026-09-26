package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChannelVersionsTest {
    @Test
    fun groupsQualityAndPrefixVariantsOfTheSameChannel() {
        val key = LiveVersionNames.groupKey("TF1")
        listOf("FR| TF1 FHD", "|FR| TF1 UHD", "[FR] TF1 HD", "FR: TF1 4K", "TF1 ᴴᴰ", "TF1 ⁴ᴷ", "TF1 HEVC", "TF1 backup", "TF1²", "|UHD| TF1")
            .forEach { assertEquals(it, key, LiveVersionNames.groupKey(it)) }
        assertEquals(LiveVersionNames.groupKey("RMC Sport 1"), LiveVersionNames.groupKey("RMC SPORT1 FHD"))
    }

    @Test
    fun keepsDifferentChannelsApart() {
        assertNotEquals(LiveVersionNames.groupKey("TF1"), LiveVersionNames.groupKey("TF1 +1"))
        assertNotEquals(LiveVersionNames.groupKey("beIN SPORTS 1 HD"), LiveVersionNames.groupKey("beIN SPORTS 2 HD"))
        assertNotEquals(LiveVersionNames.groupKey("beIN SPORTS 1"), LiveVersionNames.groupKey("beIN SPORTS 11"))
        assertNotEquals(LiveVersionNames.groupKey("France 3"), LiveVersionNames.groupKey("France 3 Alpes"))
        assertEquals("", LiveVersionNames.groupKey("|FR| UHD"))
    }

    @Test
    fun readsAnnouncedQualityAndLanguage() {
        assertEquals(2160, LiveVersionNames.announcedHeight("FR| TF1 UHD"))
        assertEquals(2160, LiveVersionNames.announcedHeight("|4K| TF1"))
        assertEquals(1080, LiveVersionNames.announcedHeight("TF1 FHD"))
        assertEquals(720, LiveVersionNames.announcedHeight("TF1 ᴴᴰ"))
        assertNull(LiveVersionNames.announcedHeight("TF1"))
        assertEquals("FR", LiveVersionNames.language("|FR| TF1"))
        assertEquals("AR", LiveVersionNames.language("AR: beIN SPORTS 1"))
        assertEquals("FR", LiveVersionNames.language("|UHD| |FR| TF1"))
        assertNull(LiveVersionNames.language("TF1 HD"))
    }

    @Test
    fun indexFindsVersionsByNameOrGuideId() {
        val tf1 = live(1, "FR| TF1 FHD", tvgId = "TF1.fr")
        val tf1Uhd = live(2, "FR| TF1 UHD")
        val renamed = live(3, "FR| La Une TF1 Direct", tvgId = "tf1.fr")
        val tf1Plus1 = live(4, "FR| TF1 +1", tvgId = "TF1.fr")
        val m6 = live(5, "FR| M6 HD", tvgId = "M6.fr")
        val index = LiveVersionIndex(listOf(tf1, tf1Uhd, renamed, tf1Plus1, m6))
        assertEquals(listOf(1, 2, 3), index.versionsOf(tf1).map { it.id })
        assertEquals(listOf(4), index.versionsOf(tf1Plus1).map { it.id })
        assertEquals(listOf(5), index.versionsOf(m6).map { it.id })
    }

    @Test
    fun ignoresGuideIdsSharedByTooManyChannels() {
        val channels = (1..20).map { live(it, "Chaîne locale $it", tvgId = "local.fr") }
        assertEquals(listOf(1), LiveVersionIndex(channels).versionsOf(channels.first()).map { it.id })
    }

    @Test
    fun ranksOnMeasuredQualityNotOnTheName() {
        val current = live(1, "FR| TF1 HD")
        val fakeUhd = live(2, "FR| TF1 UHD")
        val fhd = live(3, "FR| TF1 FHD")
        val stats = mapOf(
            current.key to stable(1280, 720, fps = 50f),
            fakeUhd.key to stable(1920, 1080, fps = 25f),
            fhd.key to stable(1920, 1080, fps = 50f),
        )
        val ranked = rankLiveVersions(current, listOf(current, fakeUhd, fhd), stats, NOW)
        assertEquals(listOf(3, 2, 1), ranked.map { it.entry.id })
        assertTrue(ranked.first().recommended)
        assertTrue(ranked.last().current)
        assertTrue(ranked.first { it.entry.id == 2 }.warnings.contains("annoncée UHD, réellement 1080p"))
        assertEquals("1080p · 50 fps", ranked.first().qualityText)
    }

    @Test
    fun flagsLowBitrate4kAndPrefersStableThenUntestedVersions() {
        val current = live(1, "TF1 4K")
        val choppy = live(2, "TF1 FHD")
        val untested = live(3, "TF1 UHD")
        val broken = live(4, "TF1 backup")
        val stats = mapOf(
            current.key to stable(3840, 2160, bitrate = 4_000_000, codec = "H.265 / HEVC"),
            choppy.key to stable(1920, 1080).copy(watchedMs = 20 * 60_000L, rebufferCount = 5),
            broken.key to LiveVersionStats(lastFailureAtMs = NOW - 2 * 3_600_000L, consecutiveFailures = 1),
        )
        val ranked = rankLiveVersions(current, listOf(current, choppy, untested, broken), stats, NOW)
        assertEquals(listOf(1, 3, 2, 4), ranked.map { it.entry.id })
        assertTrue(ranked[0].warnings.contains("4K douteuse (débit faible)"))
        assertEquals(LiveVersionHealth.Untested, ranked[1].health)
        assertEquals("annoncée UHD", ranked[1].qualityText)
        assertEquals(LiveVersionHealth.Choppy, ranked[2].health)
        assertEquals(LiveVersionHealth.Unavailable, ranked[3].health)
        assertEquals("il y a 2 h", ranked[3].failureAgo)
    }

    @Test
    fun oldFailuresExpireAndOtherLanguagesComeLastWithoutRecommendation() {
        val oldFailure = LiveVersionStats(lastFailureAtMs = NOW - 7 * 3_600_000L, consecutiveFailures = 1)
        assertEquals(LiveVersionHealth.Untested, health(oldFailure, NOW))

        val current = live(1, "FR: beIN SPORTS 1 HD")
        val arabic = live(2, "AR: beIN SPORTS 1 4K")
        val ranked = rankLiveVersions(current, listOf(arabic, current), mapOf(arabic.key to stable(3840, 2160)), NOW)
        assertEquals(listOf(1, 2), ranked.map { it.entry.id })
        assertFalse(ranked[1].sameLanguage)
        assertFalse(ranked[1].recommended)
    }

    @Test
    fun capsRankingToWhatTheScreenCanShow() {
        val current = live(1, "TF1 FHD")
        val uhd = live(2, "TF1 UHD")
        val stats = mapOf(current.key to stable(1920, 1080, fps = 50f), uhd.key to stable(3840, 2160, fps = 50f, bitrate = 20_000_000))
        assertEquals(1, rankLiveVersions(current, listOf(current, uhd), stats, NOW, maxDisplayHeight = 1080).first().entry.id)
        assertEquals(2, rankLiveVersions(current, listOf(current, uhd), stats, NOW, maxDisplayHeight = 2160).first().entry.id)
    }

    @Test
    fun realChecksBeatTheAnnouncedQuality() {
        val current = live(1, "TF1 FHD")
        val noSound = live(2, "TF1 UHD")
        val noPicture = live(3, "TF1 4K")
        val fakeFps = live(4, "TF1 HD")
        val stats = mapOf(
            current.key to stable(1920, 1080, fps = 50f).copy(realFrameRate = 50f),
            noSound.key to stable(3840, 2160, fps = 50f).copy(noSound = true),
            noPicture.key to stable(3840, 2160).copy(noPicture = true),
            fakeFps.key to stable(1920, 1080, fps = 50f).copy(realFrameRate = 25f, droppedRatio = 0.12f),
        )
        val ranked = rankLiveVersions(current, listOf(noSound, noPicture, fakeFps, current), stats, NOW)
        assertEquals(1, ranked.first().entry.id)
        assertEquals(LiveVersionHealth.Choppy, ranked.first { it.entry.id == 4 }.health)
        assertTrue(ranked.first { it.entry.id == 4 }.warnings.contains("annoncée 50 fps, réellement 25"))
        assertTrue(ranked.first { it.entry.id == 4 }.warnings.contains("images perdues (12 %)"))
        assertEquals("1080p · 25 fps", ranked.first { it.entry.id == 4 }.qualityText)
        assertEquals(LiveVersionHealth.NoSound, ranked.first { it.entry.id == 2 }.health)
        assertEquals(LiveVersionHealth.NoPicture, ranked.first { it.entry.id == 3 }.health)
        assertEquals(setOf(2, 3), ranked.takeLast(2).map { it.entry.id }.toSet())
    }

    private fun live(id: Int, name: String, tvgId: String? = null) =
        MediaEntry(id = id, name = name, type = MediaType.Live, categoryId = "1", iconUrl = null, number = id, tvgId = tvgId)

    private fun stable(width: Int, height: Int, fps: Float? = null, bitrate: Int? = null, codec: String? = null) =
        LiveVersionStats(width = width, height = height, frameRate = fps, bitrate = bitrate, codec = codec, lastSuccessAtMs = NOW - 60_000L, updatedAtMs = NOW)

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}

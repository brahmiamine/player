package fr.streamia.tv.liveonsat

import fr.streamia.tv.domain.EpgChannel
import fr.streamia.tv.domain.EpgGuide
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOnSatTimingTest {
    @Test
    fun `EPG window has priority over the two hour fallback`() {
        val resolved = resolved(
            start = 1_000,
            epgStart = 1_100,
            epgEnd = 1_500,
        )

        assertFalse(resolved.isLiveAt(1_050))
        assertTrue(resolved.isLiveAt(1_200))
        assertFalse(resolved.isLiveAt(1_500))
        assertFalse(resolved.isVisibleAt(1_500))
    }

    @Test
    fun `matching channel EPG enriches the match with its real end time`() {
        val channel = MediaEntry(
            id = 9,
            name = "beIN Sports 1",
            displayName = "beIN Sports 1 HD",
            type = MediaType.Live,
            categoryId = "sport",
            iconUrl = "https://example.test/bein.png",
            number = 9,
        )
        val source = ResolvedLiveOnSatMatch(
            match = LiveOnSatMatch(
                competition = "Ligue 1",
                participantA = "PSG",
                participantB = "Marseille",
                startEpochSeconds = 10_000,
                channels = listOf(LiveOnSatChannel("beIN Sports 1 HD", free = false)),
            ),
            matchedChannels = mapOf("beIN Sports 1 HD" to channel),
        )
        val guide = EpgGuide(
            channels = mapOf(
                "beIN Sports 1" to EpgChannel(
                    channelId = "beIN Sports 1",
                    displayName = "beIN Sports 1 HD",
                    programs = listOf(
                        EpgProgram(
                            title = "PSG - Marseille",
                            description = "Match en direct",
                            startEpochSeconds = 10_120,
                            endEpochSeconds = 16_000,
                        ),
                    ),
                ),
            ),
        )

        val enriched = source.withEpgTiming(guide)

        assertEquals(10_120L, enriched.epgStartEpochSeconds)
        assertEquals(16_000L, enriched.epgEndEpochSeconds)
        assertTrue(enriched.isLiveAt(15_999))
        assertFalse(enriched.isLiveAt(16_000))
    }

    @Test
    fun `two hour fallback is used when EPG has no reliable end`() {
        val resolved = resolved(start = 1_000, epgStart = null, epgEnd = null)

        assertFalse(resolved.isLiveAt(999))
        assertTrue(resolved.isLiveAt(1_000))
        assertTrue(resolved.isLiveAt(8_199))
        assertFalse(resolved.isLiveAt(8_200))
        assertFalse(resolved.isVisibleAt(8_200))
    }

    private fun resolved(start: Long, epgStart: Long?, epgEnd: Long?) = ResolvedLiveOnSatMatch(
        match = LiveOnSatMatch(
            competition = "Test League",
            participantA = "Team A",
            participantB = "Team B",
            participantALogoUrl = null,
            participantBLogoUrl = null,
            startEpochSeconds = start,
            channels = emptyList(),
        ),
        matchedChannels = emptyMap(),
        epgStartEpochSeconds = epgStart,
        epgEndEpochSeconds = epgEnd,
    )
}

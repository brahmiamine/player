package fr.streamia.tv.liveonsat

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

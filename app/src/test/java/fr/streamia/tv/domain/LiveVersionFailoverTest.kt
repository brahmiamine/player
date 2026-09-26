package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVersionFailoverTest {
    @Test
    fun thirdCutOfAtLeastFiveSecondsWithinFiveMinutesTriggersASwitch() {
        val counter = LiveCutCounter()
        assertFalse(counter.onCutEnded(5_000L, at(0)))
        assertFalse(counter.onCutEnded(5_400L, at(60)))
        assertTrue(counter.onCutEnded(5_900L, at(120)))
    }

    @Test
    fun shortCutsAreIgnoredAndOldCutsLeaveTheWindow() {
        val counter = LiveCutCounter()
        assertFalse(counter.onCutEnded(4_999L, at(0)))
        assertFalse(counter.onCutEnded(1_000L, at(1)))
        assertEquals(0, counter.count)

        assertFalse(counter.onCutEnded(5_000L, at(0)))
        assertFalse(counter.onCutEnded(5_000L, at(100)))
        // 5 min 01 après la première : elle ne compte plus, on n'est qu'à 2 coupures.
        assertFalse(counter.onCutEnded(5_000L, at(301)))
        assertEquals(2, counter.count)
        assertTrue(counter.onCutEnded(5_000L, at(310)))
    }

    @Test
    fun noCandidatesWithoutAnotherVersionOfTheSameLanguage() {
        val tf1 = live(1, "FR| TF1 HD")
        assertFalse(hasFailoverCandidates(tf1, listOf(tf1)))
        assertFalse(hasFailoverCandidates(tf1, listOf(tf1, live(2, "BE| TF1 HD"))))
        assertTrue(hasFailoverCandidates(tf1, listOf(tf1, live(3, "FR| TF1 FHD"))))
        assertTrue(hasFailoverCandidates(tf1, listOf(tf1, live(4, "TF1 SD"))))
    }

    @Test
    fun switchesToBestUntriedVersionThenBackToOriginOnce() {
        val fhd = live(1, "FR| TF1 FHD")
        val hd = live(2, "FR| TF1 HD")
        val sd = live(3, "FR| TF1 SD")
        val be = live(4, "BE| TF1 HD")
        val all = listOf(fhd, hd, sd, be)

        val first = decideLiveFailover(fhd, rankLiveVersions(fhd, all, emptyMap(), NOW), null, NOW)
        assertEquals(hd, first.target)
        assertEquals(setOf(fhd.key, hd.key), first.chain.tried)

        val second = decideLiveFailover(hd, rankLiveVersions(hd, all, emptyMap(), NOW + 10_000), first.chain, NOW + 10_000)
        assertEquals(sd, second.target)

        // Plus de version de la même langue (BE exclue) : retour sur la chaîne de départ, signalé une fois.
        val third = decideLiveFailover(sd, rankLiveVersions(sd, all, emptyMap(), NOW + 20_000), second.chain, NOW + 20_000)
        assertEquals(fhd, third.target)
        assertTrue(third.backToOrigin)
        assertTrue(third.justExhausted)

        val fourth = decideLiveFailover(fhd, rankLiveVersions(fhd, all, emptyMap(), NOW + 30_000), third.chain, NOW + 30_000)
        assertNull(fourth.target)
        assertFalse(fourth.justExhausted)
    }

    @Test
    fun seriesRestartsAfterFiveQuietMinutesOrWhenTheViewerZapsElsewhere() {
        val fhd = live(1, "TF1 FHD")
        val hd = live(2, "TF1 HD")
        val exhausted = LiveFailoverChain(origin = fhd, tried = setOf(fhd.key, hd.key), lastSwitchAtMs = NOW, exhausted = true)

        val later = NOW + LiveFailoverRules.CHAIN_RESET_AFTER_MS
        assertEquals(hd, decideLiveFailover(fhd, rankLiveVersions(fhd, listOf(fhd, hd), emptyMap(), later), exhausted, later).target)

        val m6 = live(3, "M6 FHD")
        val m6Hd = live(4, "M6 HD")
        assertEquals(m6Hd, decideLiveFailover(m6, rankLiveVersions(m6, listOf(m6, m6Hd), emptyMap(), NOW), exhausted, NOW).target)
    }

    private fun at(seconds: Int) = NOW + seconds * 1_000L

    private fun live(id: Int, name: String) =
        MediaEntry(id = id, name = name, type = MediaType.Live, categoryId = "1", iconUrl = null, number = id)

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}

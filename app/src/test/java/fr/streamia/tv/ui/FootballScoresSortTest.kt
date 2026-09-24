package fr.streamia.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.Instant

class FootballScoresSortTest {
    private fun match(id: String, state: String, kickoff: Long) =
        FootballMatch(id, "", null, "", null, "", "", null, "", state, "", Instant.ofEpochSecond(kickoff))

    @Test
    fun liveFirstThenUpcomingThenFinished() {
        val sorted = sortFootballMatches(
            listOf(match("post-old", "post", 1), match("pre-late", "pre", 9), match("in", "in", 5), match("pre-soon", "pre", 7), match("post-recent", "post", 3)),
        )
        assertEquals(listOf("in", "post-recent", "post-old", "pre-soon", "pre-late"), sorted.map { it.id })
    }

    @Test
    fun refreshEveryMinuteOnlyAroundLiveMatches() {
        val now = Instant.ofEpochSecond(10_000)
        assertEquals(60_000L, footballRefreshDelayMs(listOf(match("a", "in", 9_000)), now))
        assertEquals(60_000L, footballRefreshDelayMs(listOf(match("a", "pre", 10_600)), now))
        assertEquals(900_000L, footballRefreshDelayMs(listOf(match("a", "pre", 20_000), match("b", "post", 1)), now))
        assertEquals(900_000L, footballRefreshDelayMs(emptyList(), now))
    }

    @Test
    fun diskCacheKeepsTodayMatchesOnly() {
        val file = File.createTempFile("football", ".json").apply { deleteOnExit() }
        val today = System.currentTimeMillis()
        val matches = listOf(
            FootballMatch("1", "Premier League", "logo", "Arsenal", null, "2", "Chelsea", "badge", "1", "in", "67'", Instant.ofEpochSecond(1_000)),
        )
        saveFootballDiskCache(file, today, matches)
        assertEquals(today to matches, loadFootballDiskCache(file))

        saveFootballDiskCache(file, today - 2 * 24 * 60 * 60_000L, matches)
        assertNull(loadFootballDiskCache(file))
    }
}

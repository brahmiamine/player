package fr.streamia.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimedCueIndexTest {
    private val index = TimedCueIndex(
        listOf(
            TimedCue(5_000, 7_000, "c"),
            TimedCue(1_000, 3_000, "a"),
            TimedCue(2_500, 4_000, "b"),
            TimedCue(10_000, 20_000, "long"),
            TimedCue(12_000, 13_000, "inside"),
        ),
    )

    private fun at(positionMs: Long) = index.activeAt(positionMs).map(index::payload)

    @Test
    fun `finds the cue shown at a position`() {
        assertEquals(emptyList<String>(), at(0))
        assertEquals(listOf("a"), at(1_000))
        assertEquals(listOf("a", "b"), at(2_700))
        assertEquals(listOf("b"), at(3_000))
        assertEquals(emptyList<String>(), at(4_500))
        assertEquals(listOf("c"), at(6_999))
        assertEquals(emptyList<String>(), at(7_000))
    }

    @Test
    fun `keeps a long cue visible across shorter ones`() {
        assertEquals(listOf("long", "inside"), at(12_500))
        assertEquals(listOf("long"), at(15_000))
        assertEquals(emptyList<String>(), at(25_000))
    }

    @Test
    fun `offset shifts display without touching the cues`() {
        val offsetMs = 2_000L // + = plus tard
        assertEquals(emptyList<String>(), at(1_500 - offsetMs))
        assertEquals(listOf("a"), at(3_500 - offsetMs))
        val earlier = -1_000L
        assertEquals(listOf("c"), at(4_200 - earlier))
    }
}

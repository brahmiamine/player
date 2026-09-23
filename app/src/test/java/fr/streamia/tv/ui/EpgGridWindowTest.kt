package fr.streamia.tv.ui

import fr.streamia.tv.domain.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class EpgGridWindowTest {
    private fun program(title: String, start: Long, end: Long) = EpgProgram(title, null, start, end)

    @Test
    fun `blocks are clipped to the window, sorted, and invalid ones dropped`() {
        val programs = listOf(
            program("C", 7_000, 9_000),
            program("A", 0, 2_000),
            program("B", 2_000, 7_000),
            program("Hors", 20_000, 21_000),
            EpgProgram("Sans heure", null, null, null),
        )
        val blocks = blocksInWindow(programs, windowStart = 1_000, windowEnd = 8_200)
        assertEquals(listOf("A", "B", "C"), blocks.map { it.program.title })
        assertEquals(1_000L, blocks.first().clippedStart)
        assertEquals(8_200L, blocks.last().clippedEnd)
    }

    @Test
    fun `window stays inside the day`() {
        val dayStart = 0L
        val dayEnd = 86_400L
        assertEquals(0L, clampWindow(-3_600, dayStart, dayEnd))
        assertEquals(86_400L - 7_200L, clampWindow(86_000, dayStart, dayEnd))
        assertEquals(36_000L, clampWindow(36_000, dayStart, dayEnd))
    }
}

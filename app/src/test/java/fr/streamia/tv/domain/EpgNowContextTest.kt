package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgNowContextTest {
    private fun program(title: String, start: Long, end: Long) = EpgProgram(
        title = title,
        description = null,
        startEpochSeconds = start,
        endEpochSeconds = end,
    )

    @Test
    fun `selects previous current and next around now`() {
        val programs = listOf(
            program("Avant", 100, 200),
            program("En cours", 200, 300),
            program("Après", 300, 400),
            program("Plus tard", 400, 500),
        )

        val context = programs.epgNowContextAt(nowEpochSeconds = 250)

        assertEquals("Avant", context.previous?.title)
        assertEquals("En cours", context.current?.title)
        assertEquals("Après", context.next?.title)
    }

    @Test
    fun `applies display offset without changing source selection semantics`() {
        val programs = listOf(
            program("Avant", 100, 200),
            program("En cours", 200, 300),
            program("Après", 300, 400),
        )

        val context = programs.epgNowContextAt(
            nowEpochSeconds = 3_850,
            offsetHours = 1,
        )

        assertEquals("En cours", context.current?.title)
        assertEquals(3_800L, context.current?.startEpochSeconds)
        assertEquals(3_900L, context.current?.endEpochSeconds)
        assertEquals("Avant", context.previous?.title)
        assertEquals("Après", context.next?.title)
    }

    @Test
    fun `returns an empty context when no timed program exists`() {
        val context = listOf(
            EpgProgram("Sans horaire", null, null, null),
        ).epgNowContextAt(nowEpochSeconds = 250)

        assertNull(context.previous)
        assertNull(context.current)
        assertNull(context.next)
    }
}

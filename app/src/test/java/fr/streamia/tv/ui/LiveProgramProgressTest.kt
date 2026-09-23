package fr.streamia.tv.ui

import fr.streamia.tv.domain.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveProgramProgressTest {
    private fun program(start: Long?, end: Long?) = EpgProgram("JT", null, start, end)

    @Test
    fun `progress is clamped and needs valid times`() {
        assertEquals(0.5f, liveProgramProgress(program(100, 200), 150)!!, 0.001f)
        assertEquals(0f, liveProgramProgress(program(100, 200), 50)!!, 0.001f)
        assertEquals(1f, liveProgramProgress(program(100, 200), 500)!!, 0.001f)
        assertNull(liveProgramProgress(program(null, 200), 150))
        assertNull(liveProgramProgress(program(200, 100), 150))
    }
}

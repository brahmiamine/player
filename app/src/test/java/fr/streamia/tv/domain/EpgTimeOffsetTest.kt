package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EpgTimeOffsetTest {
    private val guide = EpgGuide(
        channels = mapOf(
            "tf1" to EpgChannel(
                channelId = "tf1",
                displayName = "TF1",
                programs = listOf(
                    EpgProgram(title = "JT", description = null, startEpochSeconds = 1_000L, endEpochSeconds = 2_000L),
                    EpgProgram(title = "Sans horaire", description = null, startEpochSeconds = null, endEpochSeconds = null),
                ),
            ),
        ),
    )

    @Test
    fun `zero offset returns the same instance`() {
        assertSame(guide, guide.withTimeOffset(0))
    }

    @Test
    fun `positive offset shifts start and end forward`() {
        val shifted = guide.withTimeOffset(2).channels.getValue("tf1").programs.first()
        assertEquals(1_000L + 7_200L, shifted.startEpochSeconds)
        assertEquals(2_000L + 7_200L, shifted.endEpochSeconds)
    }

    @Test
    fun `negative offset shifts backward`() {
        val shifted = guide.withTimeOffset(-1).channels.getValue("tf1").programs.first()
        assertEquals(1_000L - 3_600L, shifted.startEpochSeconds)
        assertEquals(2_000L - 3_600L, shifted.endEpochSeconds)
    }

    @Test
    fun `missing epoch values stay null`() {
        val shifted = guide.withTimeOffset(3).channels.getValue("tf1").programs[1]
        assertEquals(null, shifted.startEpochSeconds)
        assertEquals(null, shifted.endEpochSeconds)
    }
}

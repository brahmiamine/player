package fr.streamia.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLoadingsPageTest {
    private val now = 1_000_000_000_000L
    private fun min(n: Long) = n * 60_000L

    @Test
    fun relativeFormatsPastFutureAndUnknown() {
        assertEquals("—", relative(null, now))
        assertEquals("à l'instant", relative(now - 20_000, now))
        assertEquals("il y a 12 min", relative(now - min(12), now))
        assertEquals("dans 18 min", relative(now + min(18), now))
        assertEquals("il y a 3 h", relative(now - min(180), now))
        assertEquals("dans 2 j", relative(now + min(60 * 24 * 2 + 30), now))
    }
}

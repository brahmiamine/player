package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.TimeZone

class PrayerTimesTest {
    @Test
    fun fivePrayersInOrderForParis() {
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = TimeZone.getTimeZone("UTC") }
        val times = prayerTimes(HomePlace("Paris", 48.8566, 2.3522), PrayerMethod.MuslimWorldLeague, utc.parse("2026-09-24 12:00")!!)
        assertEquals(listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"), times.map { it.first })
        assertTrue(times.zipWithNext().all { (a, b) -> a.second.before(b.second) })
        // Dhuhr à Paris fin septembre : vers 11:45 UTC (13:45 heure de Paris).
        assertEquals("11:4", utc.format(times[1].second).substringAfter(' ').take(4))
    }
}

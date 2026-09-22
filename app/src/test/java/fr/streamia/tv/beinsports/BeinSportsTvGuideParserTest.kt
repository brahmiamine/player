package fr.streamia.tv.beinsports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BeinSportsTvGuideParserTest {
    @Test
    fun parsesEveryMenaChannelIncludingAfcAndNbaVariants() {
        val channels = BeinSportsTvGuideParser.parseChannels(CHANNELS_JSON)

        assertEquals(
            listOf(
                "beIN SPORTS 1",
                "beIN SPORTS 2",
                "beIN SPORTS EN 1",
                "beIN SPORTS XTRA 1",
                "beIN SPORTS MAX 1",
                "beIN SPORTS 1 AFC",
                "beIN SPORTS NBA",
            ),
            channels.map { it.name },
        )
    }

    @Test
    fun parsesEventsAndSelectsCurrentAndNextFromUtcTimes() {
        val channels = BeinSportsTvGuideParser.parseChannels(CHANNELS_JSON)
        val schedules = BeinSportsTvGuideParser.parseEvents(EVENTS_JSON, channels)

        val now = millis("2026-09-22T19:47:00Z")
        val rows = BeinGuideSelector.select(schedules, now)

        assertEquals(
            listOf("beIN SPORTS 1", "beIN SPORTS 2", "beIN SPORTS EN 1", "beIN SPORTS 1 AFC"),
            schedules.map { it.channelName },
        )
        assertEquals("Karate - Asian Games", rows.current.single { it.channelName == "beIN SPORTS 1 AFC" }.title)
        // Les créneaux de remplissage (MAX, bandeau XTRA) sont ignorés.
        assertTrue(schedules.none { it.channelName == "beIN SPORTS MAX 1" })
        assertTrue(schedules.none { it.channelName == "beIN SPORTS XTRA 1" })

        val sports1Now = rows.current.single { it.channelName == "beIN SPORTS 1" }
        assertEquals("La Liga Highlights 2026/2027 EP.7", sports1Now.title)
        assertEquals("Football", sports1Now.category)
        assertFalse(sports1Now.isLive)
        assertEquals(
            "Fulham vs Machester United - English Premier League",
            rows.next.single { it.channelName == "beIN SPORTS 1" }.title,
        )

        val sports2Now = rows.current.single { it.channelName == "beIN SPORTS 2" }
        assertEquals("Real Madrid vs PSG - UEFA Women's Champions League", sports2Now.title)
        assertTrue(sports2Now.isLive)
        assertEquals(
            "Philippines vs Kuwait - Football Men - Asian Games",
            rows.next.single { it.channelName == "beIN SPORTS 2" }.title,
        )

        // EN 1 : rien à l'antenne à 19:47 UTC, mais le programme suivant est connu.
        assertTrue(rows.current.none { it.channelName == "beIN SPORTS EN 1" })
        assertEquals("UEFA Nations League - Preview Show", rows.next.single { it.channelName == "beIN SPORTS EN 1" }.title)
    }

    @Test
    fun switchesToNextProgrammeExactlyAtItsStartTime() {
        val channels = BeinSportsTvGuideParser.parseChannels(CHANNELS_JSON)
        val schedules = BeinSportsTvGuideParser.parseEvents(EVENTS_JSON, channels)

        val rows = BeinGuideSelector.select(schedules, millis("2026-09-22T20:00:00Z"))

        assertEquals(
            "Fulham vs Machester United - English Premier League",
            rows.current.single { it.channelName == "beIN SPORTS 1" }.title,
        )
    }

    @Test
    fun handlesProgrammeCrossingMidnightAndComputesProgress() {
        val schedule = BeinChannelSchedule(
            channelName = "beIN SPORTS 2",
            programmes = listOf(
                programme("Late match", "2026-09-22T20:20:00Z", "2026-09-22T22:10:00Z"),
                programme("Highlights", "2026-09-22T22:10:00Z", "2026-09-22T23:00:00Z"),
            ),
        )
        val now = millis("2026-09-22T21:15:00Z")

        val rows = BeinGuideSelector.select(listOf(schedule), now)

        assertEquals("Late match", rows.current.single().title)
        assertEquals("Highlights", rows.next.single().title)
        assertEquals(55f / 110f, rows.current.single().progressAt(now)!!, 0.001f)
        assertNull(rows.next.single().progressAt(now))
    }

    private fun programme(title: String, start: String, end: String) = BeinProgrammeItem(
        channelName = "beIN SPORTS 2",
        category = "Football",
        title = title,
        startEpochMillis = millis(start),
        endEpochMillis = millis(end),
    )

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private companion object {
        const val CHANNELS_JSON = """
            {"count":7,"rows":[
              {"id":"FD1DD7DD-1E7B-4AA2-8682-BFA17338E653","name":"beIN SPORTS 2"},
              {"id":"7836FEA9-6B39-4A1A-8352-DC5FCB97A16C","name":"beIN SPORTS 1"},
              {"id":"0CB3E227-4376-4545-AB64-D6C390F644D8","name":"beIN SPORTS 1 AFC"},
              {"id":"2F518547-2269-4C07-93D5-2733397472BD","name":"beIN SPORTS NBA"},
              {"id":"2FB43094-3598-43C1-A3BA-44BFB40092E0","name":"beIN SPORTS MAX 1"},
              {"id":"E3B37FA0-E582-45B2-BB8E-516E1A714EF6","name":"beIN SPORTS XTRA 1"},
              {"id":"8C1EC4FC-35E6-4866-A75D-37FCFAE18839","name":"beIN SPORTS EN 1"}
            ]}
        """

        const val EVENTS_JSON = """
            {"count":9,"rows":[
              {"title":"Fulham vs Machester United - English Premier League","category":"Football",
               "startDate":"2026-09-22T20:00:00.000Z","endDate":"2026-09-22T20:30:00.000Z","live":false,
               "channelId":"7836FEA9-6B39-4A1A-8352-DC5FCB97A16C","data":{"ImageURL":""}},
              {"title":"La Liga Highlights 2026/2027 EP.7","category":"Football",
               "startDate":"2026-09-22T19:30:00.000Z","endDate":"2026-09-22T20:00:00.000Z","live":false,
               "channelId":"7836FEA9-6B39-4A1A-8352-DC5FCB97A16C","data":{"ImageURL":""}},
              {"title":"Real Madrid vs PSG - UEFA Women's Champions League","category":"Football",
               "startDate":"2026-09-22T18:50:00.000Z","endDate":"2026-09-22T21:00:00.000Z","live":true,
               "channelId":"FD1DD7DD-1E7B-4AA2-8682-BFA17338E653"},
              {"title":"Philippines vs Kuwait - Football Men - Asian Games","category":"Asian Games",
               "startDate":"2026-09-22T21:00:00.000Z","endDate":"2026-09-22T23:00:00.000Z","live":false,
               "channelId":"FD1DD7DD-1E7B-4AA2-8682-BFA17338E653"},
              {"title":"Late replay","category":"Football",
               "startDate":"2026-09-23T01:00:00.000Z","endDate":"2026-09-23T03:00:00.000Z","live":false,
               "channelId":"FD1DD7DD-1E7B-4AA2-8682-BFA17338E653"},
              {"title":"UEFA Nations League - Preview Show","category":"Football",
               "startDate":"2026-09-22T21:00:00.000Z","endDate":"2026-09-22T21:45:00.000Z","live":false,
               "channelId":"8C1EC4FC-35E6-4866-A75D-37FCFAE18839"},
              {"title":"beIN Sports MAX","category":"",
               "startDate":"2026-09-22T19:00:00.000Z","endDate":"2026-09-22T21:00:00.000Z","live":false,
               "channelId":"2FB43094-3598-43C1-A3BA-44BFB40092E0"},
              {"title":"beIN SPORTS XTRA For Live And Exclusive Coverage of Premium Sporting Events - August - 2026",
               "category":"","startDate":"2026-09-22T19:00:00.000Z","endDate":"2026-09-22T20:00:00.000Z","live":false,
               "channelId":"E3B37FA0-E582-45B2-BB8E-516E1A714EF6"},
              {"title":"Karate - Asian Games","category":"Asian Games",
               "startDate":"2026-09-22T18:20:00.000Z","endDate":"2026-09-22T23:15:00.000Z","live":false,
               "channelId":"0CB3E227-4376-4545-AB64-D6C390F644D8"}
            ]}
        """
    }
}

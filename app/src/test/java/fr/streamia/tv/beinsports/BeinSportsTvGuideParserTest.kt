package fr.streamia.tv.beinsports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class BeinSportsTvGuideParserTest {
    @Test
    fun parsesChannelProgrammesAndSelectsCurrentAndNext() {
        val html = """
            <html><body>
              <section class="channel">
                <h2>beIN SPORTS 1</h2>
                <div class="event">
                  <div>English Premier League</div>
                  <div>Live</div>
                  <strong>Arsenal vs Liverpool - Premier League</strong>
                  <div class="time"><span>20:00</span> <span>22:00</span></div>
                </div>
                <div class="event">
                  <div>Football</div>
                  <strong>Premier League Review</strong>
                  <div class="time"><span>22:00</span> <span>23:00</span></div>
                </div>
              </section>
              <section class="channel">
                <h2>beIN SPORTS EN 1</h2>
                <div class="event">
                  <div>Football</div>
                  <strong>Champions League Magazine</strong>
                  <div class="time"><span>20:30</span> <span>21:30</span></div>
                </div>
                <div class="event">
                  <div>Series</div>
                  <strong>EPL World</strong>
                  <div class="time"><span>21:30</span> <span>22:30</span></div>
                </div>
              </section>
            </body></html>
        """.trimIndent()

        val schedules = BeinSportsTvGuideParser.parse(html)
        val rows = BeinGuideSelector.select(schedules, LocalTime.of(21, 0))

        assertEquals(2, schedules.size)
        assertEquals("beIN SPORTS 1", schedules[0].channelName)
        assertEquals(2, schedules[0].programmes.size)
        assertEquals("Arsenal vs Liverpool - Premier League", rows.current[0].title)
        assertTrue(rows.current[0].isLive)
        assertEquals("Premier League Review", rows.next[0].title)
        assertEquals("Champions League Magazine", rows.current[1].title)
        assertEquals("EPL World", rows.next[1].title)
    }

    @Test
    fun handlesProgrammeCrossingMidnight() {
        val schedule = BeinChannelSchedule(
            channelName = "beIN SPORTS 2",
            programmes = listOf(
                BeinProgrammeItem(
                    channelName = "beIN SPORTS 2",
                    category = "Football",
                    title = "Late match",
                    startTime = "23:20",
                    endTime = "01:10",
                ),
                BeinProgrammeItem(
                    channelName = "beIN SPORTS 2",
                    category = "Football",
                    title = "Highlights",
                    startTime = "01:10",
                    endTime = "02:00",
                ),
            ),
        )

        val rows = BeinGuideSelector.select(listOf(schedule), LocalTime.of(0, 15))

        assertEquals("Late match", rows.current.single().title)
        assertEquals("Highlights", rows.next.single().title)
        assertEquals(55f / 110f, rows.current.single().progressAt(LocalTime.of(0, 15))!!, 0.001f)
    }
}

package fr.streamia.tv.tvprogramme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalTime

class TvProgrammeNowParserTest {
    @Test
    fun parsesCurrentProgrammeAndUsesNextStartAsEndTime() {
        val html = """
            <html><body>
              <div class="channel-row">
                <a href="/tf1">N° 1</a>
                <div class="programme">
                  <span>09h30</span>
                  <a href="/tfou"><img src="/tfou.webp" alt="Visuel de TFou">TFou</a>
                </div>
                <div class="programme">
                  <span>10h10</span>
                  <a href="/journal"><img data-src="//img.tv-programme.com/jt.webp" alt="Visuel de JT">Le journal Direct</a>
                </div>
                <div class="programme">
                  <span>10h25</span>
                  <a href="/magazine">Le magazine</a>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val result = TvProgrammeNowParser.parse(html, LocalTime.of(10, 17))

        assertEquals(1, result.size)
        val programme = result.single()
        assertEquals("tf1", programme.channelName)
        assertEquals("10:10", programme.startTime)
        assertEquals("10:25", programme.endTime)
        assertEquals("Le journal", programme.title)
        assertEquals("https://img.tv-programme.com/jt.webp", programme.imageUrl)

        val progress = programme.progressAt(LocalTime.of(10, 17))
        assertNotNull(progress)
        assertEquals(7f / 15f, progress!!, 0.001f)
    }

    @Test
    fun readsSemanticChannelLogoAndHandlesProgrammeAcrossMidnight() {
        val html = """
            <html><body>
              <div class="channel-row">
                <a href="/france-2">N° 2</a>
                <img src="/france2.svg" alt="Logo de la chaîne France 2 programme">
                <div class="programme"><span>23h50</span><a href="/film">Film de nuit</a></div>
                <div class="programme"><span>00h20</span><a href="/news">Journal de nuit</a></div>
                <div class="programme"><span>01h00</span><a href="/suite">Programme suivant</a></div>
              </div>
            </body></html>
        """.trimIndent()

        val programme = TvProgrammeNowParser.parse(html, LocalTime.of(0, 5)).single()

        assertEquals("France 2", programme.channelName)
        assertEquals("23:50", programme.startTime)
        assertEquals("00:20", programme.endTime)
        assertEquals("Film de nuit", programme.title)
        assertEquals(0.5f, programme.progressAt(LocalTime.of(0, 5))!!, 0.001f)
    }
}

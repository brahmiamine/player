package fr.streamia.tv.liveonsat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOnSatParserTest {
    @Test
    fun `parses teams, time, competition and channels from a match block`() {
        val matches = LiveOnSatParser.parse(HTML_ONE_MATCH)

        assertEquals(1, matches.size)
        val match = matches.first()
        assertEquals("Test League", match.competition)
        assertEquals("Team A", match.participantA)
        assertEquals("Team B", match.participantB)
        assertEquals(1_700_000_000L, match.startEpochSeconds)
        assertEquals(
            listOf(
                LiveOnSatChannel("Free Channel HD", free = true),
                LiveOnSatChannel("Paid Channel 1", free = false),
            ),
            match.channels,
        )
    }

    @Test
    fun `non-match entries such as draws are skipped`() {
        val matches = LiveOnSatParser.parse(HTML_WITH_DRAW)

        assertEquals(1, matches.size)
        assertTrue(matches.none { it.participantA.contains("Draw") })
    }

    @Test
    fun `a block without a resolvable timestamp is skipped`() {
        val matches = LiveOnSatParser.parse(HTML_NO_TIMESTAMP)

        assertEquals(0, matches.size)
    }

    @Test
    fun `competition changes carry over to following matches until the next header`() {
        val matches = LiveOnSatParser.parse(HTML_TWO_COMPETITIONS)

        assertEquals(2, matches.size)
        assertEquals("League One", matches[0].competition)
        assertEquals("League Two", matches[1].competition)
    }

    private companion object {
        val HTML_ONE_MATCH = """
            <html><body>
            <div><span class=comp_head>Test League</span></div>
            <div class=blockfix>
              <div class=fix>
                <div class=fix_text>
                  <div class = imgCenter><img src="a.gif"></div>
                  <div class = fLeft style="width:270px">Team A v Team B</div>
                  <div class = imgCenter><img src="b.gif"></div>
                </div>
                <div class=notes></div>
              </div>
              <div class = fLeft>
                <div>
                  <div class=fLeft_icon_live_l><img src="live.png"/></div>
                  <div class="fLeft_time_live dynamic-time" data-timestamp="1700000000">ST: 19:30</div>
                  <div class = fLeft_live>
                    <table><tr><td class=chan_col><a href="#" class = chan_live_free>Free Channel HD</a></td></tr></table>
                    <table><tr><td class=chan_col><a href="#" class = chan_live_not_free>Paid Channel 1</a></td></tr></table>
                  </div>
                </div>
              </div>
            </div>
            </body></html>
        """.trimIndent()

        val HTML_WITH_DRAW = """
            <html><body>
            <div><span class=comp_head>Test League</span></div>
            <div class=blockfix>
              <div class=fix><div class=fix_text><div class = fLeft>Team A v Team B</div></div></div>
              <div class = fLeft><div>
                <div class="fLeft_time_live dynamic-time" data-timestamp="1700000000">ST: 19:30</div>
                <div class = fLeft_live></div>
              </div></div>
            </div>
            <div class=blockfix>
              <div class=fix><div class=fix_text><div class = fLeft>- German Cup Draw -</div></div></div>
              <div class = fLeft><div>
                <div class="fLeft_time_live dynamic-time" data-timestamp="1700003600">ST: 20:30</div>
                <div class = fLeft_live></div>
              </div></div>
            </div>
            </body></html>
        """.trimIndent()

        val HTML_NO_TIMESTAMP = """
            <html><body>
            <div><span class=comp_head>Test League</span></div>
            <div class=blockfix>
              <div class=fix><div class=fix_text><div class = fLeft>Team A v Team B</div></div></div>
              <div class = fLeft><div><div class = fLeft_live></div></div></div>
            </div>
            </body></html>
        """.trimIndent()

        val HTML_TWO_COMPETITIONS = """
            <html><body>
            <div><span class=comp_head>League One</span></div>
            <div class=blockfix>
              <div class=fix><div class=fix_text><div class = fLeft>Team A v Team B</div></div></div>
              <div class = fLeft><div>
                <div class="fLeft_time_live dynamic-time" data-timestamp="1700000000">ST: 19:30</div>
                <div class = fLeft_live></div>
              </div></div>
            </div>
            <div><span class=comp_head>League Two</span></div>
            <div class=blockfix>
              <div class=fix><div class=fix_text><div class = fLeft>Team C v Team D</div></div></div>
              <div class = fLeft><div>
                <div class="fLeft_time_live dynamic-time" data-timestamp="1700003600">ST: 20:30</div>
                <div class = fLeft_live></div>
              </div></div>
            </div>
            </body></html>
        """.trimIndent()
    }
}

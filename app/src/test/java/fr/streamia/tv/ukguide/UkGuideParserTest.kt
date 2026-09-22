package fr.streamia.tv.ukguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class UkGuideParserTest {
    @Test
    fun parsesChannelRowsAndProgrammeArtworkFromInlineStyle() {
        val html = """
            <html><body>
              <div id="guideRows">
                <div class="tg12-row tg12-channel-row">
                  <div class="tg12-channel">
                    <img class="tg12-logo" src="/channel-logo.php?id=681" alt="BBC One logo">
                    <div class="tg12-channel-text">
                      <div class="tg12-channel-name">BBC One</div>
                      <div class="tg12-channel-kind">Entertainment</div>
                    </div>
                  </div>
                  <div class="tg12-programmes">
                    <a class="tg12-programme has-art" href="/programme.php?id=1" style="left:0px;width:232px;--tg12-art:url(&quot;/media.php?h=abc&quot;);background-image:linear-gradient(90deg,rgba(0,0,0,.9),rgba(0,0,0,.3)),url(&quot;/media.php?h=abc&quot;)"><strong>DIY SOS</strong><span>20:00&#8211;21:00</span></a>
                    <a class="tg12-programme has-art" href="/programme.php?id=2" style="left:240px;width:232px;--tg12-art:url(&quot;/media.php?h=def&quot;)"><strong>Death in Paradise</strong><span>21:00&#8211;22:00</span></a>
                  </div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val schedules = UkGuideParser.parse(html)

        assertEquals(1, schedules.size)
        val schedule = schedules.single()
        assertEquals("BBC One", schedule.channelName)
        assertEquals(2, schedule.programmes.size)

        val first = schedule.programmes[0]
        assertEquals("DIY SOS", first.title)
        assertEquals("20:00", first.startTime)
        assertEquals("21:00", first.endTime)
        assertEquals("https://tvguideuk.com/media.php?h=abc", first.imageUrl)
        assertEquals("BBC One", first.channelName)

        val second = schedule.programmes[1]
        assertEquals("Death in Paradise", second.title)
        assertEquals("https://tvguideuk.com/media.php?h=def", second.imageUrl)
    }

    @Test
    fun selectsCurrentAndNextProgrammeAcrossMidnight() {
        val html = """
            <html><body>
              <div class="tg12-row tg12-channel-row">
                <div class="tg12-channel"><div class="tg12-channel-text"><div class="tg12-channel-name">BBC Four</div></div></div>
                <div class="tg12-programmes">
                  <a class="tg12-programme" style="--tg12-art:url('/media.php?h=1')"><strong>Film de nuit</strong><span>22:40&#8211;00:45</span></a>
                  <a class="tg12-programme" style="--tg12-art:url('/media.php?h=2')"><strong>Weather for the Week Ahead</strong><span>00:45&#8211;00:50</span></a>
                  <a class="tg12-programme" style="--tg12-art:url('/media.php?h=3')"><strong>BBC News</strong><span>00:50&#8211;06:00</span></a>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val schedules = UkGuideParser.parse(html)
        val rows = UkGuideSelector.select(schedules, LocalTime.of(0, 5))

        assertEquals(1, rows.current.size)
        assertEquals("Film de nuit", rows.current.single().title)
        assertEquals(1, rows.next.size)
        assertEquals("Weather for the Week Ahead", rows.next.single().title)
    }

    @Test
    fun skipsRowsWithoutAParsableProgramme() {
        val html = """
            <html><body>
              <div class="tg12-row tg12-channel-row">
                <div class="tg12-channel"><div class="tg12-channel-text"><div class="tg12-channel-name">Empty Channel</div></div></div>
                <div class="tg12-programmes"></div>
              </div>
            </body></html>
        """.trimIndent()

        assertEquals(0, UkGuideParser.parse(html).size)
    }

    @Test
    fun noCurrentProgrammeWhenNowFallsOutsideEveryKnownSlot() {
        val html = """
            <html><body>
              <div class="tg12-row tg12-channel-row">
                <div class="tg12-channel"><div class="tg12-channel-text"><div class="tg12-channel-name">ITV1</div></div></div>
                <div class="tg12-programmes">
                  <a class="tg12-programme" style="--tg12-art:url('/media.php?h=1')"><strong>Emmerdale</strong><span>20:00&#8211;20:30</span></a>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val schedules = UkGuideParser.parse(html)
        val rows = UkGuideSelector.select(schedules, LocalTime.of(10, 0))

        assertEquals(0, rows.current.size)
        assertEquals(0, rows.next.size)
        assertFalse(schedules.single().programmes.single().isOnAirAt(LocalTime.of(10, 0)))
    }

    @Test
    fun parsesGenreCategoriesFromTheNavigationPills() {
        val html = """
            <html><body>
              <a class="cat-pill favourites-pill " data-category-key="__favourites" href="/login.php">Favourites</a>
              <a class="cat-pill active" data-category-key="entertainment" href="/?date=2026-09-22&amp;hour=21&amp;channel_category=Entertainment">Entertainment</a>
              <a class="cat-pill " data-category-key="sports" href="/?date=2026-09-22&amp;hour=21&amp;channel_category=Sports">Sports</a>
              <a class="cat-pill " data-category-key="kids" href="/?date=2026-09-22&amp;hour=21&amp;channel_category=Kids">Kids</a>
            </body></html>
        """.trimIndent()

        assertEquals(listOf("Entertainment", "Sports", "Kids"), UkGuideParser.parseCategories(html))
    }

    @Test
    fun parsesFragmentJsonWithPaginationMetadata() {
        val json = """
            {
              "ok": true,
              "hasMore": true,
              "nextOffset": 24,
              "html": "<div class=\"tg12-row tg12-channel-row\"><div class=\"tg12-channel\"><div class=\"tg12-channel-text\"><div class=\"tg12-channel-name\">Sky Sports Main Event</div></div></div><div class=\"tg12-programmes\"><a class=\"tg12-programme\" style=\"--tg12-art:url('/media.php?h=1')\"><strong>Sky Sports News</strong><span>20:00&#8211;21:00</span></a></div></div>"
            }
        """.trimIndent()

        val page = UkGuideParser.parseFragment(json)

        assertTrue(page.hasMore)
        assertEquals(24, page.nextOffset)
        assertEquals(1, page.schedules.size)
        assertEquals("Sky Sports Main Event", page.schedules.single().channelName)
        assertEquals("Sky Sports News", page.schedules.single().programmes.single().title)
    }

    @Test
    fun fragmentWithoutMorePagesReportsHasMoreFalse() {
        val json = """{"ok":true,"hasMore":false,"nextOffset":-1,"html":""}"""

        val page = UkGuideParser.parseFragment(json)

        assertFalse(page.hasMore)
        assertEquals(0, page.schedules.size)
    }
}

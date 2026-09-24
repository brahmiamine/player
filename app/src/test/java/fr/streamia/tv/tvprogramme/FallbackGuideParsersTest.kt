package fr.streamia.tv.tvprogramme

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class FallbackGuideParsersTest {
    private fun parisMillis(day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()

    @Test
    fun programmeTvNetGivesCurrentAndNextSlots() {
        val html = """
            <div class="gridRow"><div class="gridRow-cards">
              <div class="gridRow-cardsChannel">
                <div class="gridRow-cardsChannelItem"><img src="https://x/tf1.png" alt="TF1"></div>
                <h2 class="gridRow-cardsChannelName"><a class="gridRow-cardsChannelItemLink" href="/c"><span class="sr-only">N°1</span>TF1</a></h2>
              </div>
              <div class="mainBroadcastCard reverse">
                <div class="mainBroadcastCard-imageContent"><img src="data:image/gif;base64,R0" data-src="https://x/64x90/a.jpg" data-srcset="https://x/64x90/focus-point/318,479/a.jpg 64w, https://x/128x180/focus-point/318,479/a.jpg 128w" alt="Les douze coups de midi"></div>
                <p class="mainBroadcastCard-startingHour" data-startinghour="11">11h50</p>
                <h3 class="mainBroadcastCard-title"><a href="/p" title="Les douze coups de midi">Les douze coups de midi</a></h3>
                <p class="mainBroadcastCard-duration"><span class="mainBroadcastCard-durationContent">1h05min</span></p>
              </div>
              <div class="mainBroadcastCard reverse">
                <p class="mainBroadcastCard-startingHour" data-startinghour="12">12h55</p>
                <h3 class="mainBroadcastCard-title"><a href="/p2" title="Petits plats en équilibre">Petits plats en équilibre</a></h3>
                <p class="mainBroadcastCard-duration"><span class="mainBroadcastCard-durationContent">5min</span></p>
              </div>
            </div></div>
        """.trimIndent()

        val slots = ProgrammeTvNetParser.slots(html)
        assertEquals(listOf("TF1", "TF1"), slots.map { it.channelName })
        assertEquals(LocalTime.of(11, 50), slots[0].time)
        assertEquals(65, slots[0].durationMinutes)
        assertEquals("https://x/128x180/focus-point/318,479/a.jpg", slots[0].imageUrl)

        val now = parisMillis(24, 12, 30)
        val schedule = FallbackGuide.schedule(slots, now)
        val current = TvProgrammeNowParser.onAir(schedule, now).single()
        assertEquals("Les douze coups de midi", current.title)
        assertEquals(parisMillis(24, 12, 55), current.endEpochMillis)
        assertEquals("Petits plats en équilibre", TvProgrammeNowParser.onAir(schedule, parisMillis(24, 12, 57)).single().title)

        assertEquals(listOf("11:50", "12:55"), FallbackGuide.tonight(slots).map { it.time })
    }

    @Test
    fun programmeTelevisionOrgParsesOneSlotPerItem() {
        val html = """
            <ul class="now-grid"><li class="tvgrid-broadcast__item"><div class="tvgrid-broadcast__wrapper">
              <div class="tvgrid-channel__wrapper"><span class="xA tvgrid-channel__link">
                <img src="https://x/80.png" alt="Logo de la chaîne France 3">
                <div class="channel_name"><div>Programme</div> France 3 </div>
              </span></div>
              <div class="tvgrid-broadcast__details"><a class="tvgrid-broadcast__wrapper--link" href="/p">
                <div class="tvgrid-broadcast__details-time">12:26</div>
                <div class="tvgrid-broadcast__details-title"><span>ICI 12/13</span></div>
                <div class="tvgrid-broadcast__subdetails"> Direct | 29 mins | Journal </div>
              </a></div>
              <div class="tvgrid-broadcast__poster"><picture><img src="//cdn.example/p.jpg" alt="ICI 12/13" class="tvgrid-broadcast__poster-image"></picture></div>
            </div></li></ul>
        """.trimIndent()

        val slot = ProgrammeTelevisionOrgParser.slots(html).single()
        assertEquals("France 3", slot.channelName)
        assertEquals("ICI 12/13", slot.title)
        assertEquals(29, slot.durationMinutes)
        assertEquals("https://cdn.example/p.jpg", slot.imageUrl)
    }

    @Test
    fun currentSlotStartedBeforeMidnightBelongsToYesterday() {
        val slots = listOf(
            GuideSlot("TF1", LocalTime.of(23, 40), 50, "Film", null),
            GuideSlot("TF1", LocalTime.of(0, 30), 30, "Nuit", null),
        )
        val now = parisMillis(25, 0, 10)
        val schedule = FallbackGuide.schedule(slots, now)

        assertEquals(parisMillis(24, 23, 40), schedule[0].startEpochMillis)
        assertEquals(parisMillis(25, 0, 30), schedule[1].startEpochMillis)
        assertEquals("Film", TvProgrammeNowParser.onAir(schedule, now).single().title)
    }

    @Test
    fun parsesProgrammeTvNetDurations() {
        assertEquals(65, ProgrammeTvNetParser.duration("1h05min"))
        assertEquals(55, ProgrammeTvNetParser.duration(" 55min "))
        assertEquals(120, ProgrammeTvNetParser.duration("2h"))
        assertEquals(null, ProgrammeTvNetParser.duration(""))
    }
}

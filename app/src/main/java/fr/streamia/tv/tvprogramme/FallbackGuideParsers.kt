package fr.streamia.tv.tvprogramme

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Créneau extrait d'un site de secours, quand tv-programme.com ne répond plus (403). Ces sites
 * n'exposent qu'une heure locale de Paris et une durée : les instants absolus sont reconstruits par
 * [FallbackGuide.schedule].
 */
internal data class GuideSlot(
    val channelName: String,
    val time: LocalTime,
    val durationMinutes: Int?,
    val title: String,
    val imageUrl: String?,
)

internal object FallbackGuide {
    private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

    fun tonight(slots: List<GuideSlot>): List<TvProgrammeItem> = byChannel(slots).map { slot ->
        TvProgrammeItem(
            channelName = slot.channelName,
            time = String.format(Locale.ROOT, "%02d:%02d", slot.time.hour, slot.time.minute),
            title = slot.title,
            imageUrl = slot.imageUrl,
        )
    }

    /**
     * Le premier créneau de chaque chaîne est celui en cours : une heure plus d'une heure dans le
     * futur appartient donc à la veille (émission commencée avant minuit). Les créneaux suivants
     * passent au lendemain quand l'heure recule. Fin = début + durée, sinon début du suivant.
     */
    fun schedule(slots: List<GuideSlot>, nowEpochMillis: Long): List<TvProgrammeNowItem> {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowEpochMillis), PARIS)
        return slots.groupBy { it.channelName.lowercase() }.values.flatMap { channelSlots ->
            var previous: ZonedDateTime? = null
            val starts = channelSlots.map { slot ->
                val start = previous?.let { prev ->
                    prev.with(slot.time).let { if (it.isAfter(prev)) it else it.plusDays(1) }
                } ?: now.with(slot.time).let { if (it.isAfter(now.plusHours(1))) it.minusDays(1) else it }
                previous = start
                start
            }
            channelSlots.indices.mapNotNull { index ->
                val slot = channelSlots[index]
                val start = starts[index]
                val end = slot.durationMinutes?.let { start.plusMinutes(it.toLong()) }
                    ?: starts.getOrNull(index + 1)
                    ?: return@mapNotNull null
                TvProgrammeNowItem(
                    channelName = slot.channelName,
                    startEpochMillis = start.toInstant().toEpochMilli(),
                    endEpochMillis = end.toInstant().toEpochMilli(),
                    title = slot.title,
                    imageUrl = slot.imageUrl,
                )
            }
        }.filter { it.endEpochMillis > nowEpochMillis }
    }

    /** Regroupe par chaîne (ordre de première apparition), certaines pages listant 20h puis 22h. */
    private fun byChannel(slots: List<GuideSlot>): List<GuideSlot> =
        slots.groupBy { it.channelName.lowercase() }.values.flatten()

    /** Plus grande image du srcset (lazyload : data-srcset), sinon src/data-src hors placeholder. */
    fun imageUrl(image: Element?): String? {
        image ?: return null
        listOf("data-srcset", "srcset").forEach { attribute ->
            image.attr(attribute).split(SRCSET_SEPARATOR).lastOrNull()?.trim()?.substringBefore(' ')
                ?.takeIf { it.isNotBlank() }
                ?.let { return absolute(it) }
        }
        return listOf("data-src", "src").map { image.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let(::absolute)
    }

    private fun absolute(url: String): String = if (url.startsWith("//")) "https:$url" else url

    // Virgule suivie d'un espace : les URL programme-tv.net contiennent « focus-point/318,479 ».
    private val SRCSET_SEPARATOR = Regex(""",\s+""")
}

/** www.programme-tv.net (Télé-Loisirs) : une `div.gridRow` par chaîne, cartes « en cours » puis « suivant ». */
internal object ProgrammeTvNetParser {
    const val NOW_URL = "https://www.programme-tv.net/programme/en-ce-moment.html"
    const val TONIGHT_URL = "https://www.programme-tv.net/programme/programme-tnt.html"

    fun slots(html: String): List<GuideSlot> =
        Jsoup.parse(html, NOW_URL).select("div.gridRow").flatMap { row ->
            val name = row.selectFirst("a.gridRow-cardsChannelItemLink")?.ownText()?.trim()?.takeIf(String::isNotBlank)
                ?: row.selectFirst(".gridRow-cardsChannelItem img[alt]")?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
                ?: return@flatMap emptyList()
            val channel = CHANNEL_ALIASES[name] ?: name
            row.select(".mainBroadcastCard").mapNotNull { card ->
                val time = CLOCK.find(card.selectFirst(".mainBroadcastCard-startingHour")?.text().orEmpty())
                    ?.destructured?.let { (h, m) -> LocalTime.of(h.toInt() % 24, m.toInt()) }
                    ?: return@mapNotNull null
                val titleLink = card.selectFirst(".mainBroadcastCard-title a")
                val title = titleLink?.attr("title")?.trim()?.takeIf(String::isNotBlank)
                    ?: card.selectFirst(".mainBroadcastCard-title")?.text()?.trim()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                GuideSlot(
                    channelName = channel,
                    time = time,
                    durationMinutes = duration(card.selectFirst(".mainBroadcastCard-durationContent")?.text().orEmpty()),
                    title = title,
                    imageUrl = FallbackGuide.imageUrl(card.selectFirst(".mainBroadcastCard-imageContent img")),
                )
            }
        }

    /** « 1h05min », « 55min », « 2h ». */
    internal fun duration(text: String): Int? {
        val hours = HOURS.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val minutes = MINUTES.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (hours == null && minutes == null) return null
        return (hours ?: 0) * 60 + (minutes ?: 0)
    }

    // Noms longs que le rapprochement par mots ne relierait pas aux chaînes « FR: LCI », « FR: LCP ».
    private val CHANNEL_ALIASES = mapOf("LCI - La Chaîne Info" to "LCI", "La Chaîne parlementaire" to "LCP")
    private val CLOCK = Regex("""(\d{1,2})h(\d{2})""")
    private val HOURS = Regex("""(\d+)\s*h""")
    private val MINUTES = Regex("""(\d+)\s*min""")
}

/** www.programme-television.org (Télé 7 Jours) : un `li.tvgrid-broadcast__item` par créneau. */
internal object ProgrammeTelevisionOrgParser {
    const val NOW_URL = "https://www.programme-television.org/tv/en-ce-moment"
    const val TONIGHT_URL = "https://www.programme-television.org/"

    fun slots(html: String): List<GuideSlot> =
        Jsoup.parse(html, NOW_URL).select("li.tvgrid-broadcast__item").mapNotNull { item ->
            val channel = item.selectFirst(".tvgrid-channel__wrapper img[alt]")?.attr("alt")
                ?.let { CHANNEL_ALT.matchEntire(it.trim())?.groupValues?.get(1)?.trim() }
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val time = CLOCK.find(item.selectFirst(".tvgrid-broadcast__details-time")?.text().orEmpty())
                ?.destructured?.let { (h, m) -> LocalTime.of(h.toInt() % 24, m.toInt()) }
                ?: return@mapNotNull null
            val title = item.selectFirst(".tvgrid-broadcast__details-title")?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            GuideSlot(
                channelName = channel,
                time = time,
                durationMinutes = DURATION.find(item.selectFirst(".tvgrid-broadcast__subdetails")?.text().orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull(),
                title = title,
                imageUrl = FallbackGuide.imageUrl(item.selectFirst("img.tvgrid-broadcast__poster-image")),
            )
        }

    private val CHANNEL_ALT = Regex("""(?i)^Logo\s+(?:de\s+la\s+cha[iî]ne\s+)?(.+)$""")
    private val CLOCK = Regex("""(\d{1,2}):(\d{2})""")
    private val DURATION = Regex("""(\d+)\s*mins?""")
}

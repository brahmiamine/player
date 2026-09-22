package fr.streamia.tv.ukguide

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Locale

/**
 * Parseur de la grille TV britannique publiée sur tvguideuk.com.
 *
 * La page rend une ligne `div.tg12-channel-row` par chaîne (nom dans `.tg12-channel-name`), et
 * pour chaque chaîne une suite de créneaux `a.tg12-programme` : le titre est dans `strong`, l'heure
 * dans `span` au format "HH:MM–HH:MM" (tiret demi-cadratin), et l'illustration est encodée dans le
 * style inline (`--tg12-art:url("...")`) plutôt que dans une balise `<img>`.
 */
object UkGuideParser {
    fun parse(html: String): List<UkChannelSchedule> {
        val document = Jsoup.parse(html, ORIGIN)
        val seenChannels = mutableSetOf<String>()

        return document.select(".tg12-channel-row")
            .asSequence()
            .mapNotNull { row ->
                val channelName = row.selectFirst(".tg12-channel-name")?.text()?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val channelKey = channelName.lowercase(Locale.ROOT)
                if (!seenChannels.add(channelKey)) return@mapNotNull null

                val programmes = row.select(".tg12-programmes > a.tg12-programme")
                    .mapNotNull { anchor -> parseProgramme(anchor, channelName) }
                if (programmes.isEmpty()) return@mapNotNull null

                UkChannelSchedule(channelName = channelName, programmes = programmes)
            }
            .toList()
    }

    private fun parseProgramme(anchor: Element, channelName: String): UkProgrammeItem? {
        val title = anchor.selectFirst("strong")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val timeText = anchor.selectFirst("span")?.text()?.trim().orEmpty()
        val (start, end) = TIME_RANGE.matchEntire(timeText)?.destructured ?: return null

        return UkProgrammeItem(
            channelName = channelName,
            startTime = start,
            endTime = end,
            title = title,
            imageUrl = imageUrl(anchor),
        )
    }

    /** L'illustration n'est jamais dans un `<img>` : elle vient de `--tg12-art:url("...")`. */
    private fun imageUrl(anchor: Element): String? {
        val style = anchor.attr("style")
        val match = ART_URL.find(style) ?: return null
        val raw = match.groupValues.drop(1).firstOrNull(String::isNotBlank)?.trim() ?: return null
        return when {
            raw.startsWith("https://", ignoreCase = true) || raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$ORIGIN$raw"
            else -> "$ORIGIN/$raw"
        }
    }

    private const val ORIGIN = "https://tvguideuk.com"
    private val TIME_RANGE = Regex("""(\d{1,2}:\d{2})[–—-](\d{1,2}:\d{2})""")
    private val ART_URL = Regex("""--tg12-art:url\((?:"([^"]*)"|'([^']*)'|([^)]*))\)""")
}

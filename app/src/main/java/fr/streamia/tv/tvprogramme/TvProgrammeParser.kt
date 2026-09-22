package fr.streamia.tv.tvprogramme

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.Locale

/**
 * Parseur pur de la page "Programme TV ce soir" (accueil de tv-programme.com).
 *
 * La page rend une ligne `.tvp-home-channel-line` par chaîne : le nom complet de la chaîne vient
 * de l'alt de son logo ("Logo de la chaîne TF1" — le titre visible est tronqué, "La Chaîne…"), et
 * chaque programme est une carte `.tvp-home-program-card` (heure, titre, visuel). Si cette mise en
 * page disparaît, on retombe sur l'alt sémantique des vignettes ("Sur TF1 à 21h10 : Titre").
 *
 * Le site double-échappe certaines entités ("L&amp;apos;Equipe") : noms et titres sont donc
 * déséchappés une seconde fois.
 */
object TvProgrammeParser {
    fun parse(html: String): List<TvProgrammeItem> {
        val document = Jsoup.parse(html, BASE_URL)
        val structured = document.select(".tvp-home-channel-line").flatMap(::parseChannelLine)
        val items = structured.ifEmpty { document.select("img[alt]").mapNotNull(::parseImage) }
        return items.distinctBy { item ->
            "${item.channelName.lowercase(Locale.ROOT)}|${item.time}|${item.title.lowercase(Locale.ROOT)}"
        }
    }

    private fun parseChannelLine(line: Element): List<TvProgrammeItem> {
        val cards = line.select(".tvp-home-program-card")
        val channel = channelName(line, cards.firstOrNull()) ?: return emptyList()
        return cards.mapNotNull { card -> parseCard(card, channel) }
    }

    private fun channelName(line: Element, firstCard: Element?): String? {
        line.selectFirst("img.tvp-home-channel-logo[alt]")
            ?.let { CHANNEL_LOGO_ALT.matchEntire(it.attr("alt").trim())?.groupValues?.get(1) }
            ?.let(::clean)
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        line.selectFirst(".tvp-home-channel-name")?.text()?.let(::clean)
            ?.takeIf { it.isNotBlank() && !it.endsWith("…") }
            ?.let { return it }

        return firstCard?.selectFirst("img[alt]")
            ?.let { PROGRAMME_ALT.matchEntire(it.attr("alt").trim())?.groupValues?.get(1) }
            ?.let(::clean)
            ?.takeIf(String::isNotBlank)
    }

    private fun parseCard(card: Element, channel: String): TvProgrammeItem? {
        val altMatch = card.selectFirst("img.tvp-home-program-image[alt]")
            ?.let { PROGRAMME_ALT.matchEntire(it.attr("alt").trim()) }

        val timeElement = card.selectFirst(".tvp-home-program-time time")
        val time = timeElement?.text()?.let(::clockFromText)
            ?: timeElement?.attr("datetime")?.let(::clockFromIso)
            ?: altMatch?.let { formatClock(it.groupValues[2], it.groupValues[3]) }
            ?: return null

        val title = card.selectFirst(".tvp-home-program-title")?.text()?.let(::clean)?.takeIf(String::isNotBlank)
            ?: altMatch?.groupValues?.get(4)?.let(::clean)?.takeIf(String::isNotBlank)
            ?: return null

        val image = card.selectFirst("img.tvp-home-program-image") ?: card.selectFirst("img.tvp-home-program-poster-image")
        return TvProgrammeItem(
            channelName = channel,
            time = time,
            title = title,
            imageUrl = image?.let(::imageUrl),
        )
    }

    private fun parseImage(image: Element): TvProgrammeItem? {
        val alt = image.attr("alt").trim()
        val match = PROGRAMME_ALT.matchEntire(alt) ?: return null
        val (channel, hour, minute, title) = match.destructured
        if (channel.isBlank() || title.isBlank()) return null

        return TvProgrammeItem(
            channelName = clean(channel),
            time = formatClock(hour, minute),
            title = clean(title),
            imageUrl = imageUrl(image),
        )
    }

    private fun clockFromText(text: String): String? =
        CLOCK_TEXT.find(text)?.destructured?.let { (hour, minute) -> formatClock(hour, minute) }

    private fun clockFromIso(datetime: String): String? =
        CLOCK_ISO.find(datetime)?.destructured?.let { (hour, minute) -> formatClock(hour, minute) }

    private fun formatClock(hour: String, minute: String): String = "${hour.padStart(2, '0')}:$minute"

    private fun clean(raw: String): String = Parser.unescapeEntities(raw, false).trim()

    private fun imageUrl(image: Element): String? {
        IMAGE_ATTRIBUTES.forEach { attribute ->
            val raw = image.attr(attribute).trim()
            if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true)) return@forEach
            val firstUrl = if (attribute == "srcset") {
                raw.substringBefore(',').trim().substringBefore(' ').trim()
            } else {
                raw
            }
            if (firstUrl.isBlank()) return@forEach
            return absoluteUrl(image, attribute, firstUrl)
        }
        return null
    }

    private fun absoluteUrl(image: Element, attribute: String, raw: String): String {
        val absolute = image.absUrl(attribute)
        if (absolute.isNotBlank() && attribute != "srcset") return absolute
        return when {
            raw.startsWith("https://", ignoreCase = true) || raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$ORIGIN$raw"
            else -> "$ORIGIN/$raw"
        }
    }

    private const val ORIGIN = "https://tv-programme.com"
    private const val BASE_URL = "$ORIGIN/"
    private val IMAGE_ATTRIBUTES = listOf("data-src", "data-lazy-src", "data-original", "src", "srcset")
    private val PROGRAMME_ALT = Regex(
        """^Sur\s+(.+?)\s+à\s+(\d{1,2})h(\d{2})\s*:\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val CHANNEL_LOGO_ALT = Regex("""^Logo\s+(?:de\s+la\s+cha[iî]ne\s+)?(.+)$""", RegexOption.IGNORE_CASE)
    private val CLOCK_TEXT = Regex("""(?<!\d)(\d{1,2})h(\d{2})(?!\d)""")
    private val CLOCK_ISO = Regex("""T(\d{2}):(\d{2})""")
}

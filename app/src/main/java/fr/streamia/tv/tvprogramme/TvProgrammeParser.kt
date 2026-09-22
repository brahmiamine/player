package fr.streamia.tv.tvprogramme

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Locale

/**
 * Parseur pur de la page "Programme TV ce soir".
 *
 * Le site fournit pour chaque vignette un alt stable de la forme
 * "Sur TF1 à 21h10 : Titre". On s'appuie volontairement sur cette information sémantique plutôt
 * que sur les classes CSS de mise en page, beaucoup plus susceptibles de changer.
 */
object TvProgrammeParser {
    fun parse(html: String): List<TvProgrammeItem> {
        val document = Jsoup.parse(html, BASE_URL)
        return document.select("img[alt]")
            .mapNotNull(::parseImage)
            .distinctBy { item ->
                "${item.channelName.lowercase(Locale.ROOT)}|${item.time}|${item.title.lowercase(Locale.ROOT)}"
            }
    }

    private fun parseImage(image: Element): TvProgrammeItem? {
        val alt = image.attr("alt").trim()
        val match = PROGRAMME_ALT.matchEntire(alt) ?: return null
        val (channel, hour, minute, title) = match.destructured
        if (channel.isBlank() || title.isBlank()) return null

        return TvProgrammeItem(
            channelName = channel.trim(),
            time = "${hour.padStart(2, '0')}:${minute.padStart(2, '0')}",
            title = title.trim(),
            imageUrl = imageUrl(image),
        )
    }

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
}

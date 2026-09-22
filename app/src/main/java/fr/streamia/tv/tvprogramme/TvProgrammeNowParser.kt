package fr.streamia.tv.tvprogramme

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Locale

/**
 * Parseur de la page "Programme TV en ce moment" (tv-programme.com/en-ce-moment).
 *
 * Le site rend désormais une grille sémantique : une ligne `tr.tvp-grille-row` par chaîne, avec
 * un lien de chaîne dans `th.tvp-grille-channel-cell` et, pour chaque créneau, un
 * `article.tvp-grille-item` portant `data-starttime`/`data-endtime` (secondes UTC). Ces bornes
 * absolues permettent de déterminer le programme en cours par simple comparaison d'instants, sans
 * dépendre de l'ordre des créneaux ni d'une heuristique de passage de minuit.
 */
object TvProgrammeNowParser {
    fun parse(
        html: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): List<TvProgrammeNowItem> {
        val document = Jsoup.parse(html, ORIGIN)
        val seenChannels = mutableSetOf<String>()

        return document.select("tr.tvp-grille-row")
            .asSequence()
            .mapNotNull { row ->
                val channelName = channelName(row) ?: return@mapNotNull null
                val channelKey = channelName.lowercase(Locale.ROOT)
                if (!seenChannels.add(channelKey)) return@mapNotNull null

                val current = row.select("article.tvp-grille-item")
                    .asSequence()
                    .mapNotNull(::parseProgramme)
                    .firstOrNull { it.isOnAirAt(nowEpochMillis) }
                    ?: return@mapNotNull null

                TvProgrammeNowItem(
                    channelName = channelName,
                    startEpochMillis = current.startEpochMillis,
                    endEpochMillis = current.endEpochMillis,
                    title = current.title,
                    imageUrl = current.imageUrl,
                )
            }
            .toList()
    }

    private fun channelName(row: Element): String? {
        val link = row.selectFirst("a.tvp-grille-channel-link") ?: return null
        CHANNEL_ARIA_LABEL.matchEntire(link.attr("aria-label").trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        val alt = link.selectFirst("img")?.attr("alt").orEmpty()
        return CHANNEL_LOGO_ALT.matchEntire(alt.trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun parseProgramme(article: Element): ProgrammeCandidate? {
        val start = article.attr("data-starttime").toLongOrNull()?.times(1000L) ?: return null
        val end = article.attr("data-endtime").toLongOrNull()?.times(1000L) ?: return null
        if (end <= start) return null

        val title = article.selectFirst("h2.tvp-grille-sr-only")?.text()?.trim()
            ?.takeIf(String::isNotBlank)
            ?: article.selectFirst(".tvp-grille-item-link")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: article.attr("data-tooltip-title").trim().takeIf(String::isNotBlank)
            ?: return null

        return ProgrammeCandidate(
            startEpochMillis = start,
            endEpochMillis = end,
            title = title,
            imageUrl = article.selectFirst("img.tvp-grille-item-image")?.let(::imageUrl),
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
            val absolute = image.absUrl(attribute)
            return when {
                absolute.isNotBlank() && attribute != "srcset" -> absolute
                firstUrl.startsWith("https://", ignoreCase = true) ||
                    firstUrl.startsWith("http://", ignoreCase = true) -> firstUrl
                firstUrl.startsWith("//") -> "https:$firstUrl"
                firstUrl.startsWith("/") -> "$ORIGIN$firstUrl"
                else -> "$ORIGIN/$firstUrl"
            }
        }
        return null
    }

    private data class ProgrammeCandidate(
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val title: String,
        val imageUrl: String?,
    ) {
        fun isOnAirAt(nowEpochMillis: Long): Boolean =
            nowEpochMillis >= startEpochMillis && nowEpochMillis < endEpochMillis
    }

    private const val ORIGIN = "https://tv-programme.com"
    private val CHANNEL_ARIA_LABEL = Regex("""(?i)^Voir la cha[iî]ne (.+)$""")
    private val CHANNEL_LOGO_ALT = Regex("""(?i)^Logo (.+)$""")
    private val IMAGE_ATTRIBUTES = listOf("data-src", "data-lazy-src", "data-original", "src", "srcset")
}

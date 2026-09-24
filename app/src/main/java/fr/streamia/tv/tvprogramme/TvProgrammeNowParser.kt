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
    ): List<TvProgrammeNowItem> = onAir(parseSchedule(html, nowEpochMillis), nowEpochMillis)

    /**
     * Tous les créneaux pas encore terminés de chaque chaîne (pas seulement celui en cours) : mis en
     * cache, ils permettent de recalculer « en ce moment » localement pendant des heures sans
     * re-télécharger la page (le site renvoie 403 quand on l'interroge trop souvent).
     */
    fun parseSchedule(
        html: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): List<TvProgrammeNowItem> {
        val document = Jsoup.parse(html, ORIGIN)
        val seenChannels = mutableSetOf<String>()

        return document.select("tr.tvp-grille-row").flatMap { row ->
            val channelName = channelName(row) ?: return@flatMap emptyList()
            if (!seenChannels.add(channelName.lowercase(Locale.ROOT))) return@flatMap emptyList()
            row.select("article.tvp-grille-item")
                .mapNotNull(::parseProgramme)
                .filter { it.endEpochMillis > nowEpochMillis }
                .map { slot ->
                    TvProgrammeNowItem(
                        channelName = channelName,
                        startEpochMillis = slot.startEpochMillis,
                        endEpochMillis = slot.endEpochMillis,
                        title = slot.title,
                        imageUrl = slot.imageUrl,
                    )
                }
        }
    }

    /** Programme en cours de chaque chaîne, dans l'ordre de la grille. */
    fun onAir(schedule: List<TvProgrammeNowItem>, nowEpochMillis: Long): List<TvProgrammeNowItem> =
        schedule.filter { it.isOnAirAt(nowEpochMillis) }.distinctBy { it.channelName.lowercase(Locale.ROOT) }

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
    )

    private const val ORIGIN = "https://tv-programme.com"
    private val CHANNEL_ARIA_LABEL = Regex("""(?i)^Voir la cha[iî]ne (.+)$""")
    private val CHANNEL_LOGO_ALT = Regex("""(?i)^Logo (.+)$""")
    private val IMAGE_ATTRIBUTES = listOf("data-src", "data-lazy-src", "data-original", "src", "srcset")
}

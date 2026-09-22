package fr.streamia.tv.tvprogramme

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Parseur de la page "Programme TV en ce moment".
 *
 * Le site présente une ligne par chaîne, repérée par son lien "N° X", puis plusieurs programmes
 * ordonnés avec leur heure de début. Le programme courant est le dernier dont l'heure de début est
 * antérieure à l'heure de Paris ; le programme suivant donne l'heure de fin et la progression.
 *
 * Le parseur évite les classes CSS : il s'appuie sur la numérotation, les heures et les liens de
 * programmes afin de mieux résister aux changements de mise en page.
 */
object TvProgrammeNowParser {
    fun parse(
        html: String,
        now: LocalTime = LocalTime.now(PARIS_ZONE),
    ): List<TvProgrammeNowItem> {
        val document = Jsoup.parse(html, NOW_URL)
        val seenChannels = mutableSetOf<String>()

        return document.select("a[href]")
            .asSequence()
            .filter { CHANNEL_NUMBER.containsMatchIn(it.text().trim()) }
            .mapNotNull { channelAnchor ->
                val container = findChannelContainer(channelAnchor) ?: return@mapNotNull null
                val channelName = channelName(channelAnchor, container) ?: return@mapNotNull null
                val channelKey = channelName.lowercase(Locale.ROOT)
                if (!seenChannels.add(channelKey)) return@mapNotNull null

                val programmes = extractProgrammes(container, channelAnchor)
                currentProgramme(channelName, programmes, now)
            }
            .toList()
    }

    private fun findChannelContainer(anchor: Element): Element? {
        var node = anchor.parent()
        repeat(MAX_ANCESTOR_DEPTH) {
            val current = node ?: return null
            val numberedChannels = current.select("a[href]")
                .count { CHANNEL_NUMBER.containsMatchIn(it.text().trim()) }
            val timeCount = TIME.findAll(current.text()).count()
            if (numberedChannels == 1 && timeCount >= 2) return current
            if (current.tagName().equals("body", ignoreCase = true)) return null
            node = current.parent()
        }
        return null
    }

    private fun extractProgrammes(
        container: Element,
        channelAnchor: Element,
    ): List<ProgrammeCandidate> {
        val seen = mutableSetOf<String>()
        return container.select("a[href]")
            .asSequence()
            .filterNot { it == channelAnchor }
            .mapNotNull { link ->
                val rawTitle = link.text().trim()
                if (
                    rawTitle.isBlank() ||
                    CHANNEL_NUMBER.containsMatchIn(rawTitle) ||
                    rawTitle.startsWith("Voir ", ignoreCase = true)
                ) {
                    return@mapNotNull null
                }

                val block = findProgrammeBlock(link, container) ?: return@mapNotNull null
                val start = TIME.find(block.text())?.destructured?.let { (hour, minute) ->
                    "${hour.padStart(2, '0')}:${minute.padStart(2, '0')}"
                } ?: return@mapNotNull null
                val title = cleanProgrammeTitle(rawTitle)
                if (title.isBlank()) return@mapNotNull null

                val key = "$start|${title.lowercase(Locale.ROOT)}"
                if (!seen.add(key)) return@mapNotNull null

                ProgrammeCandidate(
                    startTime = start,
                    title = title,
                    imageUrl = block.select("img[alt], img[src], img[data-src]")
                        .firstOrNull()
                        ?.let(::imageUrl),
                )
            }
            .toList()
    }

    private fun findProgrammeBlock(link: Element, container: Element): Element? {
        var node = link.parent()
        repeat(MAX_PROGRAMME_ANCESTOR_DEPTH) {
            val current = node ?: return null
            val text = current.text()
            val times = TIME.findAll(text).toList()
            if (times.size == 1 && text.length <= MAX_PROGRAMME_TEXT_LENGTH) return current
            if (current == container) return null
            node = current.parent()
        }
        return null
    }

    private fun currentProgramme(
        channelName: String,
        programmes: List<ProgrammeCandidate>,
        now: LocalTime,
    ): TvProgrammeNowItem? {
        if (programmes.isEmpty()) return null
        val nowMinutes = now.hour * 60 + now.minute

        val currentIndex = programmes.indices
            .map { index -> index to signedMinuteDelta(programmes[index].startTime, nowMinutes) }
            .filter { (_, delta) -> delta <= 0 }
            .maxByOrNull { (_, delta) -> delta }
            ?.first
            ?: return null

        val current = programmes[currentIndex]
        val next = programmes.drop(currentIndex + 1).firstOrNull()
        val endTime = next?.startTime?.takeIf { candidateEnd ->
            val duration = forwardMinutes(current.startTime, candidateEnd)
            duration in 1..MAX_REASONABLE_PROGRAMME_MINUTES
        }

        return TvProgrammeNowItem(
            channelName = channelName,
            startTime = current.startTime,
            endTime = endTime,
            title = current.title,
            imageUrl = current.imageUrl,
        )
    }

    private fun signedMinuteDelta(startTime: String, nowMinutes: Int): Int {
        val start = startTime.toMinutes()
        return ((start - nowMinutes + HALF_DAY_MINUTES + DAY_MINUTES) % DAY_MINUTES) - HALF_DAY_MINUTES
    }

    private fun forwardMinutes(startTime: String, endTime: String): Int =
        (endTime.toMinutes() - startTime.toMinutes() + DAY_MINUTES) % DAY_MINUTES

    private fun String.toMinutes(): Int {
        val (hour, minute) = split(':').map(String::toInt)
        return hour * 60 + minute
    }

    private fun channelName(anchor: Element, container: Element): String? {
        val semanticAlt = container.select("img[alt]")
            .asSequence()
            .map { it.attr("alt").trim() }
            .mapNotNull { CHANNEL_LOGO_ALT.matchEntire(it)?.groupValues?.getOrNull(1)?.trim() }
            .firstOrNull()
            ?.removeSuffix(" programme")
            ?.trim()
        if (!semanticAlt.isNullOrBlank()) return semanticAlt

        listOf(anchor.attr("title"), anchor.attr("aria-label"))
            .firstOrNull { it.isNotBlank() && !CHANNEL_NUMBER.containsMatchIn(it) }
            ?.let { return it.trim() }

        val rawHref = anchor.attr("href").substringBefore('?').substringBefore('#').trimEnd('/')
        val slug = rawHref.substringAfterLast('/').takeIf(String::isNotBlank) ?: return null
        return URLDecoder.decode(slug, StandardCharsets.UTF_8.name())
            .replace('-', ' ')
            .replace('_', ' ')
            .trim()
            .takeIf(String::isNotBlank)
    }

    private fun cleanProgrammeTitle(raw: String): String =
        raw.replace(TRAILING_DIRECT, "")
            .replace(TRAILING_ELLIPSIS, "")
            .trim()

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
        val startTime: String,
        val title: String,
        val imageUrl: String?,
    )

    private const val ORIGIN = "https://tv-programme.com"
    private const val NOW_URL = "$ORIGIN/en-ce-moment"
    private val PARIS_ZONE = ZoneId.of("Europe/Paris")
    private const val MAX_ANCESTOR_DEPTH = 9
    private const val MAX_PROGRAMME_ANCESTOR_DEPTH = 6
    private const val MAX_PROGRAMME_TEXT_LENGTH = 420
    private const val DAY_MINUTES = 24 * 60
    private const val HALF_DAY_MINUTES = 12 * 60
    private const val MAX_REASONABLE_PROGRAMME_MINUTES = 8 * 60
    private val CHANNEL_NUMBER = Regex("""\bN\s*[°ºo]?\s*\d+\b""", RegexOption.IGNORE_CASE)
    private val CHANNEL_LOGO_ALT = Regex("""(?:logo\s+(?:de\s+la\s+cha[iî]ne\s+)?)?(.+?)(?:\s+programme)?$""", RegexOption.IGNORE_CASE)
    private val TIME = Regex("""\b(\d{1,2})h(\d{2})\b""")
    private val TRAILING_DIRECT = Regex("""\s+Direct\s*$""", RegexOption.IGNORE_CASE)
    private val TRAILING_ELLIPSIS = Regex("""\s*(?:…|\.\.\.)\s*$""")
    private val IMAGE_ATTRIBUTES = listOf("data-src", "data-lazy-src", "data-original", "src", "srcset")
}

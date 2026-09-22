package fr.streamia.tv.beinsports

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Locale

/**
 * Parse la grille TV MENA de beIN SPORTS sans dépendre de classes CSS précises.
 *
 * La page expose une section par chaîne (beIN SPORTS 1, XTRA, EN, FR, MAX, 4K...) et chaque
 * programme contient une plage horaire HH:mm HH:mm. Le parseur cherche ces éléments sémantiques,
 * puis remonte vers le plus petit bloc contenant aussi le titre du programme.
 */
object BeinSportsTvGuideParser {
    fun parse(html: String): List<BeinChannelSchedule> {
        val document = Jsoup.parse(html, GUIDE_URL)
        val seenChannels = mutableSetOf<String>()

        return document.getAllElements()
            .asSequence()
            .mapNotNull { element ->
                val name = element.ownText().trim().takeIf(CHANNEL_NAME::matches) ?: return@mapNotNull null
                val key = canonicalChannelKey(name)
                if (!seenChannels.add(key)) return@mapNotNull null

                val container = findChannelContainer(element) ?: return@mapNotNull null
                val programmes = extractProgrammes(container, name)
                if (programmes.isEmpty()) return@mapNotNull null
                BeinChannelSchedule(channelName = name, programmes = programmes)
            }
            .toList()
    }

    private fun findChannelContainer(channelElement: Element): Element? {
        var node: Element? = channelElement.parent()
        repeat(MAX_CHANNEL_ANCESTORS) {
            val current = node ?: return null
            val channelNames = current.getAllElements()
                .count { CHANNEL_NAME.matches(it.ownText().trim()) }
            val timePairs = TIME_PAIR.findAll(current.text()).count()
            if (channelNames == 1 && timePairs >= 1) return current
            if (current.tagName().equals("body", ignoreCase = true)) return null
            node = current.parent()
        }
        return null
    }

    private fun extractProgrammes(
        container: Element,
        channelName: String,
    ): List<BeinProgrammeItem> {
        val seen = mutableSetOf<String>()

        return container.getAllElements()
            .asSequence()
            .filter { element ->
                TIME_PAIR.containsMatchIn(element.text()) &&
                    element.children().none { child -> TIME_PAIR.containsMatchIn(child.text()) }
            }
            .mapNotNull { timeLeaf ->
                val block = findProgrammeBlock(timeLeaf, container, channelName) ?: return@mapNotNull null
                val time = TIME_PAIR.find(block.text()) ?: return@mapNotNull null
                val start = normalizeTime(time.groupValues[1], time.groupValues[2])
                val end = normalizeTime(time.groupValues[3], time.groupValues[4])
                val descriptor = descriptor(block, channelName, time.value)
                if (descriptor.title.isBlank()) return@mapNotNull null

                val dedupeKey = "$start|$end|${descriptor.title.lowercase(Locale.ROOT)}"
                if (!seen.add(dedupeKey)) return@mapNotNull null

                BeinProgrammeItem(
                    channelName = channelName,
                    category = descriptor.category,
                    title = descriptor.title,
                    startTime = start,
                    endTime = end,
                    isLive = LIVE_WORD.containsMatchIn(block.text()),
                    imageUrl = programmeImageUrl(block),
                )
            }
            .toList()
    }

    private fun findProgrammeBlock(
        timeLeaf: Element,
        channelContainer: Element,
        channelName: String,
    ): Element? {
        var node: Element? = timeLeaf
        repeat(MAX_PROGRAMME_ANCESTORS) {
            val current = node ?: return null
            if (current == channelContainer) return null

            val text = current.text().trim()
            val pairCount = TIME_PAIR.findAll(text).count()
            val meaningful = text
                .replace(TIME_PAIR, " ")
                .replace(LIVE_WORD, " ")
                .replace(channelName, " ", ignoreCase = true)
                .replace(MULTI_SPACE, " ")
                .trim()

            if (pairCount == 1 && meaningful.any(Char::isLetter)) return current
            node = current.parent()
        }
        return null
    }

    private fun descriptor(
        block: Element,
        channelName: String,
        timeText: String,
    ): ProgrammeDescriptor {
        val lines = block.wholeText()
            .lines()
            .map { line ->
                line.replace(timeText, " ")
                    .replace(LIVE_WORD, " ")
                    .replace(channelName, " ", ignoreCase = true)
                    .replace(MULTI_SPACE, " ")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .filterNot { CHANNEL_NAME.matches(it) }
            .distinct()

        if (lines.isNotEmpty()) {
            val title = lines.maxByOrNull(String::length).orEmpty()
            val category = lines.firstOrNull { it != title }
            return ProgrammeDescriptor(title = title, category = category)
        }

        val fallback = block.text()
            .replace(timeText, " ")
            .replace(LIVE_WORD, " ")
            .replace(channelName, " ", ignoreCase = true)
            .replace(MULTI_SPACE, " ")
            .trim()
        return ProgrammeDescriptor(title = fallback, category = null)
    }

    private fun programmeImageUrl(block: Element): String? =
        block.select("img[data-src], img[data-lazy-src], img[src]")
            .asSequence()
            .mapNotNull { image ->
                listOf("data-src", "data-lazy-src", "src")
                    .asSequence()
                    .map { attribute -> image.attr(attribute).trim() }
                    .firstOrNull { raw -> raw.isNotBlank() && !raw.startsWith("data:", ignoreCase = true) }
                    ?.let(::absoluteUrl)
            }
            .firstOrNull()

    private fun absoluteUrl(raw: String): String = when {
        raw.startsWith("https://", ignoreCase = true) || raw.startsWith("http://", ignoreCase = true) -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$ORIGIN$raw"
        else -> "$ORIGIN/$raw"
    }

    private fun normalizeTime(hour: String, minute: String): String =
        "${hour.padStart(2, '0')}:${minute.padStart(2, '0')}"

    private fun canonicalChannelKey(raw: String): String =
        raw.lowercase(Locale.ROOT).replace(MULTI_SPACE, " ").trim()

    private data class ProgrammeDescriptor(
        val title: String,
        val category: String?,
    )

    private const val ORIGIN = "https://www.beinsports.com"
    private const val GUIDE_URL = "$ORIGIN/en-mena/tv-guide"
    private const val MAX_CHANNEL_ANCESTORS = 10
    private const val MAX_PROGRAMME_ANCESTORS = 7

    private val CHANNEL_NAME = Regex(
        """(?i)^beIN(?:\s+SPORTS)?(?:\s+(?:NEWS|XTRA|EN|FR|MAX))?(?:\s+\d+)?(?:\s+4K)?$""",
    )
    private val TIME_PAIR = Regex("""\b(\d{1,2}):(\d{2})\s+(\d{1,2}):(\d{2})\b""")
    private val LIVE_WORD = Regex("""(?i)\bLive\b""")
    private val MULTI_SPACE = Regex("""\s+""")
}

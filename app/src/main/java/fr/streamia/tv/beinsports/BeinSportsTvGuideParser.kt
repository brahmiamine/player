package fr.streamia.tv.beinsports

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

/** Chaîne exposée par l'EPG beIN SPORTS (`/api/opta/tv-channel`). */
data class BeinGuideChannel(
    val id: String,
    val name: String,
)

/**
 * Parse la grille TV MENA de beIN SPORTS.
 *
 * La page https://www.beinsports.com/en-mena/tv-guide ne contient pas la grille dans son HTML :
 * elle est chargée côté client depuis deux endpoints JSON du site :
 * - `/api/opta/tv-channel?region=en-mena` : liste des chaînes (id + nom) ;
 * - `/api/opta/tv-event?channelIds=…&endAfter=…&startBefore=…` : programmes, avec des bornes
 *   `startDate`/`endDate` en UTC (ISO-8601), le titre, la catégorie et l'indicateur `live`.
 */
object BeinSportsTvGuideParser {
    /**
     * Toutes les chaînes beIN de la grille MENA, déclinaisons AFC/NBA/4K HDR comprises : leur
     * identité reste distincte des chaînes principales côté [BeinSportsChannelMatcher].
     */
    fun parseChannels(json: String): List<BeinGuideChannel> {
        val seenNames = mutableSetOf<String>()
        return rows(json)
            .mapNotNull { row ->
                val id = row.optString("id").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val name = row.optString("name").replace(MULTI_SPACE, " ").trim()
                if (!name.startsWith("bein", ignoreCase = true)) return@mapNotNull null
                if (!seenNames.add(name.lowercase(Locale.ROOT))) return@mapNotNull null
                BeinGuideChannel(id = id, name = name)
            }
            .sortedWith(CHANNEL_ORDER)
            .toList()
    }

    /** Regroupe les programmes par chaîne, dans l'ordre de [channels], triés par heure de début. */
    fun parseEvents(
        json: String,
        channels: List<BeinGuideChannel>,
    ): List<BeinChannelSchedule> {
        val channelsById = channels.associateBy { it.id.uppercase(Locale.ROOT) }
        val programmesByChannel = linkedMapOf<String, MutableList<BeinProgrammeItem>>()
        val seen = mutableSetOf<String>()

        rows(json).forEach { row ->
            val channelId = row.optString("channelId").ifBlank {
                row.optJSONObject("channel")?.optString("id").orEmpty()
            }.uppercase(Locale.ROOT)
            val channel = channelsById[channelId] ?: return@forEach

            val start = parseInstant(row.optString("startDate")) ?: return@forEach
            val end = parseInstant(row.optString("endDate")) ?: return@forEach
            if (end <= start) return@forEach

            val title = row.optString("title").ifBlank { row.localized("Title") }
                .replace(MULTI_SPACE, " ")
                .trim()
            if (title.isEmpty() || isChannelFiller(title)) return@forEach

            val dedupeKey = "$channelId|$start|${title.lowercase(Locale.ROOT)}"
            if (!seen.add(dedupeKey)) return@forEach

            val category = row.optString("category").ifBlank { row.localized("Category") }
                .replace(MULTI_SPACE, " ")
                .trim()
                .takeIf(String::isNotEmpty)

            programmesByChannel.getOrPut(channel.id) { mutableListOf() } += BeinProgrammeItem(
                channelName = channel.name,
                category = category,
                title = title,
                startEpochMillis = start,
                endEpochMillis = end,
                isLive = row.optBoolean("live", false) ||
                    row.optJSONObject("data")?.optString("Live").equals("true", ignoreCase = true),
                imageUrl = row.optJSONObject("data")?.optString("ImageURL")?.let(::absoluteUrl),
            )
        }

        return channels.mapNotNull { channel ->
            val programmes = programmesByChannel[channel.id]?.sortedBy { it.startEpochMillis }
            if (programmes.isNullOrEmpty()) null else BeinChannelSchedule(channel.name, programmes)
        }
    }

    private fun rows(json: String): Sequence<JSONObject> {
        val trimmed = json.trim()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).optJSONArray("rows") ?: JSONArray()
        }
        return (0 until array.length()).asSequence().mapNotNull(array::optJSONObject)
    }

    private fun JSONObject.localized(key: String): String =
        optJSONObject("data")?.optJSONObject(key)?.optString("English").orEmpty()

    private fun parseInstant(raw: String): Long? =
        raw.trim().takeIf(String::isNotEmpty)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /**
     * Créneaux de remplissage : titre réduit au nom de la chaîne (« beIN Sports MAX ») ou bandeau
     * promotionnel (« beIN SPORTS XTRA For Live And Exclusive Coverage… »).
     */
    private fun isChannelFiller(title: String): Boolean =
        CHANNEL_NAME.matches(title) || CHANNEL_PROMO.containsMatchIn(title)

    private fun absoluteUrl(raw: String): String? {
        val value = raw.trim()
        return when {
            value.isEmpty() -> null
            value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$ORIGIN$value"
            else -> null
        }
    }

    private fun familyRank(name: String): Int {
        val upper = name.uppercase(Locale.ROOT)
        return when {
            " EN " in "$upper " -> 1
            " FR " in "$upper " -> 2
            "XTRA" in upper -> 3
            "MAX" in upper -> 4
            "AFC" in upper -> 5
            "NBA" in upper -> 6
            "4K" in upper -> 7
            "NEWS" in upper -> 8
            DIGITS.containsMatchIn(upper) -> 0
            else -> 9
        }
    }

    private val CHANNEL_ORDER: Comparator<BeinGuideChannel> =
        compareBy<BeinGuideChannel> { familyRank(it.name) }
            .thenBy { DIGITS.find(it.name)?.value?.toIntOrNull() ?: 0 }
            .thenBy { it.name }

    private const val ORIGIN = "https://www.beinsports.com"

    private val CHANNEL_NAME = Regex(
        """(?i)^beIN(?:\s+SPORTS)?(?:\s+(?:NEWS|XTRA|EN|FR|MAX|AFC|NBA))?(?:\s+\d+)?(?:\s+AFC)?(?:\s+4K(?:\s+HDR)?)?$""",
    )
    private val CHANNEL_PROMO = Regex("""(?i)^beIN(?:\s+SPORTS)?(?:\s+(?:XTRA|MAX))?\s+for\s+live\b""")
    private val DIGITS = Regex("""\d+""")
    private val MULTI_SPACE = Regex("""\s+""")
}

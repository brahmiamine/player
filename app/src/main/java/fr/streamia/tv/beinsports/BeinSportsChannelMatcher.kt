package fr.streamia.tv.beinsports

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.LiveChannelPrefix
import fr.streamia.tv.domain.MediaEntry
import java.text.Normalizer
import java.util.Locale

/**
 * Résout les noms de chaînes de la grille MENA vers les chaînes Live "AR" de la playlist
 * (catégorie ou nom de chaîne commençant par "AR", voir [LiveChannelPrefix]).
 *
 * Le matching utilise une identité canonique (SPORTS 1, EN 1, FR 1, XTRA 1, MAX 1, 1 AFC, NBA,
 * NEWS, 4K, 4K HDR...) puis préfère la meilleure variante technique disponible :
 * 4K > UHD > FHD > HD > SD.
 */
class BeinSportsChannelMatcher {
    fun resolve(
        programmes: List<BeinProgrammeItem>,
        catalog: Catalog,
    ): List<ResolvedBeinProgrammeItem> {
        if (programmes.isEmpty()) return emptyList()

        val candidates = LiveChannelPrefix.AR.channels(catalog)
            .asSequence()
            .mapNotNull { channel ->
                val identity = identityOf(channel.displayName) ?: identityOf(channel.name) ?: return@mapNotNull null
                IndexedChannel(
                    channel = channel,
                    identity = identity,
                    quality = maxOf(qualityRank(channel.displayName), qualityRank(channel.name)),
                )
            }
            .toList()

        if (candidates.isEmpty()) return emptyList()

        val seenChannels = mutableSetOf<String>()
        return programmes.mapNotNull { programme ->
            val identity = identityOf(programme.channelName) ?: return@mapNotNull null
            val best = candidates.asSequence()
                .filter { it.identity == identity }
                .sortedWith(
                    compareByDescending<IndexedChannel> { it.quality }
                        .thenBy { it.channel.number },
                )
                .firstOrNull()
                ?: return@mapNotNull null

            if (!seenChannels.add(best.channel.key)) return@mapNotNull null
            ResolvedBeinProgrammeItem(programme = programme, channel = best.channel)
        }
    }

    internal fun identityOf(raw: String): String? {
        val normalized = normalize(raw)
        if ("bein" !in normalized.tokens) return null

        val number = normalized.tokens.firstOrNull { token -> token.all(Char::isDigit) }
        return when {
            "news" in normalized.tokens -> "news"
            "nba" in normalized.tokens -> "nba"
            "afc" in normalized.tokens -> number?.let { "afc:$it" } ?: "afc"
            "xtra" in normalized.tokens || "extra" in normalized.tokens -> number?.let { "xtra:$it" }
            "max" in normalized.tokens -> number?.let { "max:$it" }
            "en" in normalized.tokens || "english" in normalized.tokens -> number?.let { "en:$it" }
            "fr" in normalized.tokens || "french" in normalized.tokens -> number?.let { "fr:$it" }
            number != null -> "sports:$number"
            "hdr" in normalized.tokens -> "4k-hdr"
            "4k" in normalized.tokens || "2160p" in normalized.tokens -> "4k"
            "sports" in normalized.tokens -> "sports"
            else -> null
        }
    }

    private fun normalize(raw: String): NormalizedName {
        val withoutProviderPrefix = LiveChannelPrefix.AR.strip(raw).substringAfterLast('|')
        val ascii = Normalizer.normalize(withoutProviderPrefix, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace("bein sports hd", "bein sports")
            .replace(NON_ALNUM, " ")
            .replace(MULTI_SPACE, " ")
            .trim()

        val tokens = ascii.split(' ')
            .filter(String::isNotBlank)
            .filterNot { it in DECORATION_TOKENS || it in QUALITY_TOKENS }
            .toSet()
        return NormalizedName(tokens)
    }

    private fun qualityRank(raw: String): Int {
        val value = raw.lowercase(Locale.ROOT)
        return when {
            Regex("""\b4k\b|\b2160p?\b""").containsMatchIn(value) -> 5
            Regex("""\buhd\b""").containsMatchIn(value) -> 4
            Regex("""\bfhd\b|\b1080p?\b""").containsMatchIn(value) -> 3
            Regex("""\bhd\b|\b720p?\b""").containsMatchIn(value) -> 2
            Regex("""\bsd\b""").containsMatchIn(value) -> 1
            else -> 0
        }
    }

    private data class NormalizedName(
        val tokens: Set<String>,
    )

    private data class IndexedChannel(
        val channel: MediaEntry,
        val identity: String,
        val quality: Int,
    )

    private companion object {
        val COMBINING_MARKS = Regex("""\p{Mn}+""")
        val NON_ALNUM = Regex("""[^a-z0-9]+""")
        val MULTI_SPACE = Regex("""\s+""")
        val QUALITY_TOKENS = setOf(
            "uhd", "fhd", "hd", "sd", "1080", "1080p", "720", "720p",
        )
        val DECORATION_TOKENS = setOf(
            "ar", "arabic", "mena", "live", "vip", "premium", "channel", "tv",
        )
    }
}

package fr.streamia.tv.beinsports

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.LiveChannelPrefix
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
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
        val bestByIdentity = bestChannelByIdentity(catalog)
        if (bestByIdentity.isEmpty()) return emptyList()

        val seenChannels = mutableSetOf<String>()
        return programmes.mapNotNull { programme ->
            val identity = identityOf(programme.channelName) ?: return@mapNotNull null
            val best = bestByIdentity[identity] ?: return@mapNotNull null
            if (!seenChannels.add(best.key)) return@mapNotNull null
            ResolvedBeinProgrammeItem(programme = programme, channel = best)
        }
    }

    /**
     * Meilleure chaîne par identité beIN (qualité, puis numéro, puis ordre de la playlist), calculée
     * une fois par liste de chaînes Direct : l'ancienne version renormalisait toutes les chaînes AR
     * (et compilait 5 expressions régulières par chaîne) à chaque rafraîchissement de l'accueil,
     * puis parcourait toutes les candidates pour chaque programme.
     */
    private fun bestChannelByIdentity(catalog: Catalog): Map<String, MediaEntry> {
        val live = catalog.entriesFor(MediaType.Live)
        val categories = catalog.categoriesFor(MediaType.Live)
        cachedIndex?.let { cached -> if (cached.live === live && cached.categories == categories) return cached.best }
        val candidates = LiveChannelPrefix.AR.channels(catalog).mapNotNull { channel ->
            val identity = identityOf(channel.displayName) ?: identityOf(channel.name) ?: return@mapNotNull null
            IndexedChannel(channel, identity, maxOf(qualityRank(channel.displayName), qualityRank(channel.name)))
        }
        val best = candidates.groupBy(IndexedChannel::identity).mapValues { (_, group) ->
            group.sortedWith(compareByDescending<IndexedChannel> { it.quality }.thenBy { it.channel.number }).first().channel
        }
        cachedIndex = CachedIndex(live, categories, best)
        return best
    }

    private class CachedIndex(val live: List<MediaEntry>, val categories: List<MediaCategory>, val best: Map<String, MediaEntry>)
    @Volatile private var cachedIndex: CachedIndex? = null

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
            QUALITY_4K.containsMatchIn(value) -> 5
            QUALITY_UHD.containsMatchIn(value) -> 4
            QUALITY_FHD.containsMatchIn(value) -> 3
            QUALITY_HD.containsMatchIn(value) -> 2
            QUALITY_SD.containsMatchIn(value) -> 1
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
        val QUALITY_4K = Regex("""\b4k\b|\b2160p?\b""")
        val QUALITY_UHD = Regex("""\buhd\b""")
        val QUALITY_FHD = Regex("""\bfhd\b|\b1080p?\b""")
        val QUALITY_HD = Regex("""\bhd\b|\b720p?\b""")
        val QUALITY_SD = Regex("""\bsd\b""")
        val QUALITY_TOKENS = setOf(
            "uhd", "fhd", "hd", "sd", "1080", "1080p", "720", "720p",
        )
        val DECORATION_TOKENS = setOf(
            "ar", "arabic", "mena", "live", "vip", "premium", "channel", "tv",
        )
    }
}

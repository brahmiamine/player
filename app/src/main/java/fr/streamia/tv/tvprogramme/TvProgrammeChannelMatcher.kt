package fr.streamia.tv.tvprogramme

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.LiveChannelPrefix
import fr.streamia.tv.domain.MediaEntry
import java.text.Normalizer
import java.util.Locale

/**
 * Associe les chaînes de tv-programme.com uniquement aux chaînes Direct "FR" de la playlist
 * (catégorie ou nom de chaîne commençant par "FR", voir [LiveChannelPrefix]), puis choisit la
 * meilleure variante qualité disponible : 4K > UHD > FHD > HD > SD > non marquée.
 */
class TvProgrammeChannelMatcher {
    fun frenchLiveChannels(catalog: Catalog): List<MediaEntry> = LiveChannelPrefix.FR.channels(catalog)

    fun resolve(
        programmes: List<TvProgrammeItem>,
        catalog: Catalog,
    ): List<ResolvedTvProgrammeItem> = resolve(programmes, frenchLiveChannels(catalog))

    fun resolveNow(
        programmes: List<TvProgrammeNowItem>,
        catalog: Catalog,
    ): List<ResolvedTvProgrammeNowItem> = resolveNow(programmes, frenchLiveChannels(catalog))

    fun resolveNow(
        programmes: List<TvProgrammeNowItem>,
        channels: List<MediaEntry>,
    ): List<ResolvedTvProgrammeNowItem> {
        if (programmes.isEmpty() || channels.isEmpty()) return emptyList()
        val index = ChannelIndex(channels)
        val seenChannels = mutableSetOf<String>()
        return programmes.mapNotNull { programme ->
            val best = index.best(programme.channelName) ?: return@mapNotNull null
            if (!seenChannels.add(best.key)) return@mapNotNull null
            ResolvedTvProgrammeNowItem(programme = programme, channel = best)
        }
    }

    fun resolve(
        programmes: List<TvProgrammeItem>,
        channels: List<MediaEntry>,
    ): List<ResolvedTvProgrammeItem> {
        if (programmes.isEmpty() || channels.isEmpty()) return emptyList()
        val index = ChannelIndex(channels)
        val seenChannels = mutableSetOf<String>()
        return programmes.mapNotNull { programme ->
            val best = index.best(programme.channelName) ?: return@mapNotNull null
            if (!seenChannels.add(best.key)) return@mapNotNull null
            ResolvedTvProgrammeItem(programme = programme, channel = best)
        }
    }

    /**
     * Chaînes normalisées une seule fois, et indexées par mot et par nom compact. Le score
     * (Jaccard sur les mots, ou 1 si les noms compacts sont identiques) vaut 0 pour une chaîne sans
     * aucun mot commun : seules les chaînes qui partagent un mot ou le nom compact sont comparées,
     * au lieu de toutes les chaînes FR pour chaque programme (des centaines de milliers de
     * comparaisons, qui figeaient l'accueil à chaque retour). Le résultat est identique : mêmes
     * scores, même ordre de départage (score, qualité, numéro, puis ordre de la playlist).
     */
    private inner class ChannelIndex(channels: List<MediaEntry>) {
        private val indexed = channels.map { channel ->
            IndexedChannel(
                channel = channel,
                display = normalized(channel.displayName),
                raw = normalized(channel.name),
                quality = maxOf(qualityRank(channel.displayName), qualityRank(channel.name)),
            )
        }
        private val byToken = HashMap<String, MutableList<Int>>()
        private val byCompact = HashMap<String, MutableList<Int>>()

        init {
            indexed.forEachIndexed { position, candidate ->
                for (name in listOf(candidate.display, candidate.raw)) {
                    name.tokens.forEach { byToken.getOrPut(it) { mutableListOf() } += position }
                    if (name.compact.isNotBlank()) byCompact.getOrPut(name.compact) { mutableListOf() } += position
                }
            }
        }

        fun best(channelName: String): MediaEntry? {
            val source = normalized(channelName)
            if (source.compact.isBlank()) return null
            val positions = sortedSetOf<Int>()
            byCompact[source.compact]?.let(positions::addAll)
            source.tokens.forEach { token -> byToken[token]?.let(positions::addAll) }
            return positions.asSequence()
                .map { position ->
                    val candidate = indexed[position]
                    RankedChannel(
                        channel = candidate.channel,
                        score = maxOf(similarity(source, candidate.display), similarity(source, candidate.raw)),
                        quality = candidate.quality,
                    )
                }
                .filter { it.score >= MIN_MATCH_SCORE }
                .sortedWith(
                    compareByDescending<RankedChannel> { it.score }
                        .thenByDescending { it.quality }
                        .thenBy { it.channel.number },
                )
                .firstOrNull()
                ?.channel
        }
    }

    private fun similarity(source: NormalizedName, candidate: NormalizedName): Double {
        if (source.compact.isBlank() || candidate.compact.isBlank()) return 0.0
        if (source.compact == candidate.compact) return 1.0
        if (source.tokens.isEmpty() || candidate.tokens.isEmpty()) return 0.0

        val intersection = source.tokens.count { it in candidate.tokens }
        val union = source.tokens.size + candidate.tokens.size - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private fun normalized(raw: String): NormalizedName {
        val withoutFrenchPrefix = LiveChannelPrefix.FR.strip(raw)
        val ascii = Normalizer.normalize(withoutFrenchPrefix, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace("+", " plus ")
            .lowercase(Locale.ROOT)
            .replace(NON_ALNUM, " ")
            .trim()

        val tokens = ascii.split(WHITESPACE)
            .asSequence()
            .filter(String::isNotBlank)
            .filterNot { it in DECORATION_TOKENS || it in QUALITY_TOKENS }
            .toList()

        return NormalizedName(tokens.toSet(), tokens.joinToString(separator = ""))
    }

    private fun qualityRank(raw: String): Int {
        val tokens = raw.lowercase(Locale.ROOT)
            .replace(NON_ALNUM, " ")
            .split(WHITESPACE)
            .filter(String::isNotBlank)
            .toSet()
        return when {
            "4k" in tokens || "2160p" in tokens -> 5
            "uhd" in tokens -> 4
            "fhd" in tokens || "1080p" in tokens -> 3
            "hd" in tokens || "720p" in tokens -> 2
            "sd" in tokens -> 1
            else -> 0
        }
    }

    private data class NormalizedName(val tokens: Set<String>, val compact: String)
    private data class IndexedChannel(
        val channel: MediaEntry,
        val display: NormalizedName,
        val raw: NormalizedName,
        val quality: Int,
    )
    private data class RankedChannel(val channel: MediaEntry, val score: Double, val quality: Int)

    private companion object {
        const val MIN_MATCH_SCORE = 0.6
        val COMBINING_MARKS = Regex("\\p{M}+")
        val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
        val WHITESPACE = Regex("\\s+")
        val QUALITY_TOKENS = setOf("4k", "2160p", "uhd", "fhd", "1080p", "hd", "720p", "sd")
        val DECORATION_TOKENS = setOf("vip", "raw", "hevc", "h265", "h264", "live", "tv")
    }
}

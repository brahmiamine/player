package fr.streamia.tv.liveonsat

import fr.streamia.tv.domain.MediaEntry
import java.text.Normalizer

/**
 * Index inversé jeton -> chaînes Direct du profil, construit une seule fois par résolution pour
 * éviter un balayage O(diffuseurs × chaînes) : un catalogue Direct peut compter des dizaines de
 * milliers d'entrées (même logique que [fr.streamia.tv.domain.EpgGuide.channelsByAlias]).
 */
class ChannelIndex internal constructor(internal val postings: Map<String, List<MediaEntry>>)

/**
 * Moteur métier pur et déterministe (même philosophie que
 * [fr.streamia.tv.recommendation.RecommendationEngine]) : associe le nom brut d'un diffuseur
 * liveonsat.com (ex. "beIN Sports 1 HD", "SuperSport ESPN 2 HD") à la chaîne du profil dont le nom
 * partage le plus de jetons significatifs. Un nom de diffuseur reste sans correspondance plutôt que
 * de forcer un résultat sous le seuil minimal — l'écran affiche alors ce diffuseur en lecture seule.
 *
 * Limite assumée : deux variantes régionales d'une même marque (ex. "Arena Sport 1 Hrvatska" vs
 * "... Srbija") ne se distinguent que si le nom de la chaîne utilisateur porte lui aussi le nom du
 * pays — sans lui, le choix entre variantes reste un pari, comme pour toute mise en correspondance
 * par similarité de texte.
 */
class ChannelMatcher {
    fun buildIndex(liveChannels: List<MediaEntry>): ChannelIndex {
        val postings = linkedMapOf<String, MutableList<MediaEntry>>()
        liveChannels.forEach { entry ->
            tokensOf(entry.displayName).forEach { token ->
                postings.getOrPut(token) { mutableListOf() }.add(entry)
            }
        }
        return ChannelIndex(postings)
    }

    /** Meilleure correspondance pour un nom de diffuseur, ou `null` sous le seuil minimal. */
    fun match(index: ChannelIndex, broadcasterName: String): MediaEntry? {
        val sourceTokens = tokensOf(broadcasterName)
        if (sourceTokens.isEmpty()) return null

        return sourceTokens.asSequence()
            .flatMap { index.postings[it].orEmpty().asSequence() }
            .distinct()
            .map { entry -> entry to jaccard(sourceTokens, tokensOf(entry.displayName)) }
            .filter { (_, score) -> score >= MIN_MATCH_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    /** Résout en une passe tous les diffuseurs de [matches] contre [liveChannels]. */
    fun resolve(matches: List<LiveOnSatMatch>, liveChannels: List<MediaEntry>): List<ResolvedLiveOnSatMatch> {
        val index = buildIndex(liveChannels)
        return matches.map { match ->
            val resolved = match.channels.mapNotNull { channel ->
                match(index, channel.name)?.let { entry -> channel.name to entry }
            }.toMap()
            ResolvedLiveOnSatMatch(match, resolved)
        }
    }

    internal fun tokensOf(name: String): Set<String> {
        val withoutAnnotations = name.replace(BRACKETED_ANNOTATION, " ")
        val normalized = Normalizer.normalize(withoutAnnotations, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
            .replace(NON_WORD, " ")
        return normalized.split(' ')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::stemPlural)
            // Un chiffre isolé ("1", "2"...) reste significatif : c'est souvent ce qui distingue
            // deux chaînes d'une même marque ("beIN Sports 1" vs "beIN Sports 2"). Seul un jeton
            // alphabétique trop court (bruit de découpage) est écarté.
            .filterNot { it in DECORATION_TOKENS || (it.length < 2 && !it[0].isDigit()) }
            .toSet()
    }

    /** Neutralise l'écart fréquent singulier/pluriel entre fournisseurs ("sport" vs "sports"). */
    private fun stemPlural(token: String): String =
        if (token.length > 3 && token.endsWith('s')) token.dropLast(1) else token

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        val union = a.size + b.size - intersection
        return if (union <= 0) 0.0 else intersection.toDouble() / union
    }

    private companion object {
        const val MIN_MATCH_SCORE = 0.5
        val BRACKETED_ANNOTATION = Regex("[\\(\\[][^)\\]]*[)\\]]")
        val NON_WORD = Regex("[^\\p{L}\\p{N}+]+")
        val COMBINING_MARKS = Regex("\\p{M}+")
        val DECORATION_TOKENS = setOf(
            "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264",
            "live", "tv", "channel", "geo", "r", "app", "online", "stream",
        )
    }
}

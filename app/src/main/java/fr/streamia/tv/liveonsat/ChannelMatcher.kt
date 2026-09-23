package fr.streamia.tv.liveonsat

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.LiveChannelPrefix
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import java.text.Normalizer

/**
 * Index inversé jeton -> chaînes Direct du profil, construit une seule fois par résolution pour
 * éviter un balayage O(diffuseurs × chaînes) : un catalogue Direct peut compter des dizaines de
 * milliers d'entrées (même logique que [fr.streamia.tv.domain.EpgGuide.channelsByAlias]).
 */
class ChannelIndex internal constructor(
    internal val postings: Map<String, List<MediaEntry>>,
    /** Bouquet beIN arabe (catégorie ou nom "AR", voir [LiveChannelPrefix]). Un diffuseur beIN qui
     * précise la région Moyen-Orient/Afrique du Nord ("MENA", "Connect", "Arabia"...) est cherché
     * uniquement ici : sans ça, "beIN Sports MENA 3" matcherait n'importe quelle "BEIN SPORTS 3"
     * du profil au même score, y compris une chaîne FR ou AU sans rapport avec la diffusion réelle. */
    internal val arabicBeinChannels: List<MediaEntry>,
)

/**
 * Moteur métier pur et déterministe (même philosophie que
 * [fr.streamia.tv.recommendation.RecommendationEngine]) : associe le nom brut d'un diffuseur
 * liveonsat.com (ex. "beIN Sports 1 HD", "SuperSport ESPN 2 HD") aux chaînes du profil dont le nom
 * partage le plus de jetons significatifs. Un nom de diffuseur reste sans correspondance plutôt que
 * de forcer un résultat sous le seuil minimal — l'écran affiche alors ce diffuseur en lecture seule.
 *
 * Un diffuseur reste une seule chaîne : [matchAll] ne renvoie plusieurs résultats que pour de
 * vraies variantes de résolution d'une même chaîne (même catégorie fournisseur, qualité HD/FHD/4K
 * filtrée avant comparaison — elles obtiennent donc le même score). Deux chaînes de catégories
 * différentes qui partagent juste un numéro ("FR : BEIN SPORTS 3" vs "AU : BEIN SPORTS 3") ne sont
 * jamais mélangées, même si leur score est identique.
 *
 * Limite assumée : deux variantes régionales d'une même marque (ex. "Arena Sport 1 Hrvatska" vs
 * "... Srbija") ne se distinguent que si le nom de la chaîne utilisateur porte lui aussi le nom du
 * pays — sans lui, le choix entre variantes reste un pari, comme pour toute mise en correspondance
 * par similarité de texte.
 */
class ChannelMatcher {
    fun buildIndex(catalog: Catalog): ChannelIndex {
        val postings = linkedMapOf<String, MutableList<MediaEntry>>()
        catalog.entriesFor(MediaType.Live).forEach { entry ->
            tokensOf(entry.displayName).forEach { token ->
                postings.getOrPut(token) { mutableListOf() }.add(entry)
            }
        }
        val arabicBeinChannels = LiveChannelPrefix.AR.channels(catalog)
            .filter { "bein" in tokensOf(it.displayName) }
            .sortedBy(MediaEntry::number)
        return ChannelIndex(postings, arabicBeinChannels)
    }

    /** Meilleure(s) correspondance(s) pour un nom de diffuseur, triées par numéro de chaîne. */
    fun matchAll(index: ChannelIndex, broadcasterName: String): List<MediaEntry> {
        val sourceTokens = tokensOf(broadcasterName)
        if (sourceTokens.isEmpty()) return emptyList()

        if (index.arabicBeinChannels.isNotEmpty() && isArabicBeinBroadcaster(sourceTokens)) {
            val withinBouquet = bestMatches(sourceTokens, index.arabicBeinChannels)
            if (withinBouquet.isNotEmpty()) return withinBouquet
            // Diffuseur générique sans numéro ("beIN Connect MENA") : "connect"/"mena" ne
            // partagent presque aucun jeton avec un nom de chaîne numéroté, donc rien n'atteint le
            // seuil normal. On propose alors la chaîne de tête (le plus petit numéro) plutôt que
            // rien. Un diffuseur numéroté ("beIN Sports MENA 3") qui ne trouve rien dans le bouquet
            // reste en revanche sans correspondance : deviner un autre numéro serait pire que rien.
            if (sourceTokens.none { it.all(Char::isDigit) }) {
                return listOf(index.arabicBeinChannels.first())
            }
            return emptyList()
        }

        val candidates = sourceTokens.asSequence()
            .flatMap { index.postings[it].orEmpty().asSequence() }
            .distinctBy(MediaEntry::key)
            .toList()
        return bestMatches(sourceTokens, candidates)
    }

    /** Meilleure correspondance unique, ou `null` sous le seuil minimal. */
    fun match(index: ChannelIndex, broadcasterName: String): MediaEntry? =
        matchAll(index, broadcasterName).firstOrNull()

    /** Résout en une passe tous les diffuseurs de [matches] contre le catalogue Direct. */
    fun resolve(matches: List<LiveOnSatMatch>, catalog: Catalog): List<ResolvedLiveOnSatMatch> {
        val index = buildIndex(catalog)
        return matches.map { match ->
            val resolved = match.channels.mapNotNull { channel ->
                matchAll(index, channel.name).takeIf(List<MediaEntry>::isNotEmpty)?.let { channel.name to it }
            }.toMap()
            ResolvedLiveOnSatMatch(match, resolved)
        }
    }

    /**
     * Score [candidates] contre [sourceTokens] et ne garde que les meilleurs. Plusieurs chaînes ne
     * sont retournées que si elles partagent en plus la même catégorie fournisseur : de vraies
     * résolutions d'une même chaîne y sont groupées, alors que deux chaînes de régions différentes
     * portant le même numéro finissent dans des catégories distinctes et restent séparées.
     */
    private fun bestMatches(sourceTokens: Set<String>, candidates: List<MediaEntry>): List<MediaEntry> {
        val scored = candidates.asSequence()
            .map { entry -> entry to jaccard(sourceTokens, tokensOf(entry.displayName)) }
            .filter { (_, score) -> score >= MIN_MATCH_SCORE }
            .toList()
        if (scored.isEmpty()) return emptyList()

        val bestScore = scored.maxOf { it.second }
        val tied = scored.filter { (_, score) -> score == bestScore }.map { (entry, _) -> entry }
        if (tied.size <= 1) return tied

        return tied.groupBy(MediaEntry::categoryId)
            .values
            .maxBy { it.size }
            .sortedBy(MediaEntry::number)
    }

    /** "beIN Connect MENA", "beIN Sports MENA 3", "beIN Sports Arabia"... */
    private fun isArabicBeinBroadcaster(tokens: Set<String>): Boolean =
        "bein" in tokens && GENERIC_ARABIC_BEIN_TOKENS.any { it in tokens }

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
        val GENERIC_ARABIC_BEIN_TOKENS = setOf("connect", "mena", "arabia", "arabic")
    }
}

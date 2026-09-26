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
    /** Chaînes Direct dans l'ordre du catalogue ; les listes de [postings] sont des positions ici. */
    internal val channels: List<MediaEntry>,
    /** Nombre de jetons de chaque chaîne (même position que [channels]). */
    internal val tokenCounts: IntArray,
    internal val postings: Map<String, IntArray>,
    /** Bouquet beIN arabe (catégorie ou nom "AR", voir [LiveChannelPrefix]). Un diffuseur beIN qui
     * précise la région Moyen-Orient/Afrique du Nord ("MENA", "Connect", "Arabia"...) est cherché
     * uniquement ici : sans ça, "beIN Sports MENA 3" matcherait n'importe quelle "BEIN SPORTS 3"
     * du profil au même score, y compris une chaîne FR ou AU sans rapport avec la diffusion réelle. */
    internal val arabicBeinChannels: List<MediaEntry>,
    internal val arabicBeinTokens: List<Set<String>>,
) {
    /**
     * Résultat déjà calculé par nom de diffuseur : « beIN Sports 1 », « Canal+ Sport »… reviennent
     * dans des dizaines de matchs de la journée. L'index est immuable, le résultat ne dépend que
     * du nom : il est calculé une seule fois.
     */
    internal val resultsByBroadcaster = java.util.concurrent.ConcurrentHashMap<String, List<MediaEntry>>()
}

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
    /**
     * Chaque nom de chaîne n'est découpé en jetons qu'une fois, ici. Avant, chaque comparaison
     * redécoupait le nom de la chaîne candidate (normalisation Unicode + expressions régulières),
     * pour chaque diffuseur de chaque match : des millions d'opérations sur un gros catalogue,
     * puisqu'un jeton courant (« 1 », « sport ») est partagé par des milliers de chaînes.
     */
    fun buildIndex(catalog: Catalog): ChannelIndex {
        val channels = catalog.entriesFor(MediaType.Live)
        val tokenCounts = IntArray(channels.size)
        val postings = HashMap<String, IntList>()
        channels.forEachIndexed { position, entry ->
            val tokens = tokensOf(entry.displayName)
            tokenCounts[position] = tokens.size
            tokens.forEach { token -> postings.getOrPut(token) { IntList() }.add(position) }
        }
        val arabicBeinChannels = LiveChannelPrefix.AR.channels(catalog)
            .filter { "bein" in tokensOf(it.displayName) }
            .sortedBy(MediaEntry::number)
        return ChannelIndex(
            channels = channels,
            tokenCounts = tokenCounts,
            postings = postings.mapValues { (_, positions) -> positions.toArray() },
            arabicBeinChannels = arabicBeinChannels,
            arabicBeinTokens = arabicBeinChannels.map { tokensOf(it.displayName) },
        )
    }

    /** Liste d'entiers sans boîtage (des centaines de milliers de positions sur un gros catalogue). */
    private class IntList {
        private var values = IntArray(4)
        private var size = 0
        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }
        fun toArray(): IntArray = values.copyOf(size)
    }

    /** Meilleure(s) correspondance(s) pour un nom de diffuseur, triées par numéro de chaîne. */
    fun matchAll(index: ChannelIndex, broadcasterName: String): List<MediaEntry> =
        index.resultsByBroadcaster.getOrPut(broadcasterName) { computeMatches(index, broadcasterName) }

    private fun computeMatches(index: ChannelIndex, broadcasterName: String): List<MediaEntry> {
        val sourceTokens = tokensOf(broadcasterName)
        if (sourceTokens.isEmpty()) return emptyList()

        if (index.arabicBeinChannels.isNotEmpty() && isArabicBeinBroadcaster(sourceTokens)) {
            val withinBouquet = bestMatches(
                index.arabicBeinChannels.mapIndexed { i, entry -> entry to jaccard(sourceTokens, index.arabicBeinTokens[i]) },
            )
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

        // Jetons communs comptés directement depuis l'index (sans redécouper le nom des chaînes) :
        // Jaccard = communs / (jetons source + jetons chaîne − communs), exactement comme avant.
        // Ordre des candidats identique à l'ancien parcours (jeton source puis ordre du catalogue,
        // première occurrence d'une clé gardée), donc mêmes départages entre ex æquo.
        val sharedCounts = LinkedHashMap<Int, Int>()
        sourceTokens.forEach { token ->
            index.postings[token]?.forEach { position -> sharedCounts[position] = (sharedCounts[position] ?: 0) + 1 }
        }
        val seenKeys = HashSet<String>()
        val scored = ArrayList<Pair<MediaEntry, Double>>()
        sharedCounts.forEach { (position, shared) ->
            val entry = index.channels[position]
            if (!seenKeys.add(entry.key)) return@forEach
            val union = sourceTokens.size + index.tokenCounts[position] - shared
            scored += entry to (if (union <= 0) 0.0 else shared.toDouble() / union)
        }
        return bestMatches(scored)
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
    private fun bestMatches(candidates: List<Pair<MediaEntry, Double>>): List<MediaEntry> {
        val scored = candidates.filter { (_, score) -> score >= MIN_MATCH_SCORE }
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
        // Nom déjà en ASCII (le cas de la grande majorité des chaînes) : la décomposition Unicode
        // ne changerait rien et il n'y a aucun accent à retirer.
        val withoutAccents = if (withoutAnnotations.all { it.code < 128 }) withoutAnnotations
        else Normalizer.normalize(withoutAnnotations, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
        val normalized = withoutAccents
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

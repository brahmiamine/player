package fr.streamia.tv.recommendation

import kotlin.math.ln

/** Métadonnées minimales d'un contenu enrichi, indexées par clé « Movie:123 ». */
internal data class IndexedContent(
    val key: String,
    val title: String,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val tmdbId: String? = null,
    /** Sagas / franchises Wikidata : (Q-id, libellé). */
    val sagas: List<Pair<String, String>> = emptyList(),
    /** Mots-clés TMDB. */
    val keywords: List<String> = emptyList(),
)

/** Lien fort hors métadonnées (même saga, mêmes spectateurs) : score plancher + raison affichée. */
data class SimilarityBoost(val score: Double, val reason: String)

/**
 * Pré-sélection des candidats « similaires » dans TOUT le catalogue enrichi, et non plus seulement
 * autour du média dans sa catégorie IPTV (souvent « FR 2023 » ou « 4K » : sans rapport avec le genre).
 *
 * Index inversé genre / personnes / mots d'intrigue → contenus, puis score rapide :
 * mots d'intrigue pondérés par leur rareté (IDF — « vampire » pèse bien plus que « police »),
 * genres communs, réalisateur, acteurs et saga (titre). Le classement final reste celui de
 * [MetadataSimilarityEngine] ; cet index ne fait que lui amener les bons candidats.
 */
internal class ContentCandidateIndex(contents: Collection<IndexedContent>) {
    private class Signals(
        val genres: Set<String>,
        val people: Set<String>,
        val plot: Set<String>,
        val title: Set<String>,
        val keywords: Set<String>,
    )

    private val tokenizer = MetadataSimilarityEngine()
    private val signals = HashMap<String, Signals>(contents.size * 2)
    private val byGenre = HashMap<String, MutableList<String>>()
    private val byPerson = HashMap<String, MutableList<String>>()
    private val byPlot = HashMap<String, MutableList<String>>()
    private val byTitle = HashMap<String, MutableList<String>>()
    private val byKeyword = HashMap<String, MutableList<String>>()
    private val bySaga = HashMap<String, MutableList<String>>()
    private val sagaLabels = HashMap<String, String>()
    private val sagasByKey = HashMap<String, List<String>>()
    private val movieByTmdb = HashMap<Int, String>()
    val size: Int get() = signals.size

    init {
        for (content in contents) {
            val s = signals(content)
            signals[content.key] = s
            s.genres.forEach { byGenre.getOrPut(it) { mutableListOf() } += content.key }
            s.people.forEach { byPerson.getOrPut(it) { mutableListOf() } += content.key }
            s.plot.forEach { byPlot.getOrPut(it) { mutableListOf() } += content.key }
            s.title.forEach { byTitle.getOrPut(it) { mutableListOf() } += content.key }
            s.keywords.forEach { byKeyword.getOrPut(it) { mutableListOf() } += content.key }
            content.sagas.forEach { (qid, label) ->
                bySaga.getOrPut(qid) { mutableListOf() } += content.key
                sagaLabels[qid] = label
            }
            sagasByKey[content.key] = content.sagas.map { it.first }
            if (content.key.startsWith("Movie:")) content.tmdbId?.trim()?.toIntOrNull()?.let { movieByTmdb.putIfAbsent(it, content.key) }
        }
    }

    private fun signals(content: IndexedContent) = Signals(
        genres = tokenizer.genreTokens(content.genre),
        // Nom complet (« christopher nolan ») plutôt que chaque mot, pour ne pas relier deux « john ».
        people = (content.director.orEmpty().split(',', '/', ';') + content.cast.orEmpty().split(',', '/', ';').take(MAX_CAST))
            .map { tokenizer.personTokens(it).joinToString(" ") }
            .filterTo(mutableSetOf()) { it.length >= 5 },
        plot = tokenizer.plotTokens(content.plot),
        title = tokenizer.titleTokens(content.title).filterTo(mutableSetOf()) { it.length >= 4 },
        keywords = tokenizer.keywordSet(content.keywords),
    )

    /**
     * Liens forts vers [source] : même saga Wikidata (hors « sagas » fourre-tout de plus de
     * [MAX_SAGA_SIZE] titres) et voisins MovieLens ([movieLens], TMDB ID du plus proche au moins proche).
     */
    fun related(source: IndexedContent, movieLens: IntArray?): Map<String, SimilarityBoost> {
        val typePrefix = source.key.substringBefore(':') + ":"
        val result = LinkedHashMap<String, SimilarityBoost>()
        val sourceSagas = source.sagas.map { it.first }.ifEmpty { sagasByKey[source.key].orEmpty() }
        for (qid in sourceSagas) {
            val members = bySaga[qid] ?: continue
            if (members.size > MAX_SAGA_SIZE) continue
            val label = sagaLabels[qid] ?: continue
            members.filter { it != source.key && it.startsWith(typePrefix) }
                // Libellé absent en français et en anglais : Wikidata renvoie alors le Q-id brut.
                .forEach { result.putIfAbsent(it, SimilarityBoost(SAGA_SCORE, if (UNLABELED.matches(label)) "Même saga" else "Même saga : $label")) }
        }
        movieLens?.forEachIndexed { rank, tmdb ->
            val key = movieByTmdb[tmdb] ?: return@forEachIndexed
            if (key == source.key || !key.startsWith(typePrefix)) return@forEachIndexed
            val score = MOVIELENS_TOP_SCORE - rank * MOVIELENS_RANK_STEP
            val existing = result[key]
            if (existing == null || existing.score < score) result[key] = SimilarityBoost(score, "Aimé par les mêmes spectateurs")
        }
        return result
    }

    private fun idf(postings: Int): Double = ln((signals.size + 1.0) / (postings + 1.0))

    /**
     * Les [limit] contenus du même type (préfixe de clé) les plus proches de [source]. [source] peut
     * être absent de l'index (fiche jamais enrichie) : on se base alors sur ce qu'il fournit.
     */
    fun topMatches(source: IndexedContent, limit: Int): List<String> {
        val src = signals[source.key] ?: signals(source)
        val typePrefix = source.key.substringBefore(':') + ":"
        fun accumulate(into: HashMap<String, Double>, postings: List<String>?, weight: Double) {
            postings ?: return
            // Un mot présent dans une grande partie du catalogue ne discrimine rien : ignoré.
            if (postings.size > signals.size * MAX_POSTING_SHARE) return
            for (key in postings) {
                if (key != source.key && key.startsWith(typePrefix)) into.merge(key, weight, Double::plus)
            }
        }
        val plotScores = HashMap<String, Double>()
        val peopleScores = HashMap<String, Double>()
        val titleScores = HashMap<String, Double>()
        val keywordScores = HashMap<String, Double>()
        src.plot.forEach { accumulate(plotScores, byPlot[it], idf(byPlot[it]?.size ?: 0)) }
        src.people.forEach { accumulate(peopleScores, byPerson[it], PERSON_WEIGHT) }
        src.title.forEach { accumulate(titleScores, byTitle[it], TITLE_WEIGHT) }
        // Mots-clés rares (« nuclear catastrophe ») pèsent bien plus que les fréquents (« murder »).
        src.keywords.forEach { accumulate(keywordScores, byKeyword[it], idf(byKeyword[it]?.size ?: 0) * KEYWORD_SCALE) }
        // Genres : part de genres partagés (Dice), pas le nombre brut.
        val genreHits = HashMap<String, Int>()
        src.genres.forEach { genre ->
            byGenre[genre]?.forEach { key -> if (key != source.key && key.startsWith(typePrefix)) genreHits.merge(key, 1, Int::plus) }
        }
        val plotNorm = src.plot.sumOf { idf(byPlot[it]?.size ?: 0) }.coerceAtLeast(1.0)
        val keys = HashSet<String>().apply { addAll(plotScores.keys); addAll(peopleScores.keys); addAll(titleScores.keys); addAll(keywordScores.keys); addAll(genreHits.keys) }
        return keys.asSequence()
            .map { key ->
                val shared = genreHits[key] ?: 0
                val candidateGenres = signals[key]?.genres?.size ?: 0
                val genreScore = if (shared == 0) 0.0 else 2.0 * shared / (src.genres.size + candidateGenres)
                val plotScore = (plotScores[key] ?: 0.0) / plotNorm * PLOT_SCALE
                key to (GENRE_WEIGHT * genreScore + plotScore + (peopleScores[key] ?: 0.0) + (titleScores[key] ?: 0.0) + (keywordScores[key] ?: 0.0))
            }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private companion object {
        const val MAX_CAST = 5
        const val MAX_POSTING_SHARE = 0.08
        const val PERSON_WEIGHT = 1.2
        const val TITLE_WEIGHT = 0.6
        const val GENRE_WEIGHT = 1.0
        const val PLOT_SCALE = 3.0
        const val KEYWORD_SCALE = 0.25
        const val MIN_SCORE = 0.25
        const val MAX_SAGA_SIZE = 60
        val UNLABELED = Regex("Q\\d+")
        const val SAGA_SCORE = 0.95
        const val MOVIELENS_TOP_SCORE = 0.80
        const val MOVIELENS_RANK_STEP = 0.01
    }
}

package fr.streamia.tv.recommendation

import fr.streamia.tv.domain.MediaDetails
import fr.streamia.tv.domain.MediaEntry
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class ContentFeatures(
    val entry: MediaEntry,
    val plot: String? = entry.plot,
    val genre: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val country: String? = null,
    val releaseDate: String? = null,
    val rating: Double? = entry.rating,
    val tmdbId: String? = null,
    val enriched: Boolean = false,
) {
    companion object {
        fun from(entry: MediaEntry, details: MediaDetails? = null): ContentFeatures = ContentFeatures(
            entry = entry,
            plot = details?.plot ?: entry.plot,
            genre = details?.genre,
            cast = details?.cast,
            director = details?.director,
            country = details?.country,
            releaseDate = details?.releaseDate,
            rating = details?.rating ?: entry.rating,
            tmdbId = details?.tmdbId,
            enriched = details != null,
        )
    }
}

data class SimilarityScore(
    val score: Double,
    val reason: String? = null,
    val semanticUsed: Boolean = false,
    val substantive: Boolean = false,
)

interface ContentSimilarityEngine {
    fun compare(source: ContentFeatures, candidate: ContentFeatures): SimilarityScore
}

/**
 * Point d'extension pour un modèle d'embedding on-device. Le moteur de recommandation ne dépend
 * jamais d'une implémentation ML particulière : si aucun modèle ne passe le budget Android TV,
 * le ranking reste fonctionnel avec les métadonnées locales.
 */
interface SemanticSimilarityProvider {
    val id: String
    fun similarity(sourceText: String, candidateText: String): Double?
}

/**
 * Similarité multi-signaux, déterministe et tolérante aux catalogues IPTV bruités.
 *
 * Le titre n'est jamais comparé tel quel : les tags fournisseur (MULTI/FHD/UHD/VOSTFR, année
 * ajoutée au nom, etc.) sont retirés pour éviter que deux contenus sans rapport deviennent
 * artificiellement proches. Une simple catégorie IPTV commune, une année proche ou une note
 * voisine ne constituent jamais à elles seules une preuve de similarité.
 */
class MetadataSimilarityEngine(
    private val semanticProvider: SemanticSimilarityProvider? = null,
) : ContentSimilarityEngine {
    override fun compare(source: ContentFeatures, candidate: ContentFeatures): SimilarityScore {
        val sourceTitleTokens = titleTokens(source.entry.displayName)
        val candidateTitleTokens = titleTokens(candidate.entry.displayName)
        val sourcePlotTokens = tokens(source.plot)
        val candidatePlotTokens = tokens(candidate.plot)
        val sourceGenres = genreTokens(source.genre)
        val candidateGenres = genreTokens(candidate.genre)
        val sourceCast = personTokens(source.cast)
        val candidateCast = personTokens(candidate.cast)
        val sourceDirector = personTokens(source.director)
        val candidateDirector = personTokens(candidate.director)
        val sourceCountries = tokens(source.country)
        val candidateCountries = tokens(candidate.country)

        val titleSimilarity = dice(sourceTitleTokens, candidateTitleTokens)
        val plotSimilarity = dice(sourcePlotTokens, candidatePlotTokens)
        val genreSimilarity = dice(sourceGenres, candidateGenres)
        val castSimilarity = dice(sourceCast, candidateCast)
        val directorSimilarity = dice(sourceDirector, candidateDirector)
        val countrySimilarity = dice(sourceCountries, candidateCountries)
        val yearSimilarity = yearSimilarity(source, candidate)
        val ratingSimilarity = ratingSimilarity(source.rating, candidate.rating)
        val categoryMatch = source.entry.categoryId == candidate.entry.categoryId

        var weighted = 0.0
        var available = 0.0
        fun add(weight: Double, value: Double, present: Boolean) {
            if (!present) return
            weighted += weight * value.coerceIn(0.0, 1.0)
            available += weight
        }

        // Les signaux descriptifs dominent. Catégorie/année/note servent uniquement de
        // départage et ne peuvent pas créer un résultat similaire sans preuve sémantique.
        add(0.10, titleSimilarity, sourceTitleTokens.isNotEmpty() && candidateTitleTokens.isNotEmpty())
        add(0.34, plotSimilarity, sourcePlotTokens.isNotEmpty() && candidatePlotTokens.isNotEmpty())
        add(0.28, genreSimilarity, sourceGenres.isNotEmpty() && candidateGenres.isNotEmpty())
        add(0.08, castSimilarity, sourceCast.isNotEmpty() && candidateCast.isNotEmpty())
        add(0.10, directorSimilarity, sourceDirector.isNotEmpty() && candidateDirector.isNotEmpty())
        add(0.025, countrySimilarity, sourceCountries.isNotEmpty() && candidateCountries.isNotEmpty())
        add(0.035, yearSimilarity, extractYear(source) != null && extractYear(candidate) != null)
        add(0.02, ratingSimilarity, source.rating != null && candidate.rating != null)
        add(0.01, if (categoryMatch) 1.0 else 0.0, true)

        var metadataScore = if (available <= 0.0) 0.0 else (weighted / available).coerceIn(0.0, 1.0)

        val semantic = semanticProvider
            ?.similarity(embeddingText(source), embeddingText(candidate))
            ?.takeIf(Double::isFinite)
            ?.coerceIn(0.0, 1.0)

        val strongTitleRelation = titleSimilarity >= 0.34
        val strongPlotRelation = plotSimilarity >= 0.10
        val genreRelation = genreSimilarity >= 0.34
        val peopleRelation = directorSimilarity >= 0.72 || castSimilarity >= 0.20
        val semanticRelation = semantic != null && semantic >= 0.58
        val substantive = strongTitleRelation || strongPlotRelation || genreRelation || peopleRelation || semanticRelation

        // Deux genres explicitement incompatibles doivent fortement pénaliser un rapprochement
        // faible basé sur quelques mots génériques du synopsis.
        if (
            sourceGenres.isNotEmpty() &&
            candidateGenres.isNotEmpty() &&
            genreSimilarity == 0.0 &&
            !strongPlotRelation &&
            !peopleRelation &&
            !semanticRelation
        ) {
            metadataScore *= DISJOINT_GENRE_PENALTY
        }

        val blended = if (semantic != null) {
            SEMANTIC_WEIGHT * semantic + METADATA_WEIGHT_WITH_SEMANTIC * metadataScore
        } else {
            metadataScore
        }

        // Règle essentielle pour la fiche détail : "même catégorie", "même année", "même note"
        // ne sont que du contexte. Sans relation descriptive, le score est nul.
        val finalScore = if (substantive) blended.coerceIn(0.0, 1.0) else 0.0

        val reason = when {
            directorSimilarity >= 0.72 -> "Même réalisateur"
            genreSimilarity >= 0.66 && plotSimilarity >= 0.10 -> "Genres, intrigue et univers proches"
            semantic != null && semantic >= 0.72 -> "Intrigue et ambiance similaires"
            genreSimilarity >= 0.50 -> "Genres et univers proches"
            plotSimilarity >= 0.18 -> "Intrigue et thèmes similaires"
            castSimilarity >= 0.25 -> "Distribution similaire"
            titleSimilarity >= 0.45 -> "Même saga ou univers"
            else -> null
        }

        return SimilarityScore(
            score = finalScore,
            reason = reason,
            semanticUsed = semantic != null,
            substantive = substantive,
        )
    }

    private fun embeddingText(features: ContentFeatures): String = buildList {
        add(cleanTitle(features.entry.displayName))
        features.plot?.takeIf(String::isNotBlank)?.let(::add)
        features.genre?.takeIf(String::isNotBlank)?.let { add("Genre: $it") }
        features.cast?.takeIf(String::isNotBlank)?.let { add("Distribution: $it") }
        features.director?.takeIf(String::isNotBlank)?.let { add("Réalisateur: $it") }
        features.country?.takeIf(String::isNotBlank)?.let { add("Pays: $it") }
        features.releaseDate?.takeIf(String::isNotBlank)?.let { add("Date: $it") }
    }.joinToString(". ").take(MAX_EMBEDDING_TEXT_CHARS)

    private fun titleTokens(value: String): Set<String> = rawTokens(value, minLength = 2)
        .filterNotTo(linkedSetOf()) { token ->
            token in TITLE_NOISE_TOKENS || YEAR_TOKEN.matches(token)
        }

    private fun cleanTitle(value: String): String = titleTokens(value).joinToString(" ")

    private fun genreTokens(value: String?): Set<String> = rawTokens(value, minLength = 2)
        .mapTo(linkedSetOf()) { token -> GENRE_ALIASES[token] ?: token }

    private fun personTokens(value: String?): Set<String> = rawTokens(value, minLength = 2)
        .filterNotTo(linkedSetOf()) { it in PERSON_NOISE_TOKENS }

    private fun tokens(value: String?): Set<String> = rawTokens(value, minLength = 3)
        .filterNotTo(linkedSetOf()) { it in STOP_WORDS || it in TITLE_NOISE_TOKENS }

    private fun rawTokens(value: String?, minLength: Int): Sequence<String> {
        if (value.isNullOrBlank()) return emptySequence()
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
        return TOKEN_REGEX.findAll(normalized)
            .map { it.value }
            .filter { it.length >= minLength }
    }

    private fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        return (2.0 * intersection / (a.size + b.size)).coerceIn(0.0, 1.0)
    }

    private fun extractYear(features: ContentFeatures): Int? {
        val explicit = YEAR_REGEX.find(features.releaseDate.orEmpty())?.value?.toIntOrNull()
        if (explicit != null) return explicit
        return YEAR_REGEX.find(features.entry.displayName)?.value?.toIntOrNull()
    }

    private fun yearSimilarity(source: ContentFeatures, candidate: ContentFeatures): Double {
        val sourceYear = extractYear(source) ?: return 0.0
        val candidateYear = extractYear(candidate) ?: return 0.0
        val distance = abs(sourceYear - candidateYear)
        return when {
            distance == 0 -> 1.0
            distance <= 2 -> 0.85
            distance <= 5 -> 0.60
            distance <= 10 -> 0.30
            else -> 0.0
        }
    }

    private fun ratingSimilarity(source: Double?, candidate: Double?): Double {
        if (source == null || candidate == null) return 0.0
        val maximum = maxOf(source, candidate, 10.0)
        return (1.0 - abs(source - candidate) / maximum).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val MAX_EMBEDDING_TEXT_CHARS = 4_000
        const val SEMANTIC_WEIGHT = 0.78
        const val METADATA_WEIGHT_WITH_SEMANTIC = 0.22
        const val DISJOINT_GENRE_PENALTY = 0.20

        val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
        val COMBINING_MARKS = Regex("\\p{M}+")
        val YEAR_REGEX = Regex("\\b(?:19|20)\\d{2}\\b")
        val YEAR_TOKEN = Regex("(?:19|20)\\d{2}")

        val TITLE_NOISE_TOKENS = setOf(
            "multi", "multilang", "multilingual", "fhd", "uhd", "hdr", "hdr10", "dolby",
            "vostfr", "vost", "truefrench", "french", "vfq", "webrip", "webdl", "bluray",
            "bdrip", "dvdrip", "remux", "x264", "x265", "hevc", "av1", "aac", "dts",
        )

        val PERSON_NOISE_TOKENS = setOf("and", "avec", "with", "et")

        val GENRE_ALIASES = mapOf(
            "science" to "science_fiction",
            "fiction" to "science_fiction",
            "scifi" to "science_fiction",
            "sci" to "science_fiction",
            "sf" to "science_fiction",
            "guerre" to "war",
            "militaire" to "war",
            "military" to "war",
            "حرب" to "war",
            "action" to "action",
            "اكشن" to "action",
            "thriller" to "thriller",
            "suspense" to "thriller",
            "drame" to "drama",
            "drama" to "drama",
            "دراما" to "drama",
            "comedie" to "comedy",
            "comedy" to "comedy",
            "كوميديا" to "comedy",
            "horreur" to "horror",
            "horror" to "horror",
            "رعب" to "horror",
            "crime" to "crime",
            "criminel" to "crime",
            "policier" to "crime",
            "جريمة" to "crime",
            "romance" to "romance",
            "romantique" to "romance",
            "رومانسي" to "romance",
            "aventure" to "adventure",
            "adventure" to "adventure",
            "fantastique" to "fantasy",
            "fantasy" to "fantasy",
            "خيال" to "fantasy",
            "animation" to "animation",
            "anime" to "animation",
            "documentaire" to "documentary",
            "documentary" to "documentary",
            "وثائقي" to "documentary",
            "famille" to "family",
            "family" to "family",
            "عائلي" to "family",
            "mystere" to "mystery",
            "mystery" to "mystery",
            "western" to "western",
            "histoire" to "history",
            "historique" to "history",
            "history" to "history",
            "تاريخي" to "history",
            "musique" to "music",
            "music" to "music",
            "musical" to "music",
            "biographie" to "biography",
            "biography" to "biography",
            "biopic" to "biography",
            "sport" to "sport",
            "sports" to "sport",
        )

        val STOP_WORDS = setOf(
            "the", "and", "for", "with", "that", "this", "from", "into", "after", "before",
            "dans", "avec", "pour", "une", "des", "les", "qui", "sur", "son", "ses", "aux",
            "est", "un", "du", "de", "la", "le", "et", "en", "au", "ce", "ces", "leur", "leurs",
            "من", "في", "على", "الى", "إلى", "عن", "مع", "هذا", "هذه", "التي", "الذي",
        )
    }
}

internal fun ContentFeatures.hasDescriptiveSimilarityMetadata(): Boolean =
    !plot.isNullOrBlank() ||
        !genre.isNullOrBlank() ||
        !cast.isNullOrBlank() ||
        !director.isNullOrBlank() ||
        !country.isNullOrBlank() ||
        !releaseDate.isNullOrBlank()

internal fun likelySameContent(source: ContentFeatures, candidate: ContentFeatures): Boolean {
    if (source.entry.key == candidate.entry.key) return true
    val sourceTmdb = source.tmdbId?.trim()?.takeIf(String::isNotBlank)
    val candidateTmdb = candidate.tmdbId?.trim()?.takeIf(String::isNotBlank)
    if (sourceTmdb != null && candidateTmdb != null && sourceTmdb == candidateTmdb) return true

    val title = canonicalIdentityTitle(source.entry.displayName)
    val candidateTitle = canonicalIdentityTitle(candidate.entry.displayName)
    if (title.isBlank() || title != candidateTitle) return false

    val sourceYear = Regex("\\b(?:19|20)\\d{2}\\b")
        .find(source.releaseDate.orEmpty())?.value
        ?: Regex("\\b(?:19|20)\\d{2}\\b").find(source.entry.displayName)?.value
    val candidateYear = Regex("\\b(?:19|20)\\d{2}\\b")
        .find(candidate.releaseDate.orEmpty())?.value
        ?: Regex("\\b(?:19|20)\\d{2}\\b").find(candidate.entry.displayName)?.value
    return sourceYear != null && candidateYear != null && sourceYear == candidateYear
}

private fun canonicalIdentityTitle(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
    val noise = setOf(
        "multi", "fhd", "uhd", "hdr", "vostfr", "vost", "truefrench", "french",
        "webrip", "webdl", "bluray", "bdrip", "remux", "x264", "x265", "hevc",
    )
    return Regex("[\\p{L}\\p{N}]+").findAll(normalized)
        .map { it.value }
        .filter { it.length >= 2 && it !in noise && !Regex("(?:19|20)\\d{2}").matches(it) }
        .joinToString(" ")
}

package fr.streamia.tv.recommendation

import fr.streamia.tv.domain.MediaDetails
import fr.streamia.tv.domain.MediaEntry
import java.text.Normalizer
import java.util.Locale

data class ContentFeatures(
    val entry: MediaEntry,
    val plot: String? = entry.plot,
    val genre: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val country: String? = null,
    val releaseDate: String? = null,
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
        )
    }
}

data class SimilarityScore(
    val score: Double,
    val reason: String? = null,
    val semanticUsed: Boolean = false,
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

class MetadataSimilarityEngine(
    private val semanticProvider: SemanticSimilarityProvider? = null,
) : ContentSimilarityEngine {
    override fun compare(source: ContentFeatures, candidate: ContentFeatures): SimilarityScore {
        val sourceTextTokens = contentTokens(source)
        val candidateTextTokens = contentTokens(candidate)
        val textSimilarity = jaccard(sourceTextTokens, candidateTextTokens)
        val genreSimilarity = jaccard(tokens(source.genre), tokens(candidate.genre))
        val castSimilarity = jaccard(tokens(source.cast), tokens(candidate.cast))
        val directorMatch = sameNormalized(source.director, candidate.director)
        val countryMatch = sameNormalized(source.country, candidate.country)
        val categoryMatch = source.entry.categoryId == candidate.entry.categoryId

        var weighted = 0.0
        var available = 0.0
        fun add(weight: Double, value: Double, present: Boolean) {
            if (!present) return
            weighted += weight * value
            available += weight
        }

        add(0.55, textSimilarity, sourceTextTokens.isNotEmpty() && candidateTextTokens.isNotEmpty())
        add(0.18, genreSimilarity, !source.genre.isNullOrBlank() && !candidate.genre.isNullOrBlank())
        add(0.08, castSimilarity, !source.cast.isNullOrBlank() && !candidate.cast.isNullOrBlank())
        add(
            0.12,
            if (directorMatch) 1.0 else 0.0,
            !source.director.isNullOrBlank() && !candidate.director.isNullOrBlank(),
        )
        add(
            0.03,
            if (countryMatch) 1.0 else 0.0,
            !source.country.isNullOrBlank() && !candidate.country.isNullOrBlank(),
        )
        add(0.04, if (categoryMatch) 1.0 else 0.0, true)
        val metadataScore = if (available <= 0.0) 0.0 else (weighted / available).coerceIn(0.0, 1.0)

        val semantic = semanticProvider
            ?.similarity(embeddingText(source), embeddingText(candidate))
            ?.takeIf(Double::isFinite)
            ?.coerceIn(0.0, 1.0)
        val finalScore = if (semantic != null) {
            SEMANTIC_WEIGHT * semantic + METADATA_WEIGHT_WITH_SEMANTIC * metadataScore
        } else {
            metadataScore
        }

        val reason = when {
            directorMatch -> "Même réalisateur"
            genreSimilarity >= 0.5 -> "Genres et univers proches"
            textSimilarity >= 0.30 -> "Intrigue et thèmes similaires"
            castSimilarity >= 0.25 -> "Distribution similaire"
            semantic != null && semantic >= 0.70 -> "Intrigue et ambiance similaires"
            categoryMatch -> "Même univers de catalogue"
            else -> null
        }
        return SimilarityScore(
            score = finalScore.coerceIn(0.0, 1.0),
            reason = reason,
            semanticUsed = semantic != null,
        )
    }

    private fun contentTokens(features: ContentFeatures): Set<String> =
        tokens(listOfNotNull(features.entry.displayName, features.plot).joinToString(" "))

    private fun embeddingText(features: ContentFeatures): String = buildList {
        add(features.entry.displayName)
        features.plot?.takeIf(String::isNotBlank)?.let(::add)
        features.genre?.takeIf(String::isNotBlank)?.let { add("Genre: $it") }
        features.cast?.takeIf(String::isNotBlank)?.let { add("Distribution: $it") }
        features.director?.takeIf(String::isNotBlank)?.let { add("Réalisateur: $it") }
        features.country?.takeIf(String::isNotBlank)?.let { add("Pays: $it") }
    }.joinToString(". ").take(MAX_EMBEDDING_TEXT_CHARS)

    private fun tokens(value: String?): Set<String> {
        if (value.isNullOrBlank()) return emptySet()
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
        return TOKEN_REGEX.findAll(normalized)
            .map { it.value }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toCollection(linkedSetOf())
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        val union = a.size + b.size - intersection
        return if (union <= 0) 0.0 else intersection.toDouble() / union
    }

    private fun sameNormalized(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val aTokens = tokens(a)
        return aTokens.isNotEmpty() && aTokens == tokens(b)
    }

    private companion object {
        const val MAX_EMBEDDING_TEXT_CHARS = 4_000
        const val SEMANTIC_WEIGHT = 0.80
        const val METADATA_WEIGHT_WITH_SEMANTIC = 0.20
        val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
        val COMBINING_MARKS = Regex("\\p{M}+")
        val STOP_WORDS = setOf(
            "the", "and", "for", "with", "that", "this", "from",
            "dans", "avec", "pour", "une", "des", "les", "qui", "sur", "son", "ses", "aux",
            "est", "un", "du", "de", "la", "le", "et",
            "من", "في", "على", "الى", "إلى", "عن", "مع", "هذا", "هذه", "التي", "الذي",
        )
    }
}

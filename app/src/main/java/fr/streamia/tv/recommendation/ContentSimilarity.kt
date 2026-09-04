package fr.streamia.tv.recommendation

import fr.streamia.tv.domain.MediaDetails
import fr.streamia.tv.domain.MediaEntry

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

interface SemanticSimilarityProvider {
    val id: String
    fun similarity(sourceText: String, candidateText: String): Double?
}

class MetadataSimilarityEngine(
    private val semanticProvider: SemanticSimilarityProvider? = null,
) : ContentSimilarityEngine {
    override fun compare(source: ContentFeatures, candidate: ContentFeatures): SimilarityScore =
        SimilarityScore(score = 0.0)
}

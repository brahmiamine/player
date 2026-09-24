package fr.streamia.tv.ui

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.recommendation.MetadataSimilarityEngine
import fr.streamia.tv.recommendation.RecommendedMedia
import fr.streamia.tv.recommendation.splitProviderPrefix

private val YEAR = Regex("""\b(19|20)\d{2}\b""")
private val titleEngine = MetadataSimilarityEngine()

private data class VersionKey(val tokens: Set<String>, val year: String?)

/** Titre sans préfixe ni année, à passer à la recherche du catalogue complet. */
internal fun versionSearchQuery(movie: MediaEntry): String =
    titleEngine.titleTokens(movie.displayName).joinToString(" ")

private fun versionKey(name: String): VersionKey {
    return VersionKey(titleEngine.titleTokens(name), YEAR.find(splitProviderPrefix(name).second)?.value)
}

/**
 * Même film (ou série) sous un autre préfixe (langue, résolution…) : titre identique une fois le préfixe
 * fournisseur retiré ; l'année doit aussi coïncider quand les deux titres en portent une (remakes).
 */
internal fun otherVersionsOf(movie: MediaEntry, entries: List<MediaEntry>): List<RecommendedMedia> {
    val key = versionKey(movie.displayName)
    if (key.tokens.isEmpty()) return emptyList()
    return entries.asSequence()
        .filter { it.type == movie.type && it.key != movie.key }
        .filter { candidate ->
            val other = versionKey(candidate.displayName)
            other.tokens == key.tokens && (key.year == null || other.year == null || key.year == other.year)
        }
        .distinctBy { it.key }
        .take(30)
        .map { RecommendedMedia(it, score = 1.0, reason = splitProviderPrefix(it.displayName).first ?: "Autre version") }
        .toList()
}

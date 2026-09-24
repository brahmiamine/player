package fr.streamia.tv.ui

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.recommendation.MetadataSimilarityEngine
import fr.streamia.tv.recommendation.RecommendedMedia

// Préfixe fournisseur en majuscules : « 4K-TOP - », « EN-TOP -», « AR-SUBS - », « FR: », « |EN| »…
// Casse sensible : un vrai titre (« Mission - Impossible ») n'est pas pris pour un préfixe.
private val VERSION_PREFIX = Regex("""^\s*(\|[^|]{1,15}\|\s*|[A-Z0-9]{2,5}(-[A-Z0-9]{2,5})*\s*[-:|]\s*)""")
// Numérotation de liste après le préfixe : « 49. 12 Years… », « 183.12.Years… ».
private val LIST_NUMBER = Regex("""^(\d{1,4}\.\s+|\d{3,4}\.)""")
private val YEAR = Regex("""\b(19|20)\d{2}\b""")
private val titleEngine = MetadataSimilarityEngine()

private data class VersionKey(val tokens: Set<String>, val year: String?)

private fun splitPrefix(name: String): Pair<String?, String> {
    val match = VERSION_PREFIX.find(name) ?: return null to name
    val prefix = match.value.trim().trim('|', '-', ':', ' ').ifBlank { null }
    return prefix to name.substring(match.range.last + 1).replaceFirst(LIST_NUMBER, "")
}

/** Titre sans préfixe ni année, à passer à la recherche du catalogue complet. */
internal fun versionSearchQuery(movie: MediaEntry): String =
    titleEngine.titleTokens(splitPrefix(movie.displayName).second).joinToString(" ")

private fun versionKey(name: String): VersionKey {
    val title = splitPrefix(name).second
    return VersionKey(titleEngine.titleTokens(title), YEAR.find(title)?.value)
}

/**
 * Même film sous un autre préfixe (langue, résolution…) : titre identique une fois le préfixe
 * fournisseur retiré ; l'année doit aussi coïncider quand les deux titres en portent une (remakes).
 */
internal fun otherVersionsOf(movie: MediaEntry, entries: List<MediaEntry>): List<RecommendedMedia> {
    val key = versionKey(movie.displayName)
    if (key.tokens.isEmpty()) return emptyList()
    return entries.asSequence()
        .filter { it.type == MediaType.Movie && it.key != movie.key }
        .filter { candidate ->
            val other = versionKey(candidate.displayName)
            other.tokens == key.tokens && (key.year == null || other.year == null || key.year == other.year)
        }
        .distinctBy { it.key }
        .take(30)
        .map { RecommendedMedia(it, score = 1.0, reason = splitPrefix(it.displayName).first ?: "Autre version") }
        .toList()
}

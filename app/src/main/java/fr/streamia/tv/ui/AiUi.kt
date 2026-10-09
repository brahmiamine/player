package fr.streamia.tv.ui

import fr.streamia.tv.recommendation.RecommendedMedia

/** Similaires dans l'ordre proposé par l'IA ([keys]) ; ceux qu'elle n'a pas classés restent derrière, dans l'ordre du moteur. */
fun List<RecommendedMedia>.withAiOrder(keys: List<String>?): List<RecommendedMedia> {
    if (keys.isNullOrEmpty() || size < 2) return this
    val rank = keys.withIndex().associate { it.value to it.index }
    return sortedBy { rank[it.entry.key] ?: Int.MAX_VALUE }
}

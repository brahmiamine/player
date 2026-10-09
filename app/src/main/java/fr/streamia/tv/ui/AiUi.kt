package fr.streamia.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import fr.streamia.tv.data.AiGate
import fr.streamia.tv.data.AiTitles
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.recommendation.RecommendedMedia

/**
 * Titre à afficher pour [entry] : version nettoyée par l'IA si elle existe et que l'assistant est actif,
 * sinon [fallback]. Désactiver l'assistant ramène immédiatement tous les écrans aux titres d'origine.
 */
@Composable
fun aiTitle(entry: MediaEntry, fallback: String = entry.displayName): String {
    val active by AiGate.active.collectAsState()
    val revision by AiTitles.version.collectAsState()
    return if (active && revision >= 0) AiTitles.get(entry.displayName) ?: fallback else fallback
}

/** Similaires dans l'ordre proposé par l'IA ([keys]) ; ceux qu'elle n'a pas classés restent derrière, dans l'ordre du moteur. */
fun List<RecommendedMedia>.withAiOrder(keys: List<String>?): List<RecommendedMedia> {
    if (keys.isNullOrEmpty() || size < 2) return this
    val rank = keys.withIndex().associate { it.value to it.index }
    return sortedBy { rank[it.entry.key] ?: Int.MAX_VALUE }
}

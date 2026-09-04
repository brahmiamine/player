package fr.streamia.tv.recommendation

import fr.streamia.tv.domain.MediaEntry
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

enum class RecommendationFeedbackKind {
    MoreLikeThis,
    LessLikeThis,
}

data class RecommendationSignal(
    val entryKey: String,
    val weight: Double,
    val occurredAtMillis: Long,
    val explicit: Boolean = false,
)

data class RecommendationCandidate(
    val entry: MediaEntry,
    val score: Double,
    val reason: String? = null,
)

enum class SecondarySlotKind {
    RecentStrongEvent,
    BecauseYouWatched,
    LiveNow,
    NewForYou,
    RecentTaste,
}

data class SecondarySlotCandidate(
    val kind: SecondarySlotKind,
    val quality: Double,
    val occurredAtMillis: Long = 0L,
)

internal fun decayedSignalWeight(
    initialWeight: Double,
    ageMillis: Long,
    halfLifeMillis: Long,
): Double {
    if (initialWeight == 0.0) return 0.0
    if (halfLifeMillis <= 0L) return initialWeight
    val safeAge = ageMillis.coerceAtLeast(0L).toDouble()
    return initialWeight * 0.5.pow(safeAge / halfLifeMillis.toDouble())
}

/**
 * Mesure la quantité de signal exploitable, pas le nombre brut d'éléments vus.
 * Un choix explicite (+/-) apporte davantage d'information qu'une lecture passive.
 *
 * La courbe saturante évite qu'un gros historique écrase définitivement les nouveaux signaux et
 * garde une valeur stable dans [0, 1] pour piloter le cold start de l'interface.
 */
internal fun profileConfidence(signals: List<RecommendationSignal>): Double {
    if (signals.isEmpty()) return 0.0
    val evidence = signals.sumOf { signal ->
        abs(signal.weight).coerceAtMost(MAX_SIGNAL_EVIDENCE) * if (signal.explicit) EXPLICIT_EVIDENCE_MULTIPLIER else 1.0
    }
    return (1.0 - exp(-evidence / CONFIDENCE_SCALE)).coerceIn(0.0, 1.0)
}

/** Exclusions produit appliquées avant tout ranking coûteux. */
internal fun filterRecommendationCandidates(
    candidates: List<RecommendationCandidate>,
    hiddenEntryKeys: Set<String>,
    hiddenCategoryIds: Set<String>,
): List<RecommendationCandidate> = candidates.filterNot { candidate ->
    candidate.entry.key in hiddenEntryKeys || candidate.entry.categoryId in hiddenCategoryIds
}

/**
 * Ordre strict et reproductible : score décroissant, puis clé média stable.
 * Aucun aléatoire n'est autorisé dans le ranking d'un snapshot.
 */
internal fun rankRecommendations(candidates: List<RecommendationCandidate>): List<RecommendationCandidate> =
    candidates.sortedWith(
        compareByDescending<RecommendationCandidate> { it.score }
            .thenBy { it.entry.key },
    )

/**
 * Le second slot de l'accueil suit toujours la même règle d'arbitrage. La qualité ne peut jamais
 * faire passer un type de rangée devant un type plus prioritaire : elle sert d'abord de garde-fou,
 * puis départage deux candidats du même type.
 */
internal fun chooseSecondarySlot(
    candidates: List<SecondarySlotCandidate>,
    minimumQuality: Double,
): SecondarySlotCandidate? {
    val priority = mapOf(
        SecondarySlotKind.RecentStrongEvent to 0,
        SecondarySlotKind.BecauseYouWatched to 1,
        SecondarySlotKind.LiveNow to 2,
        SecondarySlotKind.NewForYou to 3,
        SecondarySlotKind.RecentTaste to 4,
    )
    return candidates
        .asSequence()
        .filter { it.quality >= minimumQuality }
        .sortedWith(
            compareBy<SecondarySlotCandidate> { priority.getValue(it.kind) }
                .thenByDescending { it.quality }
                .thenByDescending { it.occurredAtMillis },
        )
        .firstOrNull()
}

private const val EXPLICIT_EVIDENCE_MULTIPLIER = 1.5
private const val MAX_SIGNAL_EVIDENCE = 1.5
private const val CONFIDENCE_SCALE = 4.0

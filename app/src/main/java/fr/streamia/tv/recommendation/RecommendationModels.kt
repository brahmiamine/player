package fr.streamia.tv.recommendation

import fr.streamia.tv.domain.MediaEntry
import kotlin.math.max

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
): Double = initialWeight

internal fun profileConfidence(signals: List<RecommendationSignal>): Double = 0.0

internal fun filterRecommendationCandidates(
    candidates: List<RecommendationCandidate>,
    hiddenEntryKeys: Set<String>,
    hiddenCategoryIds: Set<String>,
): List<RecommendationCandidate> = candidates

internal fun rankRecommendations(candidates: List<RecommendationCandidate>): List<RecommendationCandidate> = candidates

internal fun chooseSecondarySlot(
    candidates: List<SecondarySlotCandidate>,
    minimumQuality: Double,
): SecondarySlotCandidate? = candidates.firstOrNull()

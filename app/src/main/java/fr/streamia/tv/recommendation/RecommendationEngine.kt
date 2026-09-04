package fr.streamia.tv.recommendation

import fr.streamia.tv.domain.MediaEntry

enum class RecommendationRowKind {
    Discover,
    ForYou,
    BecauseYouWatched,
    LiveNow,
    NewForYou,
    RecentTaste,
}

data class RecommendedMedia(
    val entry: MediaEntry,
    val score: Double,
    val reason: String? = null,
)

data class RecommendationRow(
    val kind: RecommendationRowKind,
    val title: String,
    val items: List<RecommendedMedia>,
)

data class RecommendationSnapshot(
    val profileId: String,
    val generatedAtMillis: Long,
    val confidence: Double,
    val rows: List<RecommendationRow>,
)

data class ViewingRecord(
    val entry: MediaEntry,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMillis: Long,
)

data class RecommendationFeedback(
    val entry: MediaEntry,
    val kind: RecommendationFeedbackKind,
    val occurredAtMillis: Long,
)

data class RecommendationProfileInput(
    val history: List<ViewingRecord> = emptyList(),
    val favoriteEntries: Set<String> = emptySet(),
    val watchedEntries: Set<String> = emptySet(),
    val knownEntriesByKey: Map<String, MediaEntry> = emptyMap(),
    val feedback: Map<String, RecommendationFeedback> = emptyMap(),
    val hiddenEntries: Set<String> = emptySet(),
    val hiddenCategoryIds: Set<String> = emptySet(),
)

data class RecommendationBuildContext(
    val candidates: List<MediaEntry>,
    val detailsByKey: Map<String, ContentFeatures> = emptyMap(),
    val profile: RecommendationProfileInput = RecommendationProfileInput(),
    val liveNow: List<RecommendedMedia> = emptyList(),
    val nowMillis: Long,
)

class RecommendationEngine(
    private val similarityEngine: ContentSimilarityEngine = MetadataSimilarityEngine(),
) {
    fun buildSnapshot(profileId: String, context: RecommendationBuildContext): RecommendationSnapshot =
        RecommendationSnapshot(profileId, context.nowMillis, 0.0, emptyList())

    fun similarTo(
        source: ContentFeatures,
        candidates: List<MediaEntry>,
        detailsByKey: Map<String, ContentFeatures> = emptyMap(),
        hiddenEntries: Set<String> = emptySet(),
        hiddenCategoryIds: Set<String> = emptySet(),
        limit: Int = 12,
    ): List<RecommendedMedia> = emptyList()
}

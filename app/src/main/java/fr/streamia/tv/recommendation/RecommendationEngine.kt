package fr.streamia.tv.recommendation

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import kotlin.math.abs
import kotlin.math.pow

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

/**
 * Moteur métier pur et déterministe. Il ne sait rien de SQLite, Compose, Media3 ou du modèle ML :
 * le repository lui fournit un pool déjà borné, puis le moteur calcule au maximum deux rangées.
 */
class RecommendationEngine(
    private val similarityEngine: ContentSimilarityEngine = MetadataSimilarityEngine(),
) {
    fun buildSnapshot(profileId: String, context: RecommendationBuildContext): RecommendationSnapshot {
        val signals = recommendationSignals(context.profile, context.nowMillis)
        val confidence = profileConfidence(signals)
        val lessKeys = context.profile.feedback.values
            .filter { it.kind == RecommendationFeedbackKind.LessLikeThis }
            .mapTo(mutableSetOf()) { it.entry.key }
        val seenKeys = context.profile.history.mapTo(mutableSetOf()) { it.entry.key } + context.profile.watchedEntries

        // Le filtrage d'exclusion arrive avant toute similarité : aucun CPU n'est dépensé pour un
        // contenu masqué/verrouillé ou explicitement rejeté.
        val candidates = context.candidates
            .asSequence()
            .filter { it.type != MediaType.Live }
            .filterNot {
                it.key in context.profile.hiddenEntries ||
                    it.categoryId in context.profile.hiddenCategoryIds ||
                    it.key in lessKeys
            }
            .filterNot { it.key in seenKeys }
            .distinctBy(MediaEntry::key)
            .toList()

        if (candidates.isEmpty()) {
            return RecommendationSnapshot(profileId, context.nowMillis, confidence, emptyList())
        }

        val positiveSources = positiveSources(context.profile, context.detailsByKey, context.nowMillis)
        val negativeSources = negativeSources(context.profile, context.detailsByKey, context.nowMillis)
        val categoryAffinity = categoryAffinity(positiveSources)
        val personalized = confidence >= PERSONALIZED_CONFIDENCE

        val ranked = candidates
            .map { entry ->
                scoreCandidate(
                    entry = entry,
                    context = context,
                    sources = positiveSources,
                    negatives = negativeSources,
                    categoryAffinity = categoryAffinity,
                    personalized = personalized,
                )
            }
            .sortedWith(compareByDescending<ScoredMedia> { it.score }.thenBy { it.entry.key })

        val primaryItems = ranked.take(PRIMARY_LIMIT).map(ScoredMedia::toRecommended)
        if (primaryItems.isEmpty()) {
            return RecommendationSnapshot(profileId, context.nowMillis, confidence, emptyList())
        }

        val primary = RecommendationRow(
            kind = if (personalized) RecommendationRowKind.ForYou else RecommendationRowKind.Discover,
            title = if (personalized) "Recommandé pour vous" else "À découvrir",
            items = primaryItems,
        )

        val usedKeys = primaryItems.mapTo(mutableSetOf()) { it.entry.key }
        val secondaryRows = linkedMapOf<SecondarySlotKind, RecommendationRow>()
        val secondaryCandidates = mutableListOf<SecondarySlotCandidate>()

        strongestRecentSource(context.profile, context.detailsByKey, context.nowMillis)?.let { source ->
            val similar = similarTo(
                source = source.features,
                candidates = candidates.filterNot { it.key in usedKeys },
                detailsByKey = context.detailsByKey,
                hiddenEntries = context.profile.hiddenEntries,
                hiddenCategoryIds = context.profile.hiddenCategoryIds,
                limit = SECONDARY_LIMIT,
            )
            if (similar.size >= MIN_SECONDARY_ITEMS) {
                val slotKind = if (source.explicit) {
                    SecondarySlotKind.RecentStrongEvent
                } else {
                    SecondarySlotKind.BecauseYouWatched
                }
                // Un feedback "Plus comme ça" ne veut pas dire que l'utilisateur a regardé ce
                // contenu : le libellé ne doit affirmer un visionnage que pour un signal de lecture.
                val title = if (source.explicit) {
                    "Parce que vous aimez ${source.features.entry.displayName}"
                } else {
                    "Parce que vous avez regardé ${source.features.entry.displayName}"
                }
                secondaryRows[slotKind] = RecommendationRow(
                    kind = RecommendationRowKind.BecauseYouWatched,
                    title = title,
                    items = similar,
                )
                secondaryCandidates += SecondarySlotCandidate(
                    kind = slotKind,
                    quality = similar.first().score,
                    occurredAtMillis = source.occurredAtMillis,
                )
            }
        }

        // Le direct a son propre flux de données/TTL. Le moteur reçoit seulement le snapshot EPG
        // déjà préparé ; il ne déclenche jamais une actualisation VOD pour rafraîchir le Live.
        val live = context.liveNow
            .asSequence()
            .filterNot {
                it.entry.key in context.profile.hiddenEntries ||
                    it.entry.categoryId in context.profile.hiddenCategoryIds
            }
            .distinctBy { it.entry.key }
            .take(SECONDARY_LIMIT)
            .toList()
        if (live.size >= MIN_SECONDARY_ITEMS) {
            secondaryRows[SecondarySlotKind.LiveNow] = RecommendationRow(
                kind = RecommendationRowKind.LiveNow,
                title = "À la TV maintenant",
                items = live,
            )
            secondaryCandidates += SecondarySlotCandidate(
                kind = SecondarySlotKind.LiveNow,
                quality = live.take(3).map(RecommendedMedia::score).average(),
                occurredAtMillis = context.nowMillis,
            )
        }

        val fresh = ranked
            .asSequence()
            .filterNot { it.entry.key in usedKeys }
            .filter { freshness(it.entry, context.nowMillis) >= MIN_FRESHNESS_FOR_SECONDARY }
            .take(SECONDARY_LIMIT)
            .map(ScoredMedia::toRecommended)
            .toList()
        if (fresh.size >= MIN_SECONDARY_ITEMS) {
            secondaryRows[SecondarySlotKind.NewForYou] = RecommendationRow(
                kind = RecommendationRowKind.NewForYou,
                title = if (personalized) "Nouveautés pour vous" else "Nouveautés",
                items = fresh,
            )
            secondaryCandidates += SecondarySlotCandidate(
                kind = SecondarySlotKind.NewForYou,
                quality = fresh.first().score,
                occurredAtMillis = context.nowMillis,
            )
        }

        val selectedSecondary = chooseSecondarySlot(secondaryCandidates, MIN_SECONDARY_QUALITY)
            ?.let { secondaryRows[it.kind] }

        return RecommendationSnapshot(
            profileId = profileId,
            generatedAtMillis = context.nowMillis,
            confidence = confidence,
            rows = listOfNotNull(primary, selectedSecondary).take(MAX_HOME_AI_ROWS),
        )
    }

    fun similarTo(
        source: ContentFeatures,
        candidates: List<MediaEntry>,
        detailsByKey: Map<String, ContentFeatures> = emptyMap(),
        hiddenEntries: Set<String> = emptySet(),
        hiddenCategoryIds: Set<String> = emptySet(),
        limit: Int = 12,
    ): List<RecommendedMedia> = candidates
        .asSequence()
        .filterNot {
            it.key == source.entry.key ||
                it.key in hiddenEntries ||
                it.categoryId in hiddenCategoryIds
        }
        .distinctBy(MediaEntry::key)
        .map { entry ->
            val result = similarityEngine.compare(source, featuresFor(entry, detailsByKey))
            RecommendedMedia(entry = entry, score = result.score, reason = result.reason)
        }
        .filter { it.score >= MIN_SIMILARITY }
        .sortedWith(compareByDescending<RecommendedMedia> { it.score }.thenBy { it.entry.key })
        .take(limit.coerceAtLeast(0))
        .toList()

    private fun scoreCandidate(
        entry: MediaEntry,
        context: RecommendationBuildContext,
        sources: List<WeightedSource>,
        negatives: List<WeightedSource>,
        categoryAffinity: Map<String, Double>,
        personalized: Boolean,
    ): ScoredMedia {
        val candidateFeatures = featuresFor(entry, context.detailsByKey)
        val closest = sources
            .asSequence()
            .map { source -> source to similarityEngine.compare(source.features, candidateFeatures) }
            .maxByOrNull { (source, similarity) ->
                similarity.score * source.weight.coerceIn(0.0, 1.0)
            }
        val similarityScore = closest?.let { (source, similarity) ->
            similarity.score * source.weight.coerceIn(0.0, 1.0)
        } ?: 0.0
        val categoryScore = categoryAffinity[entry.categoryId] ?: 0.0
        val ratingScore = normalizedRating(entry.rating)
        val freshnessScore = freshness(entry, context.nowMillis)
        val descriptionScore = if (!candidateFeatures.plot.isNullOrBlank()) 1.0 else 0.0
        val negativePenalty = negatives.maxOfOrNull { source ->
            similarityEngine.compare(source.features, candidateFeatures).score *
                abs(source.weight).coerceIn(0.0, 1.0)
        } ?: 0.0

        val explicitBoost = context.profile.feedback[entry.key]
            ?.takeIf { it.kind == RecommendationFeedbackKind.MoreLikeThis }
            ?.let { EXPLICIT_ITEM_BOOST }
            ?: 0.0

        val score = if (personalized) {
            0.46 * similarityScore +
                0.24 * categoryScore +
                0.14 * ratingScore +
                0.10 * freshnessScore +
                0.06 * descriptionScore -
                NEGATIVE_SIMILARITY_PENALTY * negativePenalty +
                explicitBoost
        } else {
            0.62 * ratingScore + 0.28 * freshnessScore + 0.10 * descriptionScore
        }

        val reason = when {
            personalized && closest?.second?.reason != null -> closest.second.reason
            personalized && categoryScore >= 0.55 -> "Dans vos genres préférés"
            ratingScore >= 0.80 -> "Très bien noté"
            freshnessScore >= 0.70 -> "Ajout récent"
            else -> null
        }

        return ScoredMedia(entry, score.coerceIn(0.0, 1.0), reason)
    }

    private fun recommendationSignals(
        profile: RecommendationProfileInput,
        nowMillis: Long,
    ): List<RecommendationSignal> = buildList {
        profile.history.forEach { record ->
            val weight = playbackWeight(record, nowMillis)
            if (weight != 0.0) {
                add(RecommendationSignal(record.entry.key, weight, record.updatedAtMillis))
            }
        }
        profile.favoriteEntries.forEach { key ->
            add(RecommendationSignal(key, FAVORITE_WEIGHT, nowMillis))
        }
        profile.watchedEntries.forEach { key ->
            add(RecommendationSignal(key, WATCHED_WEIGHT, nowMillis))
        }
        profile.feedback.values.forEach { feedback ->
            val positive = feedback.kind == RecommendationFeedbackKind.MoreLikeThis
            val initial = if (positive) POSITIVE_FEEDBACK_WEIGHT else NEGATIVE_FEEDBACK_WEIGHT
            val halfLife = if (positive) POSITIVE_FEEDBACK_HALF_LIFE_MS else NEGATIVE_FEEDBACK_HALF_LIFE_MS
            add(
                RecommendationSignal(
                    entryKey = feedback.entry.key,
                    weight = decayedSignalWeight(
                        initialWeight = initial,
                        ageMillis = nowMillis - feedback.occurredAtMillis,
                        halfLifeMillis = halfLife,
                    ),
                    occurredAtMillis = feedback.occurredAtMillis,
                    explicit = true,
                ),
            )
        }
    }

    private fun positiveSources(
        profile: RecommendationProfileInput,
        details: Map<String, ContentFeatures>,
        nowMillis: Long,
    ): List<WeightedSource> = buildList {
        profile.history.forEach { record ->
            val weight = playbackWeight(record, nowMillis)
            if (weight > MIN_POSITIVE_SOURCE_WEIGHT) {
                add(
                    WeightedSource(
                        features = featuresFor(record.entry, details),
                        weight = weight,
                        occurredAtMillis = record.updatedAtMillis,
                    ),
                )
            }
        }
        profile.favoriteEntries.forEach { key ->
            profile.knownEntriesByKey[key]?.let { entry ->
                add(
                    WeightedSource(
                        features = featuresFor(entry, details),
                        weight = FAVORITE_WEIGHT,
                        occurredAtMillis = nowMillis,
                    ),
                )
            }
        }
        profile.feedback.values
            .filter { it.kind == RecommendationFeedbackKind.MoreLikeThis }
            .forEach { feedback ->
                add(
                    WeightedSource(
                        features = featuresFor(feedback.entry, details),
                        weight = decayedSignalWeight(
                            POSITIVE_FEEDBACK_WEIGHT,
                            nowMillis - feedback.occurredAtMillis,
                            POSITIVE_FEEDBACK_HALF_LIFE_MS,
                        ),
                        occurredAtMillis = feedback.occurredAtMillis,
                        explicit = true,
                    ),
                )
            }
    }
        .sortedWith(
            compareByDescending<WeightedSource> { it.weight }
                .thenByDescending { it.occurredAtMillis },
        )
        .take(MAX_TASTE_SOURCES)

    private fun negativeSources(
        profile: RecommendationProfileInput,
        details: Map<String, ContentFeatures>,
        nowMillis: Long,
    ): List<WeightedSource> = profile.feedback.values
        .asSequence()
        .filter { it.kind == RecommendationFeedbackKind.LessLikeThis }
        .map { feedback ->
            WeightedSource(
                features = featuresFor(feedback.entry, details),
                weight = decayedSignalWeight(
                    NEGATIVE_FEEDBACK_WEIGHT,
                    nowMillis - feedback.occurredAtMillis,
                    NEGATIVE_FEEDBACK_HALF_LIFE_MS,
                ),
                occurredAtMillis = feedback.occurredAtMillis,
                explicit = true,
            )
        }
        .sortedBy { it.weight }
        .take(MAX_TASTE_SOURCES)
        .toList()

    private fun strongestRecentSource(
        profile: RecommendationProfileInput,
        details: Map<String, ContentFeatures>,
        nowMillis: Long,
    ): WeightedSource? {
        val explicit = profile.feedback.values
            .asSequence()
            .filter { it.kind == RecommendationFeedbackKind.MoreLikeThis }
            .maxByOrNull { it.occurredAtMillis }
            ?.let {
                WeightedSource(
                    features = featuresFor(it.entry, details),
                    weight = POSITIVE_FEEDBACK_WEIGHT,
                    occurredAtMillis = it.occurredAtMillis,
                    explicit = true,
                )
            }
        if (explicit != null) return explicit

        return profile.history
            .asSequence()
            .map { record ->
                WeightedSource(
                    features = featuresFor(record.entry, details),
                    weight = playbackWeight(record, nowMillis),
                    occurredAtMillis = record.updatedAtMillis,
                )
            }
            .filter { it.weight >= STRONG_PLAYBACK_SOURCE_WEIGHT }
            .maxByOrNull { it.occurredAtMillis }
    }

    private fun categoryAffinity(sources: List<WeightedSource>): Map<String, Double> {
        if (sources.isEmpty()) return emptyMap()
        val raw = linkedMapOf<String, Double>()
        sources.forEach { source ->
            val category = source.features.entry.categoryId
            raw[category] = (raw[category] ?: 0.0) + source.weight.coerceAtLeast(0.0)
        }
        val maximum = raw.values.maxOrNull()?.takeIf { it > 0.0 } ?: return emptyMap()
        return raw.mapValues { (_, value) -> (value / maximum).coerceIn(0.0, 1.0) }
    }

    private fun playbackWeight(record: ViewingRecord, nowMillis: Long): Double {
        val base = when {
            record.durationMs > 0L -> {
                val progress = (record.positionMs.toDouble() / record.durationMs).coerceIn(0.0, 1.0)
                when {
                    progress >= 0.90 -> 1.0
                    progress >= 0.60 -> 0.72
                    progress >= 0.25 -> 0.35
                    record.positionMs >= MIN_MEANINGFUL_POSITION_MS -> 0.10
                    else -> 0.0
                }
            }
            record.positionMs >= LONG_UNKNOWN_DURATION_POSITION_MS -> 0.40
            record.positionMs >= MIN_MEANINGFUL_POSITION_MS -> 0.15
            else -> 0.0
        }
        return decayedSignalWeight(
            initialWeight = base,
            ageMillis = nowMillis - record.updatedAtMillis,
            halfLifeMillis = PLAYBACK_HALF_LIFE_MS,
        )
    }

    private fun featuresFor(
        entry: MediaEntry,
        details: Map<String, ContentFeatures>,
    ): ContentFeatures = details[entry.key] ?: ContentFeatures.from(entry)

    private fun normalizedRating(rating: Double?): Double =
        rating?.let { value ->
            (if (value > 10.0) value / 20.0 else value / 10.0).coerceIn(0.0, 1.0)
        } ?: DEFAULT_RATING_SCORE

    private fun freshness(entry: MediaEntry, nowMillis: Long): Double {
        val addedAtMillis = entry.addedAtEpochSeconds?.times(1_000L) ?: return DEFAULT_FRESHNESS_SCORE
        val ageMillis = (nowMillis - addedAtMillis).coerceAtLeast(0L)
        return 0.5.pow(ageMillis.toDouble() / FRESHNESS_HALF_LIFE_MS).coerceIn(0.0, 1.0)
    }

    private data class WeightedSource(
        val features: ContentFeatures,
        val weight: Double,
        val occurredAtMillis: Long,
        val explicit: Boolean = false,
    )

    private data class ScoredMedia(
        val entry: MediaEntry,
        val score: Double,
        val reason: String?,
    ) {
        fun toRecommended() = RecommendedMedia(entry, score, reason)
    }

    private companion object {
        const val PERSONALIZED_CONFIDENCE = 0.30
        const val PRIMARY_LIMIT = 12
        const val SECONDARY_LIMIT = 12
        const val MAX_HOME_AI_ROWS = 2
        const val MIN_SECONDARY_ITEMS = 2
        const val MIN_SECONDARY_QUALITY = 0.18
        const val MIN_SIMILARITY = 0.06
        const val MIN_FRESHNESS_FOR_SECONDARY = 0.65
        const val MIN_POSITIVE_SOURCE_WEIGHT = 0.15
        const val STRONG_PLAYBACK_SOURCE_WEIGHT = 0.60
        const val MAX_TASTE_SOURCES = 8
        const val FAVORITE_WEIGHT = 1.20
        const val WATCHED_WEIGHT = 0.70
        const val POSITIVE_FEEDBACK_WEIGHT = 1.50
        const val NEGATIVE_FEEDBACK_WEIGHT = -1.50
        const val EXPLICIT_ITEM_BOOST = 0.10
        const val NEGATIVE_SIMILARITY_PENALTY = 0.35
        const val DEFAULT_RATING_SCORE = 0.45
        const val DEFAULT_FRESHNESS_SCORE = 0.35
        const val MIN_MEANINGFUL_POSITION_MS = 120_000L
        const val LONG_UNKNOWN_DURATION_POSITION_MS = 600_000L
        const val DAY_MS = 86_400_000L
        const val PLAYBACK_HALF_LIFE_MS = 120L * DAY_MS
        const val POSITIVE_FEEDBACK_HALF_LIFE_MS = 240L * DAY_MS
        const val NEGATIVE_FEEDBACK_HALF_LIFE_MS = 60L * DAY_MS
        const val FRESHNESS_HALF_LIFE_MS = 365L * DAY_MS
    }
}

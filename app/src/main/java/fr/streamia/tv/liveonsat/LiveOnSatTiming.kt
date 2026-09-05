package fr.streamia.tv.liveonsat

import fr.streamia.tv.domain.EpgGuide
import java.text.Normalizer
import kotlin.math.abs

/**
 * Enrichit un match LiveOnSat avec la fenêtre EPG la plus proche parmi les chaînes réellement
 * reconnues dans la playlist. Une fenêtre incohérente est ignorée afin que le fallback de 2 h
 * reste préférable à un faux badge "EN DIRECT".
 */
fun ResolvedLiveOnSatMatch.withEpgTiming(guide: EpgGuide?): ResolvedLiveOnSatMatch {
    if (guide == null || matchedChannels.isEmpty()) return copy(
        epgStartEpochSeconds = null,
        epgEndEpochSeconds = null,
    )

    val sourceStart = match.startEpochSeconds
    val best = matchedChannels.values
        .asSequence()
        .distinctBy { it.key }
        .flatMap { channel -> guide.forEntry(channel).asSequence() }
        .mapNotNull { program ->
            if (!programMatchesParticipants(program.title, program.description, match.participantA, match.participantB)) {
                return@mapNotNull null
            }
            val start = program.startEpochSeconds ?: return@mapNotNull null
            val end = program.endEpochSeconds ?: return@mapNotNull null
            val duration = end - start
            val drift = abs(start - sourceStart)
            if (
                end <= start ||
                duration < MIN_RELIABLE_EPG_DURATION_SECONDS ||
                drift > MAX_EPG_START_DRIFT_SECONDS ||
                end <= sourceStart + MIN_REMAINING_AFTER_SOURCE_START_SECONDS
            ) {
                return@mapNotNull null
            }
            Triple(drift, start, end)
        }
        .minWithOrNull(compareBy<Triple<Long, Long, Long>> { it.first }.thenBy { it.second })

    return if (best == null) {
        copy(epgStartEpochSeconds = null, epgEndEpochSeconds = null)
    } else {
        copy(epgStartEpochSeconds = best.second, epgEndEpochSeconds = best.third)
    }
}

private fun programMatchesParticipants(
    title: String,
    description: String?,
    participantA: String,
    participantB: String,
): Boolean {
    val haystack = normalizeMatchText(title + " " + description.orEmpty())
    return participantMatches(haystack, participantA) && participantMatches(haystack, participantB)
}

private fun participantMatches(haystack: String, participant: String): Boolean =
    normalizeMatchText(participant)
        .split(' ')
        .asSequence()
        .filter { it.length >= MATCH_TOKEN_MIN_LENGTH && it !in MATCH_TOKEN_NOISE }
        .any { token -> Regex("(^| )" + Regex.escape(token) + "( |$)").containsMatchIn(haystack) }

private fun normalizeMatchText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()
        .replace(NON_ALNUM, " ")
        .replace(WHITESPACE, " ")
        .trim()

fun ResolvedLiveOnSatMatch.effectiveStartEpochSeconds(): Long {
    val epgStart = epgStartEpochSeconds
    val epgEnd = epgEndEpochSeconds
    return if (epgStart != null && epgEnd != null && epgEnd > epgStart) {
        maxOf(match.startEpochSeconds, epgStart)
    } else {
        match.startEpochSeconds
    }
}

fun ResolvedLiveOnSatMatch.effectiveEndEpochSeconds(): Long {
    val epgStart = epgStartEpochSeconds
    val epgEnd = epgEndEpochSeconds
    return if (epgStart != null && epgEnd != null && epgEnd > epgStart) {
        epgEnd
    } else {
        match.startEpochSeconds + LIVE_FALLBACK_DURATION_SECONDS
    }
}

fun ResolvedLiveOnSatMatch.isLiveAt(nowEpochSeconds: Long): Boolean =
    nowEpochSeconds >= effectiveStartEpochSeconds() && nowEpochSeconds < effectiveEndEpochSeconds()

fun ResolvedLiveOnSatMatch.isVisibleAt(nowEpochSeconds: Long): Boolean =
    nowEpochSeconds < effectiveEndEpochSeconds()

private const val LIVE_FALLBACK_DURATION_SECONDS = 2 * 60 * 60L
private const val MAX_EPG_START_DRIFT_SECONDS = 45 * 60L
private const val MIN_RELIABLE_EPG_DURATION_SECONDS = 45 * 60L
private const val MIN_REMAINING_AFTER_SOURCE_START_SECONDS = 30 * 60L
private const val MATCH_TOKEN_MIN_LENGTH = 3
private val MATCH_TOKEN_NOISE = setOf("the", "club", "football", "futbol")
private val COMBINING_MARKS = Regex("\\p{M}+")
private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
private val WHITESPACE = Regex("\\s+")

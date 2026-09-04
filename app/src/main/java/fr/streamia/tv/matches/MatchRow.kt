package fr.streamia.tv.matches

import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaEntry
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId

enum class MatchTemporalState {
    Live,
    Today,
    Tomorrow,
    ThisWeek,
}

data class MatchEvent(
    val channel: MediaEntry,
    val sport: MatchSport,
    val participantA: String,
    val participantB: String,
    val competition: String?,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val confidence: Double,
    val sourceTitle: String,
) {
    /** Fusionne un même match diffusé sur plusieurs chaînes/langues : sport + adversaires (ordre
     * indifférent) + créneau arrondi au quart d'heure. Volontairement conservateur : un léger
     * décalage d'orthographe entre fournisseurs peut laisser deux cartes plutôt que de fusionner
     * deux événements en réalité différents. */
    val fingerprint: String = matchFingerprint(sport, participantA, participantB, startEpochSeconds)
}

data class MatchRowItem(
    val event: MatchEvent,
    val temporalState: MatchTemporalState,
)

data class MatchRow(
    val title: String,
    val items: List<MatchRowItem>,
)

internal fun matchFingerprint(
    sport: MatchSport,
    participantA: String,
    participantB: String,
    startEpochSeconds: Long,
): String {
    val names = listOf(normalizeParticipant(participantA), normalizeParticipant(participantB)).sorted()
    val bucket = startEpochSeconds / FINGERPRINT_TIME_BUCKET_SECONDS
    return "${sport.name}|${names[0]}|${names[1]}|$bucket"
}

private fun normalizeParticipant(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .trim()

private const val FINGERPRINT_TIME_BUCKET_SECONDS = 900L

/**
 * `null` si l'événement est déjà terminé ou trop loin dans le futur pour la fenêtre couverte
 * ([windowDays], bornée par ce que le fournisseur EPG donne réellement — Streamia n'invente pas
 * de matchs au-delà de ce que l'EPG couvre).
 */
internal fun matchTemporalState(
    startEpochSeconds: Long,
    endEpochSeconds: Long,
    nowEpochSeconds: Long,
    windowDays: Long = 7L,
): MatchTemporalState? {
    if (endEpochSeconds <= nowEpochSeconds) return null
    if (startEpochSeconds <= nowEpochSeconds) return MatchTemporalState.Live
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochSecond(nowEpochSeconds).atZone(zone).toLocalDate()
    val startDay = Instant.ofEpochSecond(startEpochSeconds).atZone(zone).toLocalDate()
    return when {
        startDay == today -> MatchTemporalState.Today
        startDay == today.plusDays(1) -> MatchTemporalState.Tomorrow
        startEpochSeconds <= nowEpochSeconds + windowDays * 86_400L -> MatchTemporalState.ThisWeek
        else -> null
    }
}

/**
 * Moteur métier pur et déterministe, même philosophie que
 * [fr.streamia.tv.recommendation.RecommendationEngine] : classification à la synchronisation EPG
 * (pas à l'ouverture de l'accueil), exclusions avant tout calcul, aucun aléatoire dans l'ordre.
 */
class MatchRowEngine(
    private val detector: StructuredMatchDetector = StructuredMatchDetector(),
) {
    fun buildRow(
        programsByChannel: Map<MediaEntry, List<EpgProgram>>,
        nowEpochSeconds: Long,
        hiddenEntryKeys: Set<String> = emptySet(),
        hiddenCategoryIds: Set<String> = emptySet(),
        limit: Int = ROW_LIMIT,
    ): MatchRow? {
        val events = programsByChannel
            .asSequence()
            .filterNot { (channel, _) -> channel.key in hiddenEntryKeys || channel.categoryId in hiddenCategoryIds }
            .flatMap { (channel, programs) ->
                programs.asSequence().mapNotNull { program -> toMatchEvent(channel, program, nowEpochSeconds) }
            }
            .toList()

        val items = deduplicate(events)
            .mapNotNull { event ->
                matchTemporalState(event.startEpochSeconds, event.endEpochSeconds, nowEpochSeconds)
                    ?.let { state -> MatchRowItem(event, state) }
            }
            .sortedWith(
                compareBy<MatchRowItem> { it.temporalState != MatchTemporalState.Live }
                    .thenBy { it.event.startEpochSeconds }
                    .thenBy { it.event.fingerprint },
            )
            .take(limit)

        if (items.isEmpty()) return null
        return MatchRow(title = rowTitle(items), items = items)
    }

    private fun toMatchEvent(channel: MediaEntry, program: EpgProgram, nowEpochSeconds: Long): MatchEvent? {
        val start = program.startEpochSeconds ?: return null
        val end = program.endEpochSeconds ?: return null
        if (end <= start || end <= nowEpochSeconds) return null
        val detection = detector.detect(program.title, program.description, program.category)
        val sport = detection.sport
        val participantA = detection.participantA
        val participantB = detection.participantB
        if (!detection.isMatch || sport == null || participantA == null || participantB == null) return null
        return MatchEvent(
            channel = channel,
            sport = sport,
            participantA = participantA,
            participantB = participantB,
            competition = detection.competition,
            startEpochSeconds = start,
            endEpochSeconds = end,
            confidence = detection.confidence,
            sourceTitle = program.title,
        )
    }

    /** Le premier événement rencontré pour une empreinte donnée gagne, par ordre stable des chaînes
     * (v1 : pas encore de source préférée basée sur le profil de recommandations). */
    private fun deduplicate(events: List<MatchEvent>): List<MatchEvent> {
        val seen = linkedMapOf<String, MatchEvent>()
        events
            .sortedWith(compareBy({ it.channel.key }, { it.startEpochSeconds }))
            .forEach { event -> seen.putIfAbsent(event.fingerprint, event) }
        return seen.values.toList()
    }

    private fun rowTitle(items: List<MatchRowItem>): String {
        val states = items.mapTo(mutableSetOf(), MatchRowItem::temporalState)
        return when {
            MatchTemporalState.Live in states -> "🔴 Matchs en direct"
            MatchTemporalState.Today in states -> "⚽ Matchs aujourd'hui"
            MatchTemporalState.Tomorrow in states -> "⚽ Matchs demain"
            else -> "⚽ Matchs cette semaine"
        }
    }

    private companion object {
        const val ROW_LIMIT = 12
    }
}

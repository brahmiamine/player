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
     * indifférent). Volontairement **sans** l'horaire : l'heure sert au regroupement des diffusions
     * proches dans [MatchRowEngine], pas à l'identité du match. */
    val identityKey: String = matchIdentityKey(sport, participantA, participantB)

    /** Identité + créneau d'annonce : clé stable de la carte affichée. */
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

data class HomeMatchRows(
    val live: MatchRow?,
    val upcomingToday: MatchRow?,
)

/**
 * Reclasse les cartes déjà détectées sans rescanner l'EPG. Utile pendant que l'accueil reste
 * ouvert : un match à venir passe en direct dès son coup d'envoi et disparaît dès sa fin.
 */
fun HomeMatchRows.reclassifiedAt(nowEpochSeconds: Long): HomeMatchRows {
    val allItems = (live?.items.orEmpty() + upcomingToday?.items.orEmpty())
        .distinctBy { it.event.fingerprint }

    val liveItems = allItems.mapNotNull { item ->
        val state = matchTemporalState(
            item.event.startEpochSeconds,
            item.event.endEpochSeconds,
            nowEpochSeconds,
        )
        if (state == MatchTemporalState.Live) item.copy(temporalState = state) else null
    }.sortedBy { it.event.startEpochSeconds }

    val upcomingItems = allItems.mapNotNull { item ->
        val state = matchTemporalState(
            item.event.startEpochSeconds,
            item.event.endEpochSeconds,
            nowEpochSeconds,
        )
        if (state == MatchTemporalState.Today) item.copy(temporalState = state) else null
    }.sortedBy { it.event.startEpochSeconds }

    return HomeMatchRows(
        live = liveItems.takeIf { it.isNotEmpty() }
            ?.let { MatchRow(title = "🔴 Matchs en direct", items = it) },
        upcomingToday = upcomingItems.takeIf { it.isNotEmpty() }
            ?.let { MatchRow(title = "⚽ Matchs suivants", items = it) },
    )
}

internal fun matchIdentityKey(sport: MatchSport, participantA: String, participantB: String): String {
    val names = listOf(normalizeParticipant(participantA), normalizeParticipant(participantB)).sorted()
    return "${sport.name}|${names[0]}|${names[1]}"
}

internal fun matchFingerprint(
    sport: MatchSport,
    participantA: String,
    participantB: String,
    startEpochSeconds: Long,
): String = "${matchIdentityKey(sport, participantA, participantB)}|${startEpochSeconds / FINGERPRINT_TIME_BUCKET_SECONDS}"

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
        val items = buildItems(
            programsByChannel = programsByChannel,
            nowEpochSeconds = nowEpochSeconds,
            hiddenEntryKeys = hiddenEntryKeys,
            hiddenCategoryIds = hiddenCategoryIds,
        ).take(limit)

        if (items.isEmpty()) return null
        return MatchRow(title = rowTitle(items), items = items)
    }

    /**
     * Accueil : deux rangées strictement séparées.
     * live contient uniquement les programmes EPG réellement en cours.
     * upcomingToday contient uniquement les matchs qui commencent plus tard aujourd'hui.
     * Demain et le reste de la semaine restent volontairement hors de l'accueil.
     */
    fun buildHomeRows(
        programsByChannel: Map<MediaEntry, List<EpgProgram>>,
        nowEpochSeconds: Long,
        hiddenEntryKeys: Set<String> = emptySet(),
        hiddenCategoryIds: Set<String> = emptySet(),
        limit: Int = ROW_LIMIT,
    ): HomeMatchRows {
        val items = buildItems(
            programsByChannel = programsByChannel,
            nowEpochSeconds = nowEpochSeconds,
            hiddenEntryKeys = hiddenEntryKeys,
            hiddenCategoryIds = hiddenCategoryIds,
        )

        val liveItems = items
            .asSequence()
            .filter { it.temporalState == MatchTemporalState.Live }
            .take(limit)
            .toList()
        val upcomingItems = items
            .asSequence()
            .filter { it.temporalState == MatchTemporalState.Today }
            .take(limit)
            .toList()

        return HomeMatchRows(
            live = liveItems.takeIf { it.isNotEmpty() }
                ?.let { MatchRow(title = "🔴 Matchs en direct", items = it) },
            upcomingToday = upcomingItems.takeIf { it.isNotEmpty() }
                ?.let { MatchRow(title = "⚽ Matchs suivants", items = it) },
        )
    }

    private fun buildItems(
        programsByChannel: Map<MediaEntry, List<EpgProgram>>,
        nowEpochSeconds: Long,
        hiddenEntryKeys: Set<String>,
        hiddenCategoryIds: Set<String>,
    ): List<MatchRowItem> {
        val events = programsByChannel
            .asSequence()
            .filterNot { (channel, _) -> channel.key in hiddenEntryKeys || channel.categoryId in hiddenCategoryIds }
            .flatMap { (channel, programs) ->
                programs.asSequence().mapNotNull { program -> toMatchEvent(channel, program, nowEpochSeconds) }
            }
            .toList()

        return sortedItems(
            deduplicate(events).mapNotNull { event ->
                matchTemporalState(event.startEpochSeconds, event.endEpochSeconds, nowEpochSeconds)
                    ?.let { state -> MatchRowItem(event, state) }
            },
        )
    }

    /**
     * Fusionne plusieurs jours lus séquentiellement depuis SQLite sans conserver toutes les grilles
     * EPG en mémoire. Les programmes qui chevauchent minuit peuvent exister dans deux lectures de
     * journée : l'empreinte du match les déduplique ici.
     */
    fun mergeRows(rows: List<MatchRow>, limit: Int = ROW_LIMIT): MatchRow? {
        val seen = linkedMapOf<String, MatchRowItem>()
        rows.asSequence()
            .flatMap { it.items.asSequence() }
            .forEach { item -> seen.putIfAbsent(item.event.fingerprint, item) }

        val items = sortedItems(seen.values.toList()).take(limit)
        if (items.isEmpty()) return null
        return MatchRow(title = rowTitle(items), items = items)
    }

    private fun toMatchEvent(channel: MediaEntry, program: EpgProgram, nowEpochSeconds: Long): MatchEvent? {
        val start = program.startEpochSeconds ?: return null
        val end = program.endEpochSeconds ?: return null
        if (end <= start || end <= nowEpochSeconds) return null
        // Écrémer sans normalisation : un titre sans séparateur ne peut pas être un match, on saute
        // donc le détecteur complet (coûteux) pour la quasi-totalité des programmes de la grille.
        if (!detector.mightBeMatch(program.title)) return null
        val detection = detector.detect(
            title = program.title,
            description = program.description,
            category = program.category,
            channelName = "${channel.displayName} ${channel.name}",
        )
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

    /**
     * Fusionne les diffusions d'un même match. L'identité (`identityKey`) ignore volontairement
     * l'heure de départ : deux fournisseurs qui annoncent le même match à quelques minutes d'écart
     * produisent une seule carte, alors qu'un découpage en créneaux fixes en créait deux dès que les
     * horaires tombaient de part et d'autre d'une frontière (20:59 et 21:01 donnaient deux empreintes).
     *
     * Les diffusions d'un même match sont regroupées par proximité d'horaire, en comparant chaque
     * annonce au **début du groupe** et non à l'annonce précédente : sans cela, une chaîne de matchs
     * espacés de moins de [MATCH_START_TOLERANCE_SECONDS] finirait par n'en former qu'un.
     *
     * Le gagnant reste choisi par ordre stable des chaînes puis de l'heure — déterministe, identique
     * d'un rafraîchissement à l'autre (v1 : pas encore de source préférée basée sur les habitudes).
     */
    private fun deduplicate(events: List<MatchEvent>): List<MatchEvent> {
        val winners = ArrayList<MatchEvent>(events.size)
        events.groupBy { it.identityKey }.values.forEach { sameMatch ->
            val byStart = sameMatch.sortedWith(compareBy({ it.startEpochSeconds }, { it.channel.key }))
            var clusterStart = byStart.first().startEpochSeconds
            var winner = byStart.first()
            byStart.drop(1).forEach { event ->
                if (event.startEpochSeconds - clusterStart > MATCH_START_TOLERANCE_SECONDS) {
                    winners += winner
                    clusterStart = event.startEpochSeconds
                    winner = event
                } else if (STREAM_ORDER.compare(event, winner) < 0) {
                    winner = event
                }
            }
            winners += winner
        }
        return winners
    }

    private fun sortedItems(items: List<MatchRowItem>): List<MatchRowItem> =
        items.sortedWith(
            compareBy<MatchRowItem> { it.temporalState != MatchTemporalState.Live }
                .thenBy { it.event.startEpochSeconds }
                .thenBy { it.event.fingerprint },
        )

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

        /**
         * Écart maximal entre deux annonces du même match. Couvre la dérive d'horaire habituelle
         * entre fournisseurs EPG (début du pré-show chez l'un, coup d'envoi chez l'autre) tout en
         * restant très en dessous du délai qui séparerait deux diffusions distinctes d'une même
         * affiche.
         */
        const val MATCH_START_TOLERANCE_SECONDS = 45 * 60L

        /** Ordre stable et déterministe de choix de la chaîne diffusant un match. */
        val STREAM_ORDER = compareBy<MatchEvent>({ it.channel.key }, { it.startEpochSeconds })
    }
}

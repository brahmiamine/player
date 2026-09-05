package fr.streamia.tv.matches

import java.text.Normalizer

enum class MatchSport {
    Football,
    Basketball,
    Tennis,
    Rugby,
    Handball,
    Hockey,
    Volleyball,
    Combat,
    Other,
}

data class MatchDetection(
    val isMatch: Boolean,
    val confidence: Double,
    val sport: MatchSport?,
    val participantA: String?,
    val participantB: String?,
    val competition: String?,
    val signals: List<String>,
    val negativeSignals: List<String>,
)

/**
 * Détecteur déterministe multilingue pour les programmes EPG.
 *
 * Beaucoup de fournisseurs donnent très peu de métadonnées : parfois le titre contient uniquement
 * "Equipe A / Equipe B", tandis que le seul indice sportif se trouve dans le nom de la chaîne
 * ("beIN SPORTS", "Canal+ Sport", "Sky Sports"...). Le détecteur accepte donc le contexte de chaîne
 * comme signal secondaire, tout en exigeant toujours un vrai motif d'affrontement et en conservant
 * les exclusions fortes (replay, résumé, magazine, journal...).
 */
class StructuredMatchDetector {

    fun detect(
        title: String,
        description: String?,
        category: String?,
        channelName: String? = null,
    ): MatchDetection {
        val normalizedTitle = normalize(title)
        val normalizedDescription = normalize(description.orEmpty())
        val normalizedCategory = normalize(category.orEmpty())
        val normalizedChannel = normalize(channelName.orEmpty())

        if (
            containsAny(normalizedTitle, REPLAY_KEYWORDS) ||
            containsAny(normalizedDescription, REPLAY_KEYWORDS) ||
            containsAny(normalizedCategory, REPLAY_KEYWORDS)
        ) {
            return MatchDetection(false, 0.0, null, null, null, null, emptyList(), listOf("REPLAY"))
        }

        val versus = extractParticipants(title)
        val signals = mutableListOf<String>()
        val negativeSignals = mutableListOf<String>()
        var score = 0.0

        if (versus != null) {
            signals += "VERSUS_PATTERN"
            score += WEIGHT_VERSUS_PATTERN
        }

        // Le titre/description/catégorie EPG restent prioritaires pour déterminer le sport exact.
        // Le nom de chaîne n'est utilisé qu'en repli : une chaîne "beIN SPORTS MAX" confirme qu'un
        // titre "A / B" est bien un événement sportif, sans inventer qu'il s'agit forcément de foot.
        val epgHaystack = "$normalizedTitle $normalizedDescription $normalizedCategory"
        val channelHaystack = normalizedChannel

        val competitionEntry = COMPETITION_SPORT.entries.firstOrNull { (keyword, _) ->
            epgHaystack.contains(keyword)
        }
        val competition = competitionEntry?.key
        if (competition != null) {
            signals += "KNOWN_COMPETITION"
            score += WEIGHT_COMPETITION
        }

        val keywordSport = detectSportKeyword(epgHaystack)
        val channelSport = detectSportKeyword(channelHaystack)
        val genericSportsContext =
            containsAny(normalizedCategory, GENERIC_SPORT_CONTEXT_KEYWORDS) ||
                containsAny(channelHaystack, GENERIC_SPORT_CONTEXT_KEYWORDS)

        val sport = keywordSport
            ?: competitionEntry?.value
            ?: channelSport
            ?: MatchSport.Other.takeIf { versus != null && genericSportsContext }

        when {
            keywordSport != null && containsAny(normalizedCategory, SPORT_KEYWORDS.getValue(keywordSport)) -> {
                signals += "SPORT_CATEGORY"
                score += WEIGHT_SPORT_CATEGORY
            }
            keywordSport != null -> {
                signals += "SPORT_KEYWORD"
                score += WEIGHT_SPORT_KEYWORD
            }
            competitionEntry != null -> {
                signals += "SPORT_FROM_COMPETITION"
                score += WEIGHT_SPORT_KEYWORD
            }
            channelSport != null -> {
                signals += "SPORT_CHANNEL"
                score += WEIGHT_SPORT_CONTEXT
            }
            sport == MatchSport.Other -> {
                signals += "GENERIC_SPORT_CHANNEL"
                score += WEIGHT_SPORT_CONTEXT
            }
        }

        if (containsAny(normalizedDescription, ENCOUNTER_KEYWORDS)) {
            signals += "DESCRIPTION_ENCOUNTER"
            score += WEIGHT_DESCRIPTION
        }

        if (containsAny(normalizedTitle, LIVE_KEYWORDS) || containsAny(normalizedDescription, LIVE_KEYWORDS)) {
            signals += "LIVE_KEYWORD"
            score += WEIGHT_LIVE_BONUS
        }

        if (
            containsAny(normalizedTitle, HIGHLIGHT_KEYWORDS) ||
            containsAny(normalizedDescription, HIGHLIGHT_KEYWORDS)
        ) {
            negativeSignals += "HIGHLIGHTS_OR_SUMMARY"
            score -= WEIGHT_NEGATIVE_STRONG
        }
        if (
            containsAny(normalizedTitle, MAGAZINE_KEYWORDS) ||
            containsAny(normalizedDescription, MAGAZINE_KEYWORDS) ||
            containsAny(normalizedCategory, MAGAZINE_KEYWORDS)
        ) {
            negativeSignals += "MAGAZINE_OR_NEWS"
            score -= WEIGHT_NEGATIVE_STRONG
        }

        val confidence = score.coerceIn(0.0, 1.0)
        val isMatch = versus != null && sport != null && negativeSignals.isEmpty() && confidence >= MIN_CONFIDENCE

        return MatchDetection(
            isMatch = isMatch,
            confidence = confidence,
            sport = sport,
            participantA = versus?.first,
            participantB = versus?.second,
            competition = competition,
            signals = signals,
            negativeSignals = negativeSignals,
        )
    }

    private fun detectSportKeyword(haystack: String): MatchSport? =
        SPORT_KEYWORDS.entries.firstOrNull { (_, keywords) -> containsAny(haystack, keywords) }?.key

    private fun containsAny(haystack: String, needles: List<String>): Boolean =
        needles.any { haystack.contains(it) }

    private fun normalize(value: String): String {
        val stripped = Normalizer.normalize(value, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
        return stripped.lowercase().replace(WHITESPACE, " ").trim()
    }

    /**
     * Accepte les formes réellement rencontrées dans les XMLTV : "vs", "contre", "gegen", "ضد",
     * tirets, "@", mais aussi "/" et "x" (très fréquents chez les fournisseurs IPTV).
     */
    private fun extractParticipants(title: String): Pair<String, String>? {
        val trimmed = title.trim()
        for (separator in VERSUS_SEPARATORS) {
            val index = trimmed.indexOf(separator, ignoreCase = true)
            if (index <= 0) continue
            val left = trimmed.substring(0, index).trim()
            val right = trimmed.substring(index + separator.length).trim()
            if (left.isEmpty() || right.isEmpty()) continue
            if (!looksLikeParticipant(left) || !looksLikeParticipant(right)) continue
            return left to right
        }
        return null
    }

    private fun looksLikeParticipant(text: String): Boolean {
        val words = text.split(WHITESPACE).filter(String::isNotBlank)
        if (words.isEmpty() || words.size > MAX_PARTICIPANT_WORDS) return false
        return words.all { PARTICIPANT_WORD.matches(it) || it == "&" }
    }

    private companion object {
        val COMBINING_MARKS = Regex("\\p{M}+")
        val WHITESPACE = Regex("\\s+")
        // Autorise notamment U21/U19, 76ers, équipes "B"/"II", apostrophes et noms composés.
        val PARTICIPANT_WORD = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}'.+_-]*$")
        const val MAX_PARTICIPANT_WORDS = 7

        // Du plus spécifique au plus ambigu : slash/tiret sont gardés vers la fin.
        val VERSUS_SEPARATORS = listOf(
            " vs. ", " vs ", " v. ", " v ", " contre ", " gegen ", " against ", " ضد ", " @ ",
            " x ", " / ", " - ", " – ", " — ",
        )

        val SPORT_KEYWORDS: Map<MatchSport, List<String>> = mapOf(
            MatchSport.Football to listOf(
                "football", "soccer", "futbol", "calcio", "fussball", "fußball", "foot", "كرة القدم",
            ),
            MatchSport.Basketball to listOf("basketball", "basket", "baloncesto", "كرة السلة"),
            MatchSport.Tennis to listOf("tennis", "tenis", "تنس"),
            MatchSport.Rugby to listOf("rugby"),
            MatchSport.Handball to listOf("handball", "balonmano", "كرة اليد"),
            MatchSport.Hockey to listOf("hockey"),
            MatchSport.Volleyball to listOf("volleyball", "volley", "voleibol"),
            MatchSport.Combat to listOf("ufc", "mma", "boxe", "boxing"),
        )

        /** Un contexte sportif générique confirme l'événement mais ne permet pas de deviner le
         * sport exact. Il produit donc [MatchSport.Other], jamais Football par défaut. */
        val GENERIC_SPORT_CONTEXT_KEYWORDS = listOf(
            "sport", "sports", "bein", "be in", "espn", "eurosport", "dazn",
        )

        val COMPETITION_SPORT: Map<String, MatchSport> = linkedMapOf(
            "ligue 1" to MatchSport.Football,
            "ligue 2" to MatchSport.Football,
            "premier league" to MatchSport.Football,
            "championship" to MatchSport.Football,
            "la liga" to MatchSport.Football,
            "laliga" to MatchSport.Football,
            "serie a" to MatchSport.Football,
            "bundesliga" to MatchSport.Football,
            "super lig" to MatchSport.Football,
            "süper lig" to MatchSport.Football,
            "champions league" to MatchSport.Football,
            "ligue des champions" to MatchSport.Football,
            "europa league" to MatchSport.Football,
            "conference league" to MatchSport.Football,
            "coupe de france" to MatchSport.Football,
            "dfb pokal" to MatchSport.Football,
            "copa del rey" to MatchSport.Football,
            "fa cup" to MatchSport.Football,
            "world cup" to MatchSport.Football,
            "coupe du monde" to MatchSport.Football,
            "afcon" to MatchSport.Football,
            "الدوري" to MatchSport.Football,
            "دوري ابطال" to MatchSport.Football,
            "nba" to MatchSport.Basketball,
            "euroleague" to MatchSport.Basketball,
            "atp" to MatchSport.Tennis,
            "wta" to MatchSport.Tennis,
            "roland garros" to MatchSport.Tennis,
            "wimbledon" to MatchSport.Tennis,
            "ufc" to MatchSport.Combat,
            "top 14" to MatchSport.Rugby,
            "six nations" to MatchSport.Rugby,
        )

        val ENCOUNTER_KEYWORDS = listOf(
            "rencontre", "affronte", "face a", "opposent", "duel", "match", "clash", "derby",
            "partido", "partita", "spiel", "مباراة",
        )

        val LIVE_KEYWORDS = listOf("live", "direct", "en vivo", "مباشر", "vivo", "diretta")

        val HIGHLIGHT_KEYWORDS = listOf(
            "highlights", "highlight", "resume", "best of", "recap", "digest", "sommaire",
            "ملخص", "resumen", "sintesi", "zusammenfassung", "syntese",
        )

        val MAGAZINE_KEYWORDS = listOf(
            "magazine", "magazin", "news", "journal", "actualite", "actualites", "talk", "debrief",
            "chronique", "emission", "podcast", "aktuell", "nachrichten", "preview", "avant match",
            "avant-match", "post match", "post-match",
        )

        val REPLAY_KEYWORDS = listOf(
            "replay", "rediffusion", "diffusion en differe", "rerun", "اعادة", "إعادة",
        )

        const val WEIGHT_VERSUS_PATTERN = 0.42
        const val WEIGHT_SPORT_CATEGORY = 0.24
        const val WEIGHT_SPORT_KEYWORD = 0.14
        const val WEIGHT_SPORT_CONTEXT = 0.16
        const val WEIGHT_COMPETITION = 0.16
        const val WEIGHT_DESCRIPTION = 0.08
        const val WEIGHT_LIVE_BONUS = 0.04
        const val WEIGHT_NEGATIVE_STRONG = 0.9
        const val MIN_CONFIDENCE = 0.55
    }
}

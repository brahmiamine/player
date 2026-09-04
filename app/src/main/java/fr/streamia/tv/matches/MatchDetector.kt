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
 * Détecteur déterministe basé sur des règles multilingues : aucune dépendance à un modèle ML.
 * Un futur détecteur sémantique pourra couvrir les cas ambigus (pas de séparateur explicite, ex.
 * "Le Clasico"), mais la rangée Matchs doit rester pleinement fonctionnelle sans lui — même
 * philosophie de repli que [fr.streamia.tv.recommendation.MetadataSimilarityEngine].
 *
 * On exige volontairement un sport confirmé en plus du motif d'affrontement : ça écarte les faux
 * positifs comme un jeu-questionnaire "Équipe A vs Équipe B" au prix de rater les rencontres sans
 * aucun indice de catégorie/mot-clé sportif (cas laissé au détecteur sémantique).
 */
class StructuredMatchDetector {

    fun detect(title: String, description: String?, category: String?): MatchDetection {
        val normalizedTitle = normalize(title)
        val normalizedDescription = normalize(description.orEmpty())
        val normalizedCategory = normalize(category.orEmpty())

        if (containsAny(normalizedTitle, REPLAY_KEYWORDS) || containsAny(normalizedCategory, REPLAY_KEYWORDS)) {
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

        val haystack = "$normalizedTitle $normalizedDescription $normalizedCategory"

        val competitionEntry = COMPETITION_SPORT.entries.firstOrNull { (keyword, _) -> haystack.contains(keyword) }
        val competition = competitionEntry?.key
        if (competition != null) {
            signals += "KNOWN_COMPETITION"
            score += WEIGHT_COMPETITION
        }

        // Le sport peut venir d'un mot-clé direct ("football", "كرة القدم"...) ou, à défaut, d'une
        // compétition reconnue ("Premier League" implique le football même sans le mot lui-même).
        val keywordSport = detectSportKeyword(haystack)
        val sport = keywordSport ?: competitionEntry?.value
        when {
            keywordSport != null && containsAny(normalizedCategory, SPORT_KEYWORDS.getValue(keywordSport)) -> {
                signals += "SPORT_CATEGORY"
                score += WEIGHT_SPORT_CATEGORY
            }
            keywordSport != null -> {
                signals += "SPORT_KEYWORD"
                score += WEIGHT_SPORT_KEYWORD
            }
            sport != null -> {
                signals += "SPORT_FROM_COMPETITION"
                score += WEIGHT_SPORT_KEYWORD
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

        if (containsAny(normalizedTitle, HIGHLIGHT_KEYWORDS) || containsAny(normalizedDescription, HIGHLIGHT_KEYWORDS)) {
            negativeSignals += "HIGHLIGHTS_OR_SUMMARY"
            score -= WEIGHT_NEGATIVE_STRONG
        }
        if (containsAny(normalizedTitle, MAGAZINE_KEYWORDS) ||
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
     * Cherche un séparateur d'affrontement ("vs", "contre", "ضد", "-"...) dont les deux côtés
     * ressemblent à des noms d'équipes/joueurs. Les séparateurs les plus spécifiques sont essayés
     * avant le simple tiret, beaucoup plus ambigu (présent dans des titres non sportifs).
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
        return words.all { PARTICIPANT_WORD.matches(it) }
    }

    private companion object {
        val COMBINING_MARKS = Regex("\\p{M}+")
        val WHITESPACE = Regex("\\s+")
        val PARTICIPANT_WORD = Regex("^[\\p{L}][\\p{L}'.-]*$")
        const val MAX_PARTICIPANT_WORDS = 5

        // Du plus spécifique au plus ambigu : le tiret est essayé en dernier.
        val VERSUS_SEPARATORS = listOf(
            " vs. ", " vs ", " v. ", " v ", " contre ", " gegen ", " against ", " ضد ", " @ ",
            " - ", " – ", " — ",
        )

        val SPORT_KEYWORDS: Map<MatchSport, List<String>> = mapOf(
            MatchSport.Football to listOf(
                "football", "soccer", "futbol", "calcio", "fussball", "fußball", "كرة القدم",
            ),
            MatchSport.Basketball to listOf("basketball", "basket", "baloncesto", "كرة السلة"),
            MatchSport.Tennis to listOf("tennis", "tenis", "تنس"),
            MatchSport.Rugby to listOf("rugby"),
            MatchSport.Handball to listOf("handball", "balonmano", "كرة اليد"),
            MatchSport.Hockey to listOf("hockey"),
            MatchSport.Volleyball to listOf("volleyball", "volley", "voleibol"),
            MatchSport.Combat to listOf("ufc", "mma", "boxe", "boxing"),
        )

        /** Compétitions reconnues, avec le sport qu'elles impliquent (une compétition sans mot-clé
         * sport explicite à proximité, ex. "Premier League", doit quand même être reconnue). */
        val COMPETITION_SPORT: Map<String, MatchSport> = linkedMapOf(
            "ligue 1" to MatchSport.Football,
            "ligue 2" to MatchSport.Football,
            "premier league" to MatchSport.Football,
            "la liga" to MatchSport.Football,
            "laliga" to MatchSport.Football,
            "serie a" to MatchSport.Football,
            "bundesliga" to MatchSport.Football,
            "champions league" to MatchSport.Football,
            "ligue des champions" to MatchSport.Football,
            "europa league" to MatchSport.Football,
            "coupe de france" to MatchSport.Football,
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
        )

        val LIVE_KEYWORDS = listOf("live", "direct", "en vivo", "مباشر", "vivo")

        val HIGHLIGHT_KEYWORDS = listOf(
            "highlights", "highlight", "resume", "best of", "recap", "digest",
            "ملخص", "resumen", "sintesi", "zusammenfassung", "syntese",
        )

        val MAGAZINE_KEYWORDS = listOf(
            "magazine", "magazin", "news", "journal", "actualite", "actualites", "talk", "debrief",
            "chronique", "emission", "podcast", "aktuell", "nachrichten",
        )

        val REPLAY_KEYWORDS = listOf(
            "replay", "rediffusion", "diffusion en differe", "rerun", "اعادة", "إعادة",
        )

        const val WEIGHT_VERSUS_PATTERN = 0.42
        const val WEIGHT_SPORT_CATEGORY = 0.24
        const val WEIGHT_SPORT_KEYWORD = 0.14
        const val WEIGHT_COMPETITION = 0.16
        const val WEIGHT_DESCRIPTION = 0.08
        const val WEIGHT_LIVE_BONUS = 0.04
        const val WEIGHT_NEGATIVE_STRONG = 0.9
        const val MIN_CONFIDENCE = 0.55
    }
}

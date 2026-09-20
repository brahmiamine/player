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

    /**
     * Prédicat bon marché pour écrémer la grille EPG avant d'appeler [detect]. Sans séparateur
     * d'affrontement dans le titre, [detect] ne peut pas conclure à un match (`extractParticipants`
     * en a besoin) : on évite alors de normaliser titre/description/catégorie/chaîne pour la grande
     * majorité des programmes (journaux, films, magazines…), ce qui domine le coût du balayage
     * complet de l'EPG au moment de construire les rangées « en direct »/« suivants ».
     */
    fun mightBeMatch(title: String): Boolean =
        VERSUS_SEPARATORS.any { title.contains(it, ignoreCase = true) }

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

        // Un nom de club est une preuve sportive à part entière, indépendante du nom de la chaîne :
        // le format dominant du fournisseur est « Fulham / Manchester United », souvent sur une
        // chaîne dont le nom ne dit rien de sport (CANAL+ Family) et sans catégorie EPG exploitable.
        val clubMarkerContext = versus != null &&
            (containsClubMarker(versus.first) || containsClubMarker(versus.second))

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
            ?: MatchSport.Other.takeIf { versus != null && (genericSportsContext || clubMarkerContext) }

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
                signals += if (clubMarkerContext) "CLUB_NAME_MARKER" else "GENERIC_SPORT_CHANNEL"
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

    /**
     * Vrai quand un côté ressemble à un nom de club. Deux familles :
     * — les formes longues (« United », « Rovers », « Olympique ») suffisent seules ;
     * — les sigles et les suffixes ambigus (« FC », « City », « Villa ») n'exigent qu'un côté de
     *   plus d'un mot, sans quoi « AC / DC » deviendrait une affiche.
     */
    private fun containsClubMarker(side: String): Boolean {
        val words = normalize(side)
            .split(' ')
            .map { it.trim { char -> char in GENERIC_WORD_TRIM_CHARS } }
            .filter(String::isNotBlank)
        if (words.isEmpty()) return false
        if (words.any { it in CLUB_MARKER_WORDS }) return true
        return words.size >= 2 && words.any { it in AMBIGUOUS_CLUB_MARKER_WORDS }
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
        // Un côté qui n'est que du vocabulaire de programme (« Film », « Saison 3 ») n'est pas une
        // équipe. Sans ce filtre, « Documentaire - Histoire » sur une chaîne dont le nom contient
        // « sport » satisfaisait le motif d'affrontement (0.42) ET le contexte de chaîne générique
        // (0.16), soit 0.58 >= MIN_CONFIDENCE : un faux match en tête de la rangée « en direct ».
        if (isGenericContentSide(words)) return false
        return words.all { PARTICIPANT_WORD.matches(it) || it == "&" }
    }

    /**
     * Vrai quand le côté n'est fait que de vocabulaire de programme : au moins un mot descriptif, et
     * rien d'autre que des nombres (« Saison 3 »). Un seul mot porteur de sens suffit à en faire un
     * participant plausible — c'est ce qui préserve « Serie A », « Top 14 » ou « PSG U19 ».
     *
     * Sortie anticipée au premier mot significatif : le coût par programme EPG reste négligeable.
     */
    private fun isGenericContentSide(words: List<String>): Boolean {
        var hasGenericWord = false
        words.forEach { raw ->
            // Le titre arrive non normalisé : on réutilise [normalize] pour replier la casse et les
            // accents, sinon « Cinéma » n'était pas reconnu comme le mot stocké « cinema ».
            val word = normalize(raw).trim { it in GENERIC_WORD_TRIM_CHARS }
            when {
                word in GENERIC_CONTENT_WORDS -> hasGenericWord = true
                word.isNotEmpty() && word.all(Char::isDigit) -> Unit
                else -> return false
            }
        }
        return hasGenericWord
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

        /**
         * Vocabulaire de programme : un côté de titre composé uniquement de ces mots ne désigne pas
         * une équipe. Volontairement restreint aux termes sans ambiguïté — « ligue », « top »,
         * « world » et « cup » en sont exclus car ils ouvrent de vrais noms de compétition.
         */
        val GENERIC_CONTENT_WORDS = setOf(
            "film", "films", "cinema", "documentaire", "documentaires", "reportage", "emission",
            "magazine", "journal", "meteo", "teleachat", "horoscope",
            "serie", "series", "saison", "episode", "episodes", "telefilm", "animation",
            "musique", "musical", "concert", "concerts", "festival", "spectacle", "theatre",
            "opera", "ballet", "danse", "divertissement",
            "multiplex", "championnat", "championnats", "coupe", "tournoi", "tournois", "trophee",
        )

        /** Ponctuation retirée avant comparaison : [PARTICIPANT_WORD] tolère « . », « ' », « + », « _ ». */
        const val GENERIC_WORD_TRIM_CHARS = ".,;:!?\"'«»()[]-–—"

        /**
         * Formes longues de noms de club : suffisantes à elles seules. Choix volontairement
         * conservateur — « Real », « Union », « Town », « Club » ou « Forest » sont exclus car trop
         * fréquents dans des titres non sportifs.
         */
        val CLUB_MARKER_WORDS = setOf(
            "united", "rovers", "wanderers", "albion", "athletic", "sporting", "olympique",
            "olympiacos", "olympiakos", "deportivo", "atletico", "borussia", "dynamo", "lokomotiv",
            "spartak", "hotspur",
        )

        /**
         * Sigles et suffixes de club ambigus : ne comptent que dans un côté de plusieurs mots, pour
         * ne pas transformer « AC / DC » en affiche.
         */
        val AMBIGUOUS_CLUB_MARKER_WORDS = setOf(
            "fc", "cf", "sc", "ac", "afc", "cd", "sv", "fk", "sk", "bk", "vfl", "vfb", "tsg", "bsc",
            "city", "villa", "county",
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

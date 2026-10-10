package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import java.time.Year

enum class SearchSort { Rating, Recent }

/**
 * Ce que l'IA a compris d'une demande en langage naturel (« un film d'action des années 90 »). Rien de tout cela n'est
 * affiché tel quel : le plan sert à interroger l'index local du catalogue (catégories, plein texte, années), et seuls
 * des contenus réellement présents dans la playlist sont montrés.
 */
data class AiSearchPlan(
    val type: MediaType?,
    /** Mots de genre tels qu'ils peuvent apparaître dans un nom de catégorie (français, anglais, langue d'origine). */
    val genres: List<String>,
    /** Pays ou langue, mêmes variantes (« turc », « turque », « turkish »). */
    val regions: List<String>,
    /** Mots du titre cités par l'utilisateur (saga, personnage…). */
    val keywords: List<String>,
    /** Titres connus qui correspondent à la demande : vérifiés dans le catalogue avant d'être montrés. */
    val titles: List<String>,
    val yearFrom: Int?,
    val yearTo: Int?,
    val sort: SearchSort,
    /** Courte reformulation dans la langue de l'assistant, affichée au-dessus des résultats. */
    val summary: String,
) {
    val hasYears: Boolean get() = yearFrom != null || yearTo != null
    val isEmpty: Boolean get() = genres.isEmpty() && regions.isEmpty() && keywords.isEmpty() && titles.isEmpty()
}

/** Consignes stables (donc réutilisables par le cache de prompt) : la demande de l'utilisateur est dans le message. */
internal fun searchPlanSystemPrompt(languageName: String): String =
    "Tu transformes une demande de recherche en langage naturel (français, anglais ou arabe) en filtres pour un catalogue de films, séries et chaînes. " +
        "Réponds uniquement par un objet JSON, sans texte autour :\n" +
        "{\"type\":\"movie|series|live|any\",\"genres\":[],\"regions\":[],\"keywords\":[],\"titles\":[],\"years\":[début,fin],\"sort\":\"rating|recent\",\"summary\":\"\"}\n" +
        "- genres : mots de genre tels qu'ils figureraient dans un nom de catégorie IPTV, avec leurs variantes (ex. [\"action\"], [\"comédie\",\"comedie\",\"comedy\"], [\"horreur\",\"horror\"]).\n" +
        "- regions : pays ou langue demandés, avec variantes (ex. [\"turc\",\"turque\",\"turkish\",\"tr\"], [\"arabe\",\"arabic\",\"ar\"], [\"enfants\",\"kids\"] si c'est un public).\n" +
        "- keywords : mots précis du titre que l'utilisateur a cités (saga, héros). Sinon vide.\n" +
        "- titles : jusqu'à 25 titres réels et connus qui répondent bien à la demande, en titre original latin ; ajoute le titre français s'il diffère. Aucun titre inventé : en cas de doute, n'en mets pas.\n" +
        "- years : [début,fin] si une période est demandée (« années 90 » = [1990,1999], « récent » = [2022,2030]), sinon null.\n" +
        "- sort : rating (les mieux notés, par défaut) ou recent (nouveautés, récent, dernier).\n" +
        "- summary : la demande reformulée en 8 mots maximum, en $languageName.\n" +
        "Les listes vides restent []. Ne mets dans type que movie, series, live ou any."

/** Plan lu dans la réponse du modèle ; [forcedType] (filtre Films/Séries/Direct de l'écran) l'emporte sur le type deviné. Null si inexploitable. */
internal fun parseSearchPlan(answer: String, forcedType: MediaType? = null): AiSearchPlan? {
    val json = extractJsonObject(answer) ?: return null
    val type = forcedType ?: when (json.optString("type").lowercase()) {
        "movie", "movies", "film", "films" -> MediaType.Movie
        "series", "serie", "séries", "show" -> MediaType.Series
        "live", "tv", "direct" -> MediaType.Live
        else -> null
    }
    var from: Int? = null
    var to: Int? = null
    json.optJSONArray("years")?.let { years ->
        fun year(index: Int) = years.optInt(index, 0).takeIf { it in MIN_YEAR..MAX_YEAR }
        from = year(0)
        to = year(1) ?: from
        if (from != null && to != null && from!! > to!!) {
            val swap = from
            from = to
            to = swap
        }
    }
    val plan = AiSearchPlan(
        type = type,
        genres = json.stringList("genres", 12),
        regions = json.stringList("regions", 12),
        keywords = json.stringList("keywords", 6),
        titles = json.stringList("titles", 25),
        yearFrom = from,
        yearTo = to,
        sort = if (json.optString("sort").equals("recent", ignoreCase = true)) SearchSort.Recent else SearchSort.Rating,
        summary = shorten(json.optString("summary"), 80),
    )
    return plan.takeUnless { it.isEmpty && !it.hasYears }
}

private const val MIN_YEAR = 1900
private const val MAX_YEAR = 2100

/** Catégories dont le nom correspond aux genres et régions du plan (voir [matchingCategories]). */
internal fun matchingCategories(plan: AiSearchPlan, categories: List<MediaCategory>, limit: Int = MAX_PLAN_CATEGORIES): List<MediaCategory> {
    if (plan.genres.isEmpty() && plan.regions.isEmpty()) return emptyList()
    val genreTerms = plan.genres.map(::normalizeForMatch).filter(String::isNotEmpty)
    val regionTerms = plan.regions.map(::normalizeForMatch).filter(String::isNotEmpty)
    val types = plan.type?.let(::setOf) ?: setOf(MediaType.Movie, MediaType.Series)
    class Scored(val category: MediaCategory, val genre: Boolean, val region: Boolean) {
        val score get() = (if (genre) 1 else 0) + (if (region) 1 else 0)
    }
    val scored = categories.asSequence()
        .filter { it.type in types }
        .map { category ->
            val name = normalizeForMatch(category.name)
            val tokens = name.split(' ')
            Scored(category, genreTerms.any { termMatches(tokens, name, it) }, regionTerms.any { termMatches(tokens, name, it) })
        }
        .filter { it.score > 0 }
        .toList()
    if (scored.isEmpty()) return emptyList()
    val wanted = (if (genreTerms.isNotEmpty()) 1 else 0) + (if (regionTerms.isNotEmpty()) 1 else 0)
    val best = scored.maxOf(Scored::score)
    val top = scored.filter { it.score == best }
    // Genre et région demandés mais jamais ensemble dans un nom de catégorie : la région (« séries turques »)
    // restreint plus que le genre ; le genre précis est alors laissé aux titres proposés et aux années.
    val chosen = if (best < wanted && regionTerms.isNotEmpty()) top.filter(Scored::region).ifEmpty { top } else top
    return chosen.map(Scored::category).take(limit)
}

private fun termMatches(tokens: List<String>, name: String, term: String): Boolean = when {
    ' ' in term -> " $name ".contains(" $term ")
    term.length <= 2 -> term in tokens
    else -> tokens.any { token -> token.startsWith(term) || (token.length >= 4 && term.length >= 5 && term.startsWith(token)) }
}

private const val MAX_PLAN_CATEGORIES = 60

/** Année de sortie lue dans un titre (« Heat (1995) », « Heat 1995 »), la dernière du texte ; null si absente ou invraisemblable (« Blade Runner 2049 »). */
internal fun releaseYear(title: String, maxYear: Int = Year.now().value + 1): Int? =
    YEAR.findAll(title).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in MIN_YEAR..maxYear }

private val YEAR = Regex("(?<!\\d)((?:19|20)\\d{2})(?!\\d)")

/**
 * Garde les contenus dont l'année tombe dans la période. Ceux dont le titre ne donne aucune année ne sont gardés
 * (après les autres) que s'il y a moins de [enoughKnown] résultats datés : on ne vide pas une recherche parce que le
 * fournisseur ne met pas les années dans les titres.
 */
internal fun filterByYears(entries: List<MediaEntry>, from: Int?, to: Int?, enoughKnown: Int = 8, maxYear: Int = Year.now().value + 1): List<MediaEntry> {
    if (from == null && to == null) return entries
    val low = from ?: Int.MIN_VALUE
    val high = to ?: Int.MAX_VALUE
    val inside = ArrayList<MediaEntry>()
    val undated = ArrayList<MediaEntry>()
    for (entry in entries) {
        val year = releaseYear(entry.displayName, maxYear)
        when {
            year == null -> undated += entry
            year in low..high -> inside += entry
        }
    }
    return if (inside.size >= enoughKnown) inside else inside + undated
}

/** Entrées de [results] dont le titre est exactement [wanted] (mots entiers, accents et casse ignorés) : un titre proposé par l'IA n'est montré que s'il existe. */
internal fun pickTitleMatches(wanted: String, results: List<MediaEntry>, max: Int = 2): List<MediaEntry> {
    val needle = normalizeForMatch(wanted)
    if (needle.length < 2) return emptyList()
    return results.filter { " ${normalizeForMatch(it.displayName)} ".contains(" $needle ") }.take(max)
}

/** Titres proposés d'abord (réellement présents), puis le reste, sans doublon. */
internal fun mergeSearchResults(suggested: List<MediaEntry>, pool: List<MediaEntry>, limit: Int): List<MediaEntry> =
    (suggested + pool).distinctBy(MediaEntry::key).take(limit)

/** Résultat d'une recherche en langage naturel : le plan compris et les contenus trouvés. */
data class AiSearchResult(val query: String, val plan: AiSearchPlan, val entries: List<MediaEntry>)

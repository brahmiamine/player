package fr.streamia.tv.recommendation

import fr.streamia.tv.domain.MediaDetails
import fr.streamia.tv.domain.MediaEntry
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class ContentFeatures(
    val entry: MediaEntry,
    val plot: String? = entry.plot,
    val genre: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val country: String? = null,
    val releaseDate: String? = null,
    val rating: Double? = entry.rating,
    val tmdbId: String? = null,
    /** Mots-clés TMDB (« serial killer », « nuclear catastrophe »…), en anglais quelle que soit la langue du fournisseur. */
    val keywords: List<String> = emptyList(),
    val enriched: Boolean = false,
) {
    companion object {
        fun from(entry: MediaEntry, details: MediaDetails? = null): ContentFeatures = ContentFeatures(
            entry = entry,
            plot = details?.plot ?: entry.plot,
            genre = details?.genre,
            cast = details?.cast,
            director = details?.director,
            country = details?.country,
            releaseDate = details?.releaseDate,
            rating = details?.rating ?: entry.rating,
            tmdbId = details?.tmdbId,
            enriched = details != null,
        )
    }
}

data class SimilarityScore(
    val score: Double,
    val reason: String? = null,
    val semanticUsed: Boolean = false,
    val substantive: Boolean = false,
)

interface ContentSimilarityEngine {
    fun compare(source: ContentFeatures, candidate: ContentFeatures): SimilarityScore
}

/**
 * Point d'extension pour un modèle d'embedding on-device. Le moteur de recommandation ne dépend
 * jamais d'une implémentation ML particulière : si aucun modèle ne passe le budget Android TV,
 * le ranking reste fonctionnel avec les métadonnées locales.
 */
interface SemanticSimilarityProvider {
    val id: String
    fun similarity(sourceText: String, candidateText: String): Double?
}

/**
 * Similarité multi-signaux, déterministe et tolérante aux catalogues IPTV bruités.
 *
 * Le titre n'est jamais comparé tel quel : les tags fournisseur (MULTI/FHD/UHD/VOSTFR, année
 * ajoutée au nom, etc.) sont retirés pour éviter que deux contenus sans rapport deviennent
 * artificiellement proches. Une simple catégorie IPTV commune, une année proche ou une note
 * voisine ne constituent jamais à elles seules une preuve de similarité.
 */
class MetadataSimilarityEngine(
    private val semanticProvider: SemanticSimilarityProvider? = null,
) : ContentSimilarityEngine {
    override fun compare(source: ContentFeatures, candidate: ContentFeatures): SimilarityScore {
        val sourceTitleTokens = titleTokens(source.entry.displayName)
        val candidateTitleTokens = titleTokens(candidate.entry.displayName)
        val sourcePlotTokens = plotTokens(source.plot)
        val candidatePlotTokens = plotTokens(candidate.plot)
        val sourceGenres = genreTokens(source.genre)
        val candidateGenres = genreTokens(candidate.genre)
        val sourceCast = personTokens(source.cast)
        val candidateCast = personTokens(candidate.cast)
        val sourceDirector = personTokens(source.director)
        val candidateDirector = personTokens(candidate.director)
        val sourceCountries = tokens(source.country)
        val candidateCountries = tokens(candidate.country)
        val sourceKeywords = keywordSet(source.keywords)
        val candidateKeywords = keywordSet(candidate.keywords)
        val sharedKeywords = sourceKeywords.filter { it in candidateKeywords }

        val titleSimilarity = dice(sourceTitleTokens, candidateTitleTokens)
        val plotSimilarity = dice(sourcePlotTokens, candidatePlotTokens)
        val genreSimilarity = dice(sourceGenres, candidateGenres)
        val castSimilarity = dice(sourceCast, candidateCast)
        val directorSimilarity = dice(sourceDirector, candidateDirector)
        val countrySimilarity = dice(sourceCountries, candidateCountries)
        val keywordSimilarity = dice(sourceKeywords, candidateKeywords)
        val yearSimilarity = yearSimilarity(source, candidate)
        val ratingSimilarity = ratingSimilarity(source.rating, candidate.rating)
        val categoryMatch = source.entry.categoryId == candidate.entry.categoryId

        var weighted = 0.0
        var available = 0.0
        fun add(weight: Double, value: Double, present: Boolean) {
            if (!present) return
            weighted += weight * value.coerceIn(0.0, 1.0)
            available += weight
        }

        // Pour une fiche détail, l'histoire et le genre doivent dominer nettement.
        // Catégorie IPTV, année et note restent seulement des critères de départage.
        add(0.03, titleSimilarity, sourceTitleTokens.isNotEmpty() && candidateTitleTokens.isNotEmpty())
        add(0.49, plotSimilarity, sourcePlotTokens.isNotEmpty() && candidatePlotTokens.isNotEmpty())
        add(0.34, genreSimilarity, sourceGenres.isNotEmpty() && candidateGenres.isNotEmpty())
        // Mots-clés TMDB : thèmes précis et comparables quelle que soit la langue du résumé.
        add(0.30, keywordSimilarity, sourceKeywords.isNotEmpty() && candidateKeywords.isNotEmpty())
        add(0.04, castSimilarity, sourceCast.isNotEmpty() && candidateCast.isNotEmpty())
        add(0.05, directorSimilarity, sourceDirector.isNotEmpty() && candidateDirector.isNotEmpty())
        add(0.01, countrySimilarity, sourceCountries.isNotEmpty() && candidateCountries.isNotEmpty())
        add(0.02, yearSimilarity, source.releaseYear() != null && candidate.releaseYear() != null)
        add(0.01, ratingSimilarity, source.rating != null && candidate.rating != null)
        add(0.01, if (categoryMatch) 1.0 else 0.0, true)

        var metadataScore = if (available <= 0.0) 0.0 else (weighted / available).coerceIn(0.0, 1.0)

        val semantic = semanticProvider
            ?.similarity(embeddingText(source), embeddingText(candidate))
            ?.takeIf(Double::isFinite)
            ?.coerceIn(0.0, 1.0)

        // Un titre proche ne suffit pas (« The Bride » / « Bride Wars ») : il faut aussi un genre commun.
        val genresCompatible = sourceGenres.isEmpty() || candidateGenres.isEmpty() || genreSimilarity > 0.0
        val strongTitleRelation = titleSimilarity >= 0.45 && genresCompatible
        val plotAndGenreRelation = plotSimilarity >= 0.08 && genreSimilarity >= 0.34
        val strongPlotRelation = plotSimilarity >= 0.18
        val strongGenreUniverse = genreSimilarity >= 0.66 &&
            (titleSimilarity >= 0.34 || castSimilarity >= 0.20 || directorSimilarity >= 0.72)
        val peopleRelation = directorSimilarity >= 0.72 || castSimilarity >= 0.30
        val semanticRelation = semantic != null && semantic >= 0.62
        val keywordRelation = sharedKeywords.size >= 2 ||
            (sharedKeywords.isNotEmpty() && genreSimilarity >= 0.34 && plotSimilarity >= 0.05)
        val substantive = strongTitleRelation || plotAndGenreRelation || strongPlotRelation ||
            strongGenreUniverse || peopleRelation || semanticRelation || keywordRelation

        // Deux genres explicitement incompatibles doivent fortement pénaliser un rapprochement
        // faible basé sur quelques mots génériques du synopsis.
        if (
            sourceGenres.isNotEmpty() &&
            candidateGenres.isNotEmpty() &&
            genreSimilarity == 0.0 &&
            !strongPlotRelation &&
            !keywordRelation &&
            !peopleRelation &&
            !semanticRelation
        ) {
            metadataScore *= DISJOINT_GENRE_PENALTY
        }

        val blended = if (semantic != null) {
            SEMANTIC_WEIGHT * semantic + METADATA_WEIGHT_WITH_SEMANTIC * metadataScore
        } else {
            metadataScore
        }

        // Règle essentielle pour la fiche détail : "même catégorie", "même année", "même note"
        // ne sont que du contexte. Sans relation descriptive, le score est nul.
        val finalScore = if (substantive) blended.coerceIn(0.0, 1.0) else 0.0

        val reason = when {
            sharedKeywords.size >= 2 -> "Thèmes : " + sharedKeywords.take(2).joinToString(", ")
            genreSimilarity >= 0.66 && plotSimilarity >= 0.10 -> "Genre, intrigue et univers très proches"
            plotAndGenreRelation -> "Genre et histoire similaires"
            semantic != null && semantic >= 0.72 -> "Intrigue et ambiance similaires"
            directorSimilarity >= 0.72 -> "Même réalisateur"
            plotSimilarity >= 0.18 -> "Intrigue et thèmes similaires"
            castSimilarity >= 0.30 -> "Distribution similaire"
            strongTitleRelation -> "Même saga ou univers"
            else -> null
        }


        return SimilarityScore(
            score = finalScore,
            reason = reason,
            semanticUsed = semantic != null,
            substantive = substantive,
        )
    }

    private fun embeddingText(features: ContentFeatures): String = buildList {
        add(cleanTitle(features.entry.displayName))
        features.plot?.takeIf(String::isNotBlank)?.let(::add)
        features.genre?.takeIf(String::isNotBlank)?.let { add("Genre: $it") }
        features.cast?.takeIf(String::isNotBlank)?.let { add("Distribution: $it") }
        features.director?.takeIf(String::isNotBlank)?.let { add("Réalisateur: $it") }
        features.country?.takeIf(String::isNotBlank)?.let { add("Pays: $it") }
        features.releaseDate?.takeIf(String::isNotBlank)?.let { add("Date: $it") }
    }.joinToString(". ").take(MAX_EMBEDDING_TEXT_CHARS)

    // Titre sans préfixe fournisseur : sinon « RO - 12 Years a Slave » et « RO - 12 Round Gun »
    // partageaient « ro » et passaient pour une même saga.
    internal fun titleTokens(value: String): Set<String> = rawTokens(splitProviderPrefix(value).second, minLength = 2)
        .filterNotTo(linkedSetOf()) { token ->
            token in TITLE_NOISE_TOKENS || token in STOP_WORDS || YEAR_TOKEN.matches(token)
        }

    private fun cleanTitle(value: String): String = titleTokens(value).joinToString(" ")

    /** Mots-clés TMDB normalisés, sans les étiquettes trop génériques pour rapprocher deux histoires. */
    internal fun keywordSet(keywords: List<String>): Set<String> = keywords.asSequence()
        .map { it.trim().lowercase(Locale.ROOT) }
        .filterTo(linkedSetOf()) { it.isNotEmpty() && it !in GENERIC_KEYWORDS }

    internal fun genreTokens(value: String?): Set<String> = rawTokens(value, minLength = 2)
        .mapTo(linkedSetOf()) { token -> GENRE_ALIASES[token] ?: token }

    internal fun personTokens(value: String?): Set<String> = rawTokens(value, minLength = 2)
        .filterNotTo(linkedSetOf()) { it in PERSON_NOISE_TOKENS }

    /**
     * Canonicalisation très légère des mots d'intrigue. Elle rapproche les variantes françaises
     * et anglaises les plus fréquentes sans modèle lourd ni stemming agressif sur Android TV.
     */
    internal fun plotTokens(value: String?): Set<String> = rawTokens(value, minLength = 3)
        .filterNot { it in STOP_WORDS || it in PLOT_STOP_WORDS || it in TITLE_NOISE_TOKENS }
        .map(::stem)
        .filterNot { it in PLOT_STOP_WORDS }
        .mapTo(linkedSetOf()) { token -> PLOT_TOKEN_ALIASES[token] ?: token }

    /**
     * Racine minimale FR/EN (pluriels, féminins et formes verbales les plus courantes) : suffit à
     * rapprocher « vampires »/« vampire », « tueuse »/« tueur », « killing »/« killer » sans
     * stemmer complet, trop coûteux et trop agressif sur des synopsis courts.
     */
    private fun stem(token: String): String {
        if (token.length < 5 || !token[0].isLetter() || token[0].code > 0x24F) return token
        for ((suffix, replacement) in STEM_SUFFIXES) {
            if (token.length - suffix.length >= 4 && token.endsWith(suffix)) {
                return token.dropLast(suffix.length) + replacement
            }
        }
        return token
    }

    private fun tokens(value: String?): Set<String> = rawTokens(value, minLength = 3)
        .filterNotTo(linkedSetOf()) { it in STOP_WORDS || it in TITLE_NOISE_TOKENS }

    private fun rawTokens(value: String?, minLength: Int): Sequence<String> {
        if (value.isNullOrBlank()) return emptySequence()
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
        return TOKEN_REGEX.findAll(normalized)
            .map { it.value }
            .filter { it.length >= minLength }
    }

    private fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        return (2.0 * intersection / (a.size + b.size)).coerceIn(0.0, 1.0)
    }

    private fun yearSimilarity(source: ContentFeatures, candidate: ContentFeatures): Double {
        val sourceYear = source.releaseYear() ?: return 0.0
        val candidateYear = candidate.releaseYear() ?: return 0.0
        val distance = abs(sourceYear - candidateYear)
        return when {
            distance == 0 -> 1.0
            distance <= 2 -> 0.85
            distance <= 5 -> 0.60
            distance <= 10 -> 0.30
            else -> 0.0
        }
    }

    private fun ratingSimilarity(source: Double?, candidate: Double?): Double {
        if (source == null || candidate == null) return 0.0
        val maximum = maxOf(source, candidate, 10.0)
        return (1.0 - abs(source - candidate) / maximum).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val MAX_EMBEDDING_TEXT_CHARS = 4_000
        const val SEMANTIC_WEIGHT = 0.78
        const val METADATA_WEIGHT_WITH_SEMANTIC = 0.22
        const val DISJOINT_GENRE_PENALTY = 0.20

        val GENERIC_KEYWORDS = setOf(
            "woman director", "sequel", "remake", "duringcreditsstinger", "aftercreditsstinger", "miniseries",
            "based on novel or book", "based on comic", "based on manga", "anime", "new york city",
            "los angeles, california", "london, england", "paris, france", "short film", "live action",
        )
        val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
        val COMBINING_MARKS = Regex("\\p{M}+")
        val YEAR_TOKEN = Regex("(?:19|20)\\d{2}")

        val TITLE_NOISE_TOKENS = setOf(
            "multi", "multilang", "multilingual", "4k", "hd", "sd", "fhd", "uhd", "hdr", "hdr10", "dolby",
            "vostfr", "vost", "truefrench", "french", "vf", "vfq", "vo", "fr", "en", "de",
            "it", "es", "ar", "pt", "ru", "tr", "webrip", "webdl", "bluray",
            "bdrip", "dvdrip", "remux", "x264", "x265", "hevc", "av1", "aac", "dts",
        )

        val PERSON_NOISE_TOKENS = setOf("and", "avec", "with", "et")

        val PLOT_TOKEN_ALIASES = mapOf(
            "survivre" to "survival",
            "survit" to "survival",
            "survivent" to "survival",
            "survivant" to "survival",
            "survivants" to "survival",
            "survive" to "survival",
            "survives" to "survival",
            "surviving" to "survival",
            "surviv" to "survival",
            "ennemy" to "enemy",
            "ennemi" to "enemy",
            "ennemis" to "enemy",
            "ennemie" to "enemy",
            "ennemies" to "enemy",
            "enemies" to "enemy",
            "soldat" to "military",
            "soldats" to "military",
            "militaire" to "military",
            "militaires" to "military",
            "soldier" to "military",
            "soldiers" to "military",
            "army" to "military",
            "armee" to "military",
        )

        val GENRE_ALIASES = mapOf(
            "science" to "science_fiction",
            "fiction" to "science_fiction",
            "scifi" to "science_fiction",
            "sci" to "science_fiction",
            "sf" to "science_fiction",
            "guerre" to "war",
            "militaire" to "war",
            "military" to "war",
            "حرب" to "war",
            "action" to "action",
            "اكشن" to "action",
            "thriller" to "thriller",
            "suspense" to "thriller",
            "drame" to "drama",
            "drama" to "drama",
            "دراما" to "drama",
            "comedie" to "comedy",
            "comedy" to "comedy",
            "كوميديا" to "comedy",
            "horreur" to "horror",
            "horror" to "horror",
            "رعب" to "horror",
            "crime" to "crime",
            "criminel" to "crime",
            "policier" to "crime",
            "جريمة" to "crime",
            "romance" to "romance",
            "romantique" to "romance",
            "رومانسي" to "romance",
            "aventure" to "adventure",
            "adventure" to "adventure",
            "fantastique" to "fantasy",
            "fantasy" to "fantasy",
            "خيال" to "fantasy",
            "animation" to "animation",
            "anime" to "animation",
            "documentaire" to "documentary",
            "documentary" to "documentary",
            "وثائقي" to "documentary",
            "famille" to "family",
            "family" to "family",
            "عائلي" to "family",
            "mystere" to "mystery",
            "mystery" to "mystery",
            "western" to "western",
            "histoire" to "history",
            "historique" to "history",
            "history" to "history",
            "تاريخي" to "history",
            "musique" to "music",
            "music" to "music",
            "musical" to "music",
            "biographie" to "biography",
            "biography" to "biography",
            "biopic" to "biography",
            "sport" to "sport",
            "sports" to "sport",
        )

        // Ordre important : suffixes les plus longs d'abord.
        val STEM_SUFFIXES = listOf(
            "euses" to "eur", "euse" to "eur", "eurs" to "eur", "ieres" to "ier", "iere" to "ier",
            "ings" to "", "ing" to "", "ers" to "er", "ies" to "y", "aux" to "al",
            "ees" to "e", "es" to "e", "s" to "", "x" to "",
        )

        // Mots trop fréquents dans les synopsis pour prouver une ressemblance d'histoire.
        val PLOT_STOP_WORDS = setOf(
            "plus", "tout", "tous", "toute", "toutes", "mais", "comme", "lorsque", "quand", "alors",
            "elle", "elles", "ils", "lui", "sont", "etre", "avoir", "fait", "faire", "doit", "peut",
            "entre", "vers", "sans", "sous", "chez", "encore", "deja", "tres", "bien", "meme",
            "autre", "autres", "cette", "celui", "celle", "dont", "mais", "donc", "ainsi", "depuis",
            "jour", "jours", "annee", "annees", "temps", "vie", "monde", "homme", "femme", "jeune",
            "nouveau", "nouvelle", "premier", "premiere", "grand", "grande", "petit", "petite",
            "decide", "decouvre", "tente", "essaie", "devient", "retrouve", "va", "vont", "leur",
            "their", "they", "them", "his", "her", "him", "she", "who", "when", "where", "which",
            "while", "what", "will", "must", "can", "has", "have", "been", "are", "was", "were",
            "but", "not", "all", "one", "two", "new", "life", "world", "man", "woman", "young",
            "years", "year", "day", "days", "time", "only", "also", "own", "find", "finds", "gets",
            "becomes", "tries", "takes", "discovers", "decides", "about", "over", "more", "most",
            "film", "movie", "serie", "series", "saison", "season", "episode", "histoire", "story",
        )

        val STOP_WORDS = setOf(
            "the", "and", "for", "with", "that", "this", "from", "into", "after", "before",
            "dans", "avec", "pour", "une", "des", "les", "qui", "sur", "son", "ses", "aux",
            "est", "un", "du", "de", "la", "le", "et", "en", "au", "ce", "ces", "leur", "leurs",
            "من", "في", "على", "الى", "إلى", "عن", "مع", "هذا", "هذه", "التي", "الذي",
        )
    }
}

internal fun ContentFeatures.hasDescriptiveSimilarityMetadata(): Boolean =
    !plot.isNullOrBlank() ||
        !genre.isNullOrBlank() ||
        !cast.isNullOrBlank() ||
        !director.isNullOrBlank() ||
        !country.isNullOrBlank() ||
        !releaseDate.isNullOrBlank()

internal fun likelySameContent(source: ContentFeatures, candidate: ContentFeatures): Boolean {
    if (source.entry.key == candidate.entry.key) return true
    val sourceTmdb = source.tmdbId?.trim()?.takeIf(String::isNotBlank)
    val candidateTmdb = candidate.tmdbId?.trim()?.takeIf(String::isNotBlank)
    if (sourceTmdb != null && candidateTmdb != null && sourceTmdb == candidateTmdb) return true

    val title = canonicalIdentityTitle(source.entry.displayName)
    val candidateTitle = canonicalIdentityTitle(candidate.entry.displayName)
    if (title.isBlank() || title != candidateTitle) return false

    // Même titre sous un autre préfixe = autre version du même contenu (rangée « Autres versions »),
    // jamais une recommandation. Seules deux années connues et différentes (remake) les séparent.
    val sourceYear = source.releaseYear()
    val candidateYear = candidate.releaseYear()
    return sourceYear == null || candidateYear == null || sourceYear == candidateYear
}

/**
 * Année de sortie : la date fournisseur d'abord, sinon la dernière année du titre
 * (« Dune (MULTI) FHD 2021 »). Jamais la date d'ajout au catalogue.
 */
internal fun ContentFeatures.releaseYear(): Int? =
    RELEASE_YEAR_REGEX.find(releaseDate.orEmpty())?.value?.toIntOrNull()
        ?: RELEASE_YEAR_REGEX.findAll(entry.displayName).lastOrNull()?.value?.toIntOrNull()

/** Titre canonique + année + type : repère un même contenu publié sous plusieurs identifiants. */
internal fun ContentFeatures.identityKey(): String? {
    val title = canonicalIdentityTitle(entry.displayName).takeIf(String::isNotBlank) ?: return null
    val year = releaseYear() ?: return null
    return "${entry.type}|$title|$year"
}

private val RELEASE_YEAR_REGEX = Regex("\\b(?:19|20)\\d{2}\\b")

// Préfixe fournisseur en majuscules : « 4K-TOP - », « EN-TOP -», « AR-SUBS - », « FR: », « |EN| »…
// Casse sensible : un vrai titre (« Mission - Impossible ») n'est pas pris pour un préfixe.
private val PROVIDER_PREFIX = Regex("""^\s*(\|[^|]{1,15}\|\s*|[A-Z0-9]{2,5}(-[A-Z0-9]{2,5})*\s*[-:|]\s*)""")
// Numérotation de liste après le préfixe : « 49. 12 Years… », « 183.12.Years… ».
private val LIST_NUMBER = Regex("""^(\d{1,4}\.\s+|\d{3,4}\.)""")
// Code pays ajouté en fin de nom : « The Blame (2026) (GB) ».
private val COUNTRY_SUFFIX = Regex("""(\s*\([A-Z]{2,3}\))+\s*$""")

/** Sépare le préfixe fournisseur (langue, qualité…) du vrai titre : `"AR-SUBS" to "The Blame (2026)"`. */
internal fun splitProviderPrefix(name: String): Pair<String?, String> {
    val match = PROVIDER_PREFIX.find(name)
    val prefix = match?.value?.trim()?.trim('|', '-', ':', ' ')?.ifBlank { null }
    val rest = if (match == null) name else name.substring(match.range.last + 1).replaceFirst(LIST_NUMBER, "")
    return prefix to rest.replace(COUNTRY_SUFFIX, "")
}

private fun canonicalIdentityTitle(value: String): String {
    val normalized = Normalizer.normalize(splitProviderPrefix(value).second, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
    val noise = setOf(
        "multi", "fhd", "uhd", "hdr", "vostfr", "vost", "truefrench", "french",
        "webrip", "webdl", "bluray", "bdrip", "remux", "x264", "x265", "hevc",
    )
    return Regex("[\\p{L}\\p{N}]+").findAll(normalized)
        .map { it.value }
        .filter { it.length >= 2 && it !in noise && !Regex("(?:19|20)\\d{2}").matches(it) }
        .joinToString(" ")
}

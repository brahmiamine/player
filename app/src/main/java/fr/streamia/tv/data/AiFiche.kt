package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.SeriesDetails
import fr.streamia.tv.domain.SeriesEpisode
import org.json.JSONObject

/** Avis rapide d'une fiche : pour qui, quelle ambiance, ce qui peut choquer. Trois lignes, dans la langue de l'assistant. */
data class AiReview(val audience: String, val mood: String, val caution: String) {
    fun encode(): String = JSONObject().put("audience", audience).put("mood", mood).put("caution", caution).toString()
}

/** Avis lu dans un objet JSON (réponse du modèle ou copie en cache) ; null s'il manque les trois champs. */
internal fun parseReview(json: String): AiReview? {
    val obj = extractJsonObject(json) ?: return null
    val audience = shorten(obj.optString("audience"), 140)
    val mood = shorten(obj.optString("mood"), 140)
    val caution = shorten(obj.optString("caution"), 140)
    if (audience.isEmpty() && mood.isEmpty() && caution.isEmpty()) return null
    return AiReview(audience, mood, caution)
}

/** Ce que l'on sait d'un film ou d'une série pour l'avis rapide : le modèle n'en sort pas. */
data class FicheInfo(
    val genre: String? = null,
    val year: String? = null,
    val director: String? = null,
    val cast: String? = null,
    val country: String? = null,
    val rating: Double? = null,
    /** Résumé d'origine (pas la traduction). */
    val plot: String? = null,
) {
    /** Assez de matière pour un avis sans rien inventer : un genre ou un résumé. */
    val hasMaterial: Boolean get() = !genre.isNullOrBlank() || (plot?.trim()?.length ?: 0) >= MIN_REVIEW_PLOT_CHARS

    fun asPrompt(title: String): String = buildString {
        append("Titre : ").append(title)
        genre?.takeIf(String::isNotBlank)?.let { append("\nGenre : ").append(it) }
        year?.takeIf(String::isNotBlank)?.let { append("\nAnnée : ").append(it) }
        country?.takeIf(String::isNotBlank)?.let { append("\nPays : ").append(it) }
        director?.takeIf(String::isNotBlank)?.let { append("\nRéalisateur : ").append(it) }
        cast?.takeIf(String::isNotBlank)?.let { append("\nDistribution : ").append(shorten(it, 120)) }
        rating?.takeIf { it > 0 }?.let { append("\nNote : ").append(it) }
        plot?.takeIf(String::isNotBlank)?.let { append("\nRésumé : ").append(shorten(it, 700)) }
    }

    private companion object {
        const val MIN_REVIEW_PLOT_CHARS = 40
    }
}

/** Épisode déjà vu, avec ce qu'on en sait : ([label] « S1E3 »), titre et résumé éventuel. */
data class RecapEpisode(val label: String, val title: String, val plot: String?)

/** Données d'un « Précédemment dans… » : la série et les épisodes vus, du plus ancien au plus récent. */
data class RecapInput(val seriesTitle: String, val seriesPlot: String?, val watched: List<RecapEpisode>) {
    /** Sans aucun résumé ni de la série ni des épisodes, le modèle ne pourrait qu'inventer. */
    val hasMaterial: Boolean get() = watched.any { !it.plot.isNullOrBlank() } || !seriesPlot.isNullOrBlank()

    fun asPrompt(): String = buildString {
        append("Série : ").append(seriesTitle)
        seriesPlot?.takeIf(String::isNotBlank)?.let { append("\nPrésentation : ").append(shorten(it, 400)) }
        append("\nÉpisodes déjà vus, dans l'ordre :")
        watched.takeLast(MAX_RECAP_EPISODES).forEach { episode ->
            append("\n").append(episode.label).append(" ").append(episode.title)
            episode.plot?.takeIf(String::isNotBlank)?.let { append(" : ").append(shorten(it, 260)) }
        }
    }

    /** Identité du résumé en cache : un nouvel épisode vu en fait un autre. */
    fun cacheKey(): String = "${seriesTitle.hashCode()}|${watched.size}|${watched.lastOrNull()?.label}"

    private companion object {
        const val MAX_RECAP_EPISODES = 12
    }
}

internal fun recapSystemPrompt(languageName: String): String =
    "Tu rappelles à un spectateur ce qu'il a déjà vu d'une série avant qu'il la reprenne. " +
        "Utilise UNIQUEMENT les informations fournies sur les épisodes déjà vus : n'évoque jamais un événement qui n'y figure pas (aucun spoiler sur la suite) et n'invente rien. " +
        "Écris 3 à 5 phrases fluides en $languageName. Si les résumés sont trop pauvres pour savoir ce qui s'est passé, rappelle surtout le point de départ de la série et dis-le simplement. " +
        "Réponds uniquement par le texte du rappel, sans titre ni liste."

internal fun reviewSystemPrompt(languageName: String): String =
    "Tu écris un avis rapide sur un film ou une série à partir des seules informations fournies, sans rien inventer. " +
        "Réponds uniquement par un objet JSON : {\"audience\":\"…\",\"mood\":\"…\",\"caution\":\"…\"} — chaque champ en $languageName, 14 mots maximum. " +
        "audience : pour qui (âge, goûts). mood : ambiance et rythme. caution : ce qui peut choquer (violence, langage, scènes sensibles) seulement si les informations le suggèrent, sinon dis simplement qu'aucun point sensible n'est signalé."

/** Problèmes de lecture : consignes qui limitent les conseils à ce que l'application permet réellement. */
internal fun adviceSystemPrompt(languageName: String): String =
    "Tu aides un spectateur dont la lecture vidéo est mauvaise, à partir d'un relevé de mesures de l'application Streamia TV. " +
        "Donne 2 à 4 conseils courts, concrets et ordonnés du plus utile au moins utile, en $languageName, en texte simple (un conseil par ligne, sans numérotation). " +
        "Ne propose QUE ces leviers : Réglages de lecture › stabilité du flux (faible latence, automatique, stable) ; format du flux Live (automatique, MPEG-TS, HLS) ; " +
        "panneau Versions (touche droite) pour une autre version HD/FHD/UHD de la chaîne ; secours automatique (Paramètres › Lecture & direct) ; " +
        "bascule de résolution et fréquence de l'écran ; mode tunnel ; connexion réseau (Ethernet plutôt que Wi-Fi, redémarrer la box) ; " +
        "et, si le fournisseur est en cause (flux annoncé UHD mais réellement plus bas, coupures côté serveur), le dire. " +
        "Appuie-toi sur les chiffres du relevé, n'invente aucune mesure."

internal fun remoteSystemPrompt(languageName: String): String =
    "Tu es l'assistant d'une télécommande de TV : l'utilisateur t'écrit depuis son téléphone. " +
        "Réponds uniquement par un objet JSON : {\"action\":\"watch_channel|resume|search|match|say\",\"query\":\"…\",\"reply\":\"…\"}\n" +
        "- watch_channel : mettre une chaîne en direct ; query = le nom de la chaîne comme dit (« beIN Sports 1 », « TF1 »).\n" +
        "- resume : reprendre le dernier film ou la dernière série en cours ; query vide, ou le titre s'il est cité.\n" +
        "- search : chercher un film, une série ou une chaîne ; query = la demande telle quelle (une phrase naturelle est acceptée).\n" +
        "- match : trouver le match d'une équipe ou d'une compétition aujourd'hui ; query = l'équipe ou la compétition.\n" +
        "- say : salutation ou question sans action sur la TV ; tu ne connais pas la programmation, n'invente rien et propose plutôt une action.\n" +
        "reply : une phrase courte en $languageName qui dit ce que la TV va faire, ou ta réponse pour say."

internal fun matchSystemPrompt(lines: List<AiCandidate>): String =
    "Trouve dans la liste le match qui correspond à la demande de l'utilisateur (équipes, surnoms et abréviations acceptés : PSG = Paris Saint-Germain, OM = Marseille). " +
        "Réponds uniquement par {\"id\":\"M3\"} avec l'identifiant de la liste, ou {\"id\":null} s'il n'y en a aucun.\n" +
        "Liste (identifiant|match) :\n" + lines.joinToString("\n") { "${it.id}|${it.label}" }

enum class RemoteAction { WatchChannel, Resume, Search, Match, Say }

data class RemoteIntent(val action: RemoteAction, val query: String, val reply: String)

internal fun parseRemoteIntent(answer: String): RemoteIntent? {
    val json: JSONObject = extractJsonObject(answer) ?: return null
    val action = when (json.optString("action").lowercase().trim()) {
        "watch_channel", "channel", "watch" -> RemoteAction.WatchChannel
        "resume" -> RemoteAction.Resume
        "search" -> RemoteAction.Search
        "match" -> RemoteAction.Match
        "say" -> RemoteAction.Say
        else -> return null
    }
    val query = shorten(json.optString("query"), 120)
    // Une action qui vise quelque chose sans le nommer n'a rien à exécuter.
    if (query.isEmpty() && (action == RemoteAction.WatchChannel || action == RemoteAction.Search || action == RemoteAction.Match)) return null
    return RemoteIntent(action, query, shorten(json.optString("reply"), 200))
}

/** Épisodes déjà vus d'une série (du plus ancien au plus récent) et date du dernier visionnage ; 0 si elle est inconnue. */
internal class WatchedEpisodes(val episodes: List<SeriesEpisode>, val lastWatchedAtMillis: Long)

/**
 * Épisodes de [details] que l'utilisateur a vus : lus aux deux tiers ou plus, ou marqués « vu ». Null s'il n'en a vu aucun.
 * Seuls les épisodes (entrées lisibles de l'historique) comptent, jamais la série elle-même.
 */
internal fun watchedEpisodesOf(details: SeriesDetails, library: UserLibrarySnapshot): WatchedEpisodes? {
    val byId = details.episodes.associateBy(SeriesEpisode::id)
    val watched = HashSet<Int>()
    var last = 0L
    for (item in library.history) {
        val entry = item.entry
        if (entry.type != MediaType.Series || !entry.playable) continue
        if (byId[entry.id] == null) continue
        last = maxOf(last, item.updatedAt)
        if (item.progress >= WATCHED_PROGRESS) watched += entry.id
    }
    details.episodes.forEach { episode -> if ("${MediaType.Series.name}:${episode.id}" in library.watchedEntries) watched += episode.id }
    val episodes = details.episodes.filter { it.id in watched }.sortedWith(compareBy(SeriesEpisode::season, SeriesEpisode::number))
    return if (episodes.isEmpty()) null else WatchedEpisodes(episodes, last)
}

internal fun recapInputOf(details: SeriesDetails, watched: WatchedEpisodes): RecapInput = RecapInput(
    seriesTitle = details.series.displayName,
    seriesPlot = details.details?.plot ?: details.series.plot,
    watched = watched.episodes.map { RecapEpisode("S${it.season}E${it.number}", it.title, it.plot) },
)

/** Au-delà de ce délai sans visionnage, le « Précédemment dans… » se prépare tout seul à l'ouverture de la série. */
internal const val RECAP_AUTO_GAP_MS = 14L * 24 * 3_600_000

private const val WATCHED_PROGRESS = 0.7f

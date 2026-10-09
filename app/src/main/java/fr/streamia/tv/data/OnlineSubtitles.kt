package fr.streamia.tv.data

import fr.streamia.tv.BuildConfig
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipInputStream

/** Ce qu'on cherche : un film, ou un épisode si [season] et [episode] sont renseignés. */
data class SubtitleQuery(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** Codes ISO 639-1 par ordre de préférence ("fr", "ar", "en"). */
    val languages: List<String>,
    val tmdbId: String? = null,
) {
    val isEpisode: Boolean get() = season != null && episode != null
}

data class SubtitleResult(
    val provider: String,
    val id: String,
    val language: String,
    val release: String,
    val downloads: Int = 0,
    /** Épisode/saison annoncés par le fournisseur : sert à écarter un mauvais épisode. */
    val season: Int? = null,
    val episode: Int? = null,
    /** Lien direct (SubDL, Podnapisi, Wyzie) ; null si le fournisseur impose un appel de téléchargement. */
    val url: String? = null,
) {
    val languageLabel: String get() = Locale(language).getDisplayLanguage(Locale.FRENCH).replaceFirstChar(Char::uppercase)
}

/** Un fournisseur de sous-titres. [search] et [download] sont bloquants (appelés hors thread principal). */
interface SubtitleProvider {
    val name: String
    val enabled: Boolean
    fun search(query: SubtitleQuery): List<SubtitleResult>
    fun download(result: SubtitleResult): ByteArray
}

private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")

private fun bytesOf(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
    HttpClients.execute(url, headers, connectTimeoutMs = 10_000, readTimeoutMs = 30_000).use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        response.body?.bytes() ?: throw IOException("Réponse vide")
    }

private fun jsonGet(url: String, headers: Map<String, String> = emptyMap()): String =
    HttpClients.getText(url, headers + ("Accept" to "application/json"), connectTimeoutMs = 10_000, readTimeoutMs = 15_000)

/** SubDL : clé gratuite sur subdl.com, archives ZIP. */
class SubDlProvider(private val key: String = BuildConfig.SUBDL_KEY) : SubtitleProvider {
    override val name = "SubDL"
    override val enabled get() = key.isNotBlank()

    override fun search(query: SubtitleQuery): List<SubtitleResult> {
        val url = buildString {
            append("https://api.subdl.com/api/v1/subtitles?api_key=${key.enc()}&film_name=${query.title.enc()}")
            append("&type=${if (query.isEpisode) "tv" else "movie"}&subs_per_page=30")
            append("&languages=${query.languages.joinToString(",") { it.uppercase() }.enc()}")
            query.year?.let { append("&year=$it") }
            query.season?.let { append("&season_number=$it") }
            query.episode?.let { append("&episode_number=$it") }
            query.tmdbId?.let { append("&tmdb_id=$it") }
        }
        return parseSubDl(JSONObject(jsonGet(url)))
    }

    override fun download(result: SubtitleResult): ByteArray = bytesOf(result.url ?: throw IOException("Lien manquant"))
}

internal fun parseSubDl(json: JSONObject): List<SubtitleResult> {
    val items = json.optJSONArray("subtitles") ?: return emptyList()
    return items.objects().mapNotNull { item ->
        val path = item.optString("url").takeIf(String::isNotBlank) ?: return@mapNotNull null
        SubtitleResult(
            provider = "SubDL",
            id = path,
            language = normalizeLanguage(item.optString("lang").ifBlank { item.optString("language") }),
            release = item.optString("release_name").ifBlank { item.optString("name") },
            season = item.optInt("season", -1).takeIf { it > 0 },
            episode = item.optInt("episode", -1).takeIf { it > 0 },
            url = if (path.startsWith("http")) path else "https://dl.subdl.com$path",
        )
    }.toList()
}

/** OpenSubtitles.com : clé API (et idéalement un compte, pour un quota de téléchargement plus large). */
class OpenSubtitlesProvider(
    private val key: String = BuildConfig.OPENSUBTITLES_KEY,
    private val user: String = BuildConfig.OPENSUBTITLES_USER,
    private val password: String = BuildConfig.OPENSUBTITLES_PASSWORD,
) : SubtitleProvider {
    override val name = "OpenSubtitles"
    override val enabled get() = key.isNotBlank()

    private val headers get() = mapOf("Api-Key" to key, "User-Agent" to "Streamia TV v${BuildConfig.VERSION_NAME}")
    @Volatile private var token: String? = null

    override fun search(query: SubtitleQuery): List<SubtitleResult> {
        val url = buildString {
            append("https://api.opensubtitles.com/api/v1/subtitles?query=${query.title.lowercase().enc()}")
            append("&languages=${query.languages.joinToString(",").enc()}")
            append("&type=${if (query.isEpisode) "episode" else "movie"}&order_by=download_count")
            query.year?.let { append("&year=$it") }
            query.season?.let { append("&season_number=$it") }
            query.episode?.let { append("&episode_number=$it") }
            query.tmdbId?.let { append(if (query.isEpisode) "&parent_tmdb_id=$it" else "&tmdb_id=$it") }
        }
        return parseOpenSubtitles(JSONObject(jsonGet(url, headers)))
    }

    override fun download(result: SubtitleResult): ByteArray {
        val bearer = login()
        val body = JSONObject().put("file_id", result.id.toLong()).put("sub_format", "srt").toString()
        val reply = HttpClients.getText(
            "https://api.opensubtitles.com/api/v1/download",
            headers + mapOf("Accept" to "application/json") + listOfNotNull(bearer?.let { "Authorization" to "Bearer $it" }),
            body = "application/json" to body,
            errorMessage = { code -> if (code == 406) "Quota OpenSubtitles atteint." else "OpenSubtitles : HTTP $code" },
        )
        val link = JSONObject(reply).optString("link").takeIf(String::isNotBlank) ?: throw IOException("Lien OpenSubtitles absent")
        return bytesOf(link)
    }

    private fun login(): String? {
        if (user.isBlank() || password.isBlank()) return null
        token?.let { return it }
        return runCatching {
            val body = JSONObject().put("username", user).put("password", password).toString()
            val reply = HttpClients.getText(
                "https://api.opensubtitles.com/api/v1/login",
                headers + mapOf("Accept" to "application/json"),
                body = "application/json" to body,
            )
            JSONObject(reply).optString("token").takeIf(String::isNotBlank)
        }.getOrNull()?.also { token = it }
    }
}

internal fun parseOpenSubtitles(json: JSONObject): List<SubtitleResult> =
    json.optJSONArray("data").objects().mapNotNull { item ->
        val attributes = item.optJSONObject("attributes") ?: return@mapNotNull null
        val file = attributes.optJSONArray("files").objects().firstOrNull() ?: return@mapNotNull null
        val fileId = file.optLong("file_id", -1).takeIf { it > 0 } ?: return@mapNotNull null
        val feature = attributes.optJSONObject("feature_details")
        SubtitleResult(
            provider = "OpenSubtitles",
            id = fileId.toString(),
            language = normalizeLanguage(attributes.optString("language")),
            release = attributes.optString("release").ifBlank { file.optString("file_name") },
            downloads = attributes.optInt("download_count"),
            season = feature?.optInt("season_number", -1)?.takeIf { it > 0 },
            episode = feature?.optInt("episode_number", -1)?.takeIf { it > 0 },
        )
    }.toList()

/** Podnapisi : aucune clé, archives ZIP. */
class PodnapisiProvider : SubtitleProvider {
    override val name = "Podnapisi"
    override val enabled get() = true

    override fun search(query: SubtitleQuery): List<SubtitleResult> {
        val url = buildString {
            append("https://www.podnapisi.net/subtitles/search/advanced?keywords=${query.title.enc()}")
            query.languages.forEach { append("&language=$it") }
            append("&movie_type=${if (query.isEpisode) "tv-series" else "movie"}")
            query.year?.let { append("&year=$it") }
            query.season?.let { append("&seasons=$it") }
            query.episode?.let { append("&episodes=$it") }
        }
        return parsePodnapisi(JSONObject(jsonGet(url)))
    }

    override fun download(result: SubtitleResult): ByteArray = bytesOf("https://www.podnapisi.net/subtitles/${result.id}/download")
}

internal fun parsePodnapisi(json: JSONObject): List<SubtitleResult> =
    json.optJSONArray("data").objects().mapNotNull { item ->
        val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val releases = item.optJSONArray("custom_releases").strings().ifEmpty { item.optJSONArray("releases").strings() }
        SubtitleResult(
            provider = "Podnapisi",
            id = id,
            language = normalizeLanguage(item.optString("language")),
            release = releases.firstOrNull() ?: item.optString("title"),
            downloads = item.optJSONObject("stats")?.optInt("downloads") ?: 0,
            season = item.optJSONObject("episode_info")?.optInt("season", -1)?.takeIf { it > 0 },
            episode = item.optJSONObject("episode_info")?.optInt("episode", -1)?.takeIf { it > 0 },
        )
    }.toList()

/** Wyzie Subs : agrège plusieurs sources ; clé gratuite (store.wyzie.io/redeem) et identifiant TMDB requis. */
class WyzieProvider(private val key: String = BuildConfig.WYZIE_KEY) : SubtitleProvider {
    override val name = "Wyzie"
    override val enabled get() = key.isNotBlank()

    override fun search(query: SubtitleQuery): List<SubtitleResult> {
        val tmdb = query.tmdbId ?: return emptyList()
        val url = buildString {
            append("https://sub.wyzie.io/search?key=${key.enc()}&id=$tmdb&language=${query.languages.joinToString(",")}&format=srt")
            query.season?.let { append("&season=$it") }
            query.episode?.let { append("&episode=$it") }
        }
        return parseWyzie(JSONArray(jsonGet(url)))
    }

    override fun download(result: SubtitleResult): ByteArray = bytesOf(result.url ?: throw IOException("Lien manquant"))
}

internal fun parseWyzie(array: JSONArray): List<SubtitleResult> =
    array.objects().mapNotNull { item ->
        val url = item.optString("url").takeIf(String::isNotBlank) ?: return@mapNotNull null
        SubtitleResult(
            provider = "Wyzie",
            id = item.optString("id").ifBlank { url },
            language = normalizeLanguage(item.optString("language")),
            release = item.optString("release").ifBlank { item.optString("display") },
            downloads = item.optInt("downloadCount"),
            url = url,
        )
    }.toList()

/** "fra", "fre", "French", "FR", "pt-BR" → "fr" / "pt". */
internal fun normalizeLanguage(raw: String): String {
    val value = raw.trim().lowercase()
    if (value.isEmpty()) return "und"
    if (value.length == 2) return value
    value.substringBefore('-').substringBefore('_').takeIf { it.length == 2 }?.let { return it }
    return LANGUAGE_ALIASES[value] ?: Locale.getISOLanguages().firstOrNull {
        Locale(it).getDisplayLanguage(Locale.ENGLISH).equals(value, ignoreCase = true) ||
            Locale(it).getDisplayLanguage(Locale.FRENCH).equals(value, ignoreCase = true)
    } ?: runCatching { Locale(value).language }.getOrNull()?.takeIf { it.length == 2 } ?: value.take(3)
}

private val LANGUAGE_ALIASES = mapOf(
    "fra" to "fr", "fre" to "fr", "ara" to "ar", "eng" to "en", "spa" to "es", "ger" to "de", "deu" to "de",
    "ita" to "it", "por" to "pt", "tur" to "tr", "rus" to "ru", "nld" to "nl", "dut" to "nl",
)

private fun JSONArray?.objects(): Sequence<JSONObject> =
    if (this == null) emptySequence() else (0 until length()).asSequence().mapNotNull(::optJSONObject)

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter(String::isNotBlank)

/** Résultat d'une recherche : trié du meilleur au moins bon, avec les fournisseurs en échec. */
data class SubtitleSearchOutcome(val results: List<SubtitleResult>, val failedProviders: List<String>, val searched: Int)

/**
 * Interroge tous les fournisseurs actifs en parallèle (12 s chacun) : l'échec ou la lenteur de l'un ne
 * bloque pas les autres. [tmdbIdFor] sert Wyzie et affine SubDL/OpenSubtitles quand un jeton TMDB est configuré.
 */
class OnlineSubtitleService internal constructor(
    private val providers: List<SubtitleProvider>,
    private val tmdb: TmdbClient,
) {
    constructor() : this(listOf(SubDlProvider(), OpenSubtitlesProvider(), PodnapisiProvider(), WyzieProvider()), TmdbClient())

    val activeProviders: List<String> get() = providers.filter { it.enabled }.map { it.name }

    suspend fun search(base: SubtitleQuery): SubtitleSearchOutcome = coroutineScope {
        val query = if (base.tmdbId == null && tmdb.enabled) {
            val id = withContext(Dispatchers.IO) { runCatching { tmdb.findId(base.title, base.year, base.isEpisode) }.getOrNull() }
            base.copy(tmdbId = id)
        } else {
            base
        }
        val active = providers.filter { it.enabled }
        val failed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val all = active.map { provider ->
            async(Dispatchers.IO) {
                val found = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { runCatching { provider.search(query) } }
                if (found == null || found.isFailure) failed += provider.name
                found?.getOrNull().orEmpty()
            }
        }.awaitAll().flatten()
        SubtitleSearchOutcome(rank(all, query), failed, active.size)
    }

    /** Télécharge, décompresse si besoin, convertit en UTF-8 et écrit dans [directory] ; renvoie le fichier. */
    suspend fun fetch(result: SubtitleResult, query: SubtitleQuery, directory: File): File = withContext(Dispatchers.IO) {
        val provider = providers.first { it.name == result.provider }
        val raw = provider.download(result)
        val (name, content) = extractSubtitle(raw, query.season, query.episode)
        val text = decodeSubtitle(content, result.language)
        directory.mkdirs()
        directory.listFiles()?.forEach { if (it.lastModified() < System.currentTimeMillis() - CACHE_TTL_MS) it.delete() }
        val extension = if (name.endsWith(".vtt", ignoreCase = true) || text.trimStart().startsWith("WEBVTT")) "vtt" else "srt"
        File(directory, "${result.provider}-${result.id.hashCode().toUInt()}.$extension").also { it.writeText(text, Charsets.UTF_8) }
    }

    private companion object {
        const val SEARCH_TIMEOUT_MS = 12_000L
        const val CACHE_TTL_MS = 24 * 3_600_000L
    }
}

/**
 * Meilleur d'abord : langue préférée, bon épisode, puis popularité. Un résultat annoncé pour un autre
 * épisode est écarté ; les doublons (même fournisseur et même identifiant) sont fusionnés.
 */
internal fun rank(results: List<SubtitleResult>, query: SubtitleQuery): List<SubtitleResult> {
    val order = query.languages.map(::normalizeLanguage)
    return results.distinctBy { it.provider to it.id }
        .filter { result ->
            !query.isEpisode || ((result.season == null || result.season == query.season) && (result.episode == null || result.episode == query.episode))
        }
        .sortedWith(
            compareBy<SubtitleResult> { order.indexOf(it.language).let { index -> if (index < 0) order.size else index } }
                .thenBy { if (query.isEpisode && result(it, query)) 0 else 1 }
                .thenByDescending { it.downloads },
        )
}

private fun result(it: SubtitleResult, query: SubtitleQuery) = it.season == query.season && it.episode == query.episode

/** Contenu texte d'un téléchargement : fichier brut, ou meilleur .srt/.vtt d'une archive ZIP. */
internal fun extractSubtitle(raw: ByteArray, season: Int?, episode: Int?): Pair<String, ByteArray> {
    if (raw.size < 4 || raw[0] != 'P'.code.toByte() || raw[1] != 'K'.code.toByte()) return "subtitle.srt" to raw
    val tag = if (season != null && episode != null) Regex("""s0*${season}e0*$episode(?!\d)""", RegexOption.IGNORE_CASE) else null
    val entries = mutableListOf<Pair<String, ByteArray>>()
    ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
        generateSequence { zip.nextEntry }.forEach { entry ->
            if (!entry.isDirectory && entry.name.lowercase().let { it.endsWith(".srt") || it.endsWith(".vtt") }) {
                entries += entry.name to zip.readBytes()
            }
        }
    }
    return entries.firstOrNull { tag != null && tag.containsMatchIn(it.first) }
        ?: entries.maxByOrNull { it.second.size }
        ?: throw IOException("Aucun fichier .srt/.vtt dans l'archive.")
}

/** UTF-8 si valide, sinon l'encodage historique de la langue (les vieux .srt arabes sont en windows-1256). */
internal fun decodeSubtitle(bytes: ByteArray, language: String): String {
    val withoutBom = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> bytes.copyOfRange(3, bytes.size)
        else -> bytes
    }
    if (withoutBom.size >= 2 && withoutBom[0] == 0xFF.toByte() && withoutBom[1] == 0xFE.toByte()) return String(withoutBom, 2, withoutBom.size - 2, Charsets.UTF_16LE)
    if (withoutBom.size >= 2 && withoutBom[0] == 0xFE.toByte() && withoutBom[1] == 0xFF.toByte()) return String(withoutBom, 2, withoutBom.size - 2, Charsets.UTF_16BE)
    val strictUtf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        strictUtf8.decode(ByteBuffer.wrap(withoutBom)).toString()
    } catch (_: CharacterCodingException) {
        val legacy = when (language) {
            "ar" -> "windows-1256"; "tr" -> "windows-1254"; "ru", "bg", "uk" -> "windows-1251"
            "el" -> "windows-1253"; "he" -> "windows-1255"; "pl", "cs", "hu", "ro" -> "windows-1250"
            else -> "windows-1252"
        }
        String(withoutBom, Charset.forName(legacy))
    }
}

/**
 * Titre propre pour la recherche à partir d'un nom de catalogue IPTV ("FR | Avatar (2009) 4K MULTI").
 * Renvoie (titre, année). Saison/épisode viennent de [seasonEpisodeOf].
 */
internal fun cleanSubtitleTitle(raw: String): Pair<String, Int?> {
    var text = raw
    val year = Regex("""[(\[]?\b((?:19|20)\d{2})\b[)\]]?""").findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    text = text.replace(Regex("""^\s*[\[(|][^\])|]{1,10}[\])|]\s*"""), "")
        .replace(Regex("""^\s*[A-Za-z]{2,3}\s*[-:|]\s+"""), "")
        .replace(Regex("""\bS\d{1,2}\s*E\d{1,3}\b""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""[(\[]?\b(?:19|20)\d{2}\b[)\]]?"""), " ")
        .replace(Regex("""\b(?:4K|UHD|FHD|HD|SD|HDR|HEVC|x26[45]|[0-9]{3,4}p|MULTI|VOSTFR|VOSTA|VF|VO|TRUEFRENCH|FRENCH|WEB-?DL|BLURAY|BDRIP|HDRIP)\b""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""[|_\[\]()]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', ':', '.')
    return text to year
}

internal fun seasonEpisodeOf(text: String): Pair<Int, Int>? =
    Regex("""\bS(\d{1,2})\s*E(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(text)
        ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

/** Requête pour [name]/[displayName] ; pour un épisode, [seriesTitle] est le nom de la série. */
fun buildSubtitleQuery(
    type: MediaType,
    name: String,
    displayName: String,
    seriesTitle: String?,
    languages: List<String>,
): SubtitleQuery {
    val seasonEpisode = if (type == MediaType.Series) seasonEpisodeOf(displayName) ?: seasonEpisodeOf(name) else null
    val (title, year) = cleanSubtitleTitle(if (seasonEpisode != null && !seriesTitle.isNullOrBlank()) seriesTitle else name)
    return SubtitleQuery(
        title = title.ifBlank { name },
        year = if (seasonEpisode != null) null else year,
        season = seasonEpisode?.first,
        episode = seasonEpisode?.second,
        languages = languages.map(::normalizeLanguage).filter { it.length == 2 }.distinct().ifEmpty { listOf("fr", "en") },
    )
}

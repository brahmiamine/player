package fr.streamia.tv.data

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import fr.streamia.tv.domain.AccountInfo
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaDetails
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.SeriesDetails
import fr.streamia.tv.domain.SeriesEpisode
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.XtreamUrlBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class XtreamClient {
    /**
     * Vérifie les identifiants Xtream sans charger le catalogue : un seul aller-retour vers
     * player_api.php (sans `action=`), la même requête que la première étape de [loadCatalog].
     * Permet de valider un compte avant de lancer l'import complet, potentiellement long sur les
     * catalogues volumineux.
     */
    suspend fun testConnection(credentials: ServerCredentials): AccountInfo = withContext(Dispatchers.IO) {
        testConnectionOnIo(credentials)
    }

    /** Suppose déjà être sur [Dispatchers.IO] : utilisée par [loadCatalog], lui-même déjà dispatché. */
    private fun testConnectionOnIo(credentials: ServerCredentials): AccountInfo =
        parseAccount(fetchObject(XtreamUrlBuilder(credentials).authentication()))

    /**
     * Récupère le catalogue fournisseur et l'écrit directement dans [sink] au fil du parsing,
     * plutôt que de renvoyer un catalogue avec toutes les entrées déjà en mémoire : sur un
     * catalogue de plusieurs centaines de milliers de lignes, matérialiser la liste complète avant
     * de la persister double inutilement le pic mémoire pendant l'actualisation. Renvoie le compte
     * d'entrées par type (issu de ce qui a réellement été écrit) plutôt que le catalogue lui-même :
     * l'appelant relit ensuite la version allégée depuis le cache une fois l'écriture validée.
     */
    suspend fun loadCatalog(credentials: ServerCredentials, sink: CatalogWriteSink): CatalogFetchResult = withContext(Dispatchers.IO) {
        val urls = XtreamUrlBuilder(credentials)
        val account = testConnectionOnIo(credentials)
        if (!account.status.equals("Active", ignoreCase = true)) {
            throw XtreamException("Ce compte n'est pas actif (${account.status}).")
        }

        val categories = buildList {
            addAll(parseCategories(fetchArray(urls.api("get_live_categories")), MediaType.Live))
            addAll(runCatching { parseCategories(fetchArray(urls.api("get_vod_categories")), MediaType.Movie) }.getOrDefault(emptyList()))
            addAll(runCatching { parseCategories(fetchArray(urls.api("get_series_categories")), MediaType.Series) }.getOrDefault(emptyList()))
        }.distinctBy(MediaCategory::key)
        sink.writeCategories(categories)

        // Les catalogues de certains fournisseurs dépassent largement 200 000 entrées. JsonReader
        // lit le tableau objet par objet et [sink] écrit par lots de [WRITE_CHUNK_SIZE] au fil du
        // parsing, en base transactionnelle : le catalogue complet n'existe jamais comme une seule
        // liste en mémoire, et un échec réseau en cours de route laisse l'ancien catalogue intact
        // (la transaction n'est validée qu'après un succès complet, voir XtreamRepository).
        val counts = linkedMapOf(
            MediaType.Live to fetchEntriesStreaming(urls.api("get_live_streams"), MediaType.Live, sink),
            MediaType.Movie to runCatching { fetchEntriesStreaming(urls.api("get_vod_streams"), MediaType.Movie, sink) }.getOrDefault(0),
            MediaType.Series to runCatching { fetchEntriesStreaming(urls.api("get_series"), MediaType.Series, sink) }.getOrDefault(0),
        )

        // Certains serveurs authentifient correctement player_api.php mais renvoient [] pour VOD
        // ou Séries alors que leur get.php contient bien ces médias. Dans ce cas uniquement, on lit
        // le M3U en flux et seulement pour les sections manquantes.
        val missingTypes = setOf(MediaType.Movie, MediaType.Series).filterTo(linkedSetOf()) { type -> counts[type] == 0 }
        if (missingTypes.isNotEmpty()) {
            runCatching { fetchM3uFallback(urls.playlist(), missingTypes) }
                .getOrNull()
                ?.let { fallback ->
                    for (type in missingTypes) {
                        val fallbackEntries = fallback.catalog.entriesFor(type)
                        if (fallbackEntries.isEmpty()) continue
                        sink.writeCategories(fallback.catalog.categoriesFor(type))
                        sink.writeEntries(fallbackEntries)
                        counts[type] = fallbackEntries.size
                    }
                }
        }

        CatalogFetchResult(account, counts)
    }

    suspend fun loadMovieDetails(
        credentials: ServerCredentials,
        movie: MediaEntry,
    ): MediaDetails = withContext(Dispatchers.IO) {
        val root = fetchObject(XtreamUrlBuilder(credentials).vodInfo(movie.id))
        val info = root.optJSONObject("info") ?: JSONObject()
        val movieData = root.optJSONObject("movie_data") ?: JSONObject()
        parseMediaDetails(movie, info, movieData)
    }

    suspend fun loadSeriesDetails(
        credentials: ServerCredentials,
        series: MediaEntry,
    ): SeriesDetails = withContext(Dispatchers.IO) {
        val root = fetchObject(XtreamUrlBuilder(credentials).seriesInfo(series.id))
        val info = root.optJSONObject("info") ?: JSONObject()
        val episodesRoot = root.optJSONObject("episodes") ?: JSONObject()
        val episodes = buildList {
            val seasons = episodesRoot.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            for (seasonKey in seasons) {
                val seasonNumber = seasonKey.toIntOrNull() ?: continue
                val array = episodesRoot.optJSONArray(seasonKey) ?: continue
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").toIntOrNull() ?: continue
                    val episodeInfo = item.optJSONObject("info")
                    add(
                        SeriesEpisode(
                            id = id,
                            season = seasonNumber,
                            number = item.optInt("episode_num", index + 1),
                            title = item.optString("title").ifBlank { "Épisode ${index + 1}" },
                            extension = item.optString("container_extension", "mp4").ifBlank { "mp4" },
                            iconUrl = episodeInfo?.firstNonBlank("movie_image", "cover_big", "cover"),
                            plot = episodeInfo?.firstNonBlank("plot", "description"),
                            duration = episodeInfo?.firstNonBlank("duration", "duration_secs"),
                            rating = episodeInfo?.firstNonBlank("rating", "rating_5based")?.toDoubleOrNull(),
                            releaseDate = episodeInfo?.firstNonBlank("releasedate", "release_date", "air_date"),
                        ),
                    )
                }
            }
        }
        SeriesDetails(
            series = series,
            episodes = episodes,
            details = parseMediaDetails(series, info, root.optJSONObject("series_data") ?: JSONObject()),
        )
    }

    suspend fun loadShortEpg(
        credentials: ServerCredentials,
        streamId: Int,
    ): List<EpgProgram> = withContext(Dispatchers.IO) {
        val root = fetchObject(XtreamUrlBuilder(credentials).shortEpg(streamId, limit = 3))
        val listings = root.optJSONArray("epg_listings") ?: JSONArray()
        buildList {
            for (index in 0 until listings.length()) {
                val item = listings.optJSONObject(index) ?: continue
                add(
                    EpgProgram(
                        title = decodeMaybeBase64(item.optString("title")).ifBlank { "Programme non renseigné" },
                        description = decodeMaybeBase64(item.optString("description")).takeIf(String::isNotBlank),
                        startEpochSeconds = item.optionalLong("start_timestamp"),
                        endEpochSeconds = item.optionalLong("stop_timestamp"),
                        channelId = item.firstNonBlank("channel_id", "epg_id"),
                        category = item.firstNonBlank("category", "genre"),
                    ),
                )
            }
        }
    }

    private fun parseMediaDetails(media: MediaEntry, info: JSONObject, fallback: JSONObject): MediaDetails {
        val poster = info.firstNonBlank("movie_image", "cover_big", "cover", "stream_icon")
            ?: fallback.firstNonBlank("stream_icon", "cover")
            ?: media.iconUrl
        val backdrop = when (val value = info.opt("backdrop_path")) {
            is JSONArray -> value.optString(0).takeIf(String::isNotBlank)
            is String -> value.takeIf(String::isNotBlank)
            else -> info.firstNonBlank("backdrop", "backdrop_url")
        }
        val rating = info.firstNonBlank("rating", "rating_5based")?.toDoubleOrNull() ?: media.rating
        return MediaDetails(
            media = media,
            plot = info.firstNonBlank("plot", "description", "overview") ?: media.plot,
            genre = info.firstNonBlank("genre", "genres"),
            cast = info.firstNonBlank("cast", "actors"),
            director = info.firstNonBlank("director"),
            releaseDate = info.firstNonBlank("releasedate", "release_date", "releaseDate", "air_date"),
            duration = info.firstNonBlank("duration", "duration_secs"),
            country = info.firstNonBlank("country"),
            backdropUrl = backdrop,
            posterUrl = poster,
            youtubeTrailer = info.firstNonBlank("youtube_trailer", "trailer", "youtube"),
            rating = rating,
            tmdbId = info.firstNonBlank("tmdb_id", "tmdb"),
        )
    }

    private fun fetchObject(url: String): JSONObject = JSONObject(fetch(url))
    private fun fetchArray(url: String): JSONArray = JSONArray(fetch(url))

    /**
     * [sink] reçoit les entrées par lots de [WRITE_CHUNK_SIZE] au fil du parsing : en cas d'échec
     * réseau après un premier lot déjà écrit, la nouvelle tentative (URL alternative http/https)
     * reparse la réponse depuis le début et réécrit tout — `INSERT OR REPLACE` côté
     * [CatalogDatabase.ReplaceSession] rend ces réécritures sans effet de bord.
     */
    private fun fetchEntriesStreaming(url: String, type: MediaType, sink: CatalogWriteSink): Int {
        return try {
            fetchEntriesStreamingOnce(url, type, sink)
        } catch (first: IOException) {
            val alternate = XtreamUrlBuilder.alternateTransportUrl(url)
                ?: throw XtreamException("Impossible de joindre le serveur. Vérifiez l'adresse et la connexion.")
            try {
                fetchEntriesStreamingOnce(alternate, type, sink)
            } catch (_: IOException) {
                throw XtreamException("Impossible de joindre le serveur en HTTP ou HTTPS. Vérifiez l'adresse et la connexion.")
            }
        }
    }

    @Throws(IOException::class)
    private fun fetchEntriesStreamingOnce(url: String, type: MediaType, sink: CatalogWriteSink): Int {
        val connection = openConnection(url, "application/json")
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw XtreamException("Le serveur a répondu avec le code $code.")
            JsonReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                parseEntriesStreaming(reader, type, sink)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchM3uFallback(url: String, includeTypes: Set<MediaType>): M3uImport {
        val first = runCatching { fetchM3uFallbackOnce(url, includeTypes) }
        first.getOrNull()?.let { return it }
        val alternate = XtreamUrlBuilder.alternateTransportUrl(url) ?: throw first.exceptionOrNull()!!
        return fetchM3uFallbackOnce(alternate, includeTypes)
    }

    private fun fetchM3uFallbackOnce(url: String, includeTypes: Set<MediaType>): M3uImport {
        val connection = openConnection(url, "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*")
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw XtreamException("Le serveur a répondu avec le code $code pour la playlist de secours.")
            connection.inputStream.use { input ->
                M3uParser().parse(InputStreamReader(input, StandardCharsets.UTF_8), includeTypes)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Accumule au plus [WRITE_CHUNK_SIZE] entrées avant de les écrire dans [sink] et de vider le
     * tampon, plutôt que de construire la liste complète d'une section (jusqu'à des centaines de
     * milliers de lignes chez certains fournisseurs) avant de la persister. Ne trie plus le
     * résultat : les lectures (`loadCategoryPage`/`loadType`/`loadFull`) trient déjà par
     * `number, media_id` via l'index SQLite, donc un tri en mémoire ici serait un travail refait
     * pour rien à chaque actualisation.
     */
    private fun parseEntriesStreaming(reader: JsonReader, type: MediaType, sink: CatalogWriteSink): Int {
        var total = 0
        val chunk = ArrayList<MediaEntry>(WRITE_CHUNK_SIZE)
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return 0
        }
        reader.beginArray()
        var index = 0
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }

            var id: Int? = null
            var name: String? = null
            var categoryId = "0"
            var streamIcon: String? = null
            var cover: String? = null
            var coverBig: String? = null
            var number: Int? = null
            var extension: String? = null
            var tvgId: String? = null
            var plot: String? = null
            var rating: Double? = null
            var added: Long? = null

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "stream_id" -> if (type != MediaType.Series) id = reader.scalarString()?.toIntOrNull() else reader.skipValue()
                    "series_id" -> if (type == MediaType.Series) id = reader.scalarString()?.toIntOrNull() else reader.skipValue()
                    "name" -> name = reader.scalarString()
                    "category_id" -> categoryId = reader.scalarString()?.ifBlank { "0" } ?: "0"
                    "stream_icon" -> streamIcon = reader.scalarString()?.takeIf(String::isNotBlank)
                    "cover" -> cover = reader.scalarString()?.takeIf(String::isNotBlank)
                    "cover_big" -> coverBig = reader.scalarString()?.takeIf(String::isNotBlank)
                    "num" -> number = reader.scalarString()?.toIntOrNull()
                    "container_extension" -> extension = reader.scalarString()?.takeIf(String::isNotBlank)
                    "epg_channel_id" -> tvgId = reader.scalarString()?.takeIf(String::isNotBlank)
                    "plot", "description" -> {
                        val candidate = reader.scalarString()?.takeIf(String::isNotBlank)
                        if (plot.isNullOrBlank()) plot = candidate
                    }
                    "rating_5based", "rating" -> {
                        val candidate = reader.scalarString()?.toDoubleOrNull()
                        if (rating == null) rating = candidate
                    }
                    "added", "last_modified" -> {
                        val candidate = reader.scalarString()?.toLongOrNull()?.takeIf { it > 0 }
                        if (added == null) added = candidate
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            val safeId = id
            val safeName = name?.takeIf(String::isNotBlank)
            if (safeId != null && safeId > 0 && safeName != null) {
                val icon = when (type) {
                    MediaType.Series -> coverBig ?: cover ?: streamIcon
                    else -> streamIcon
                }
                chunk += MediaEntry(
                    id = safeId,
                    name = safeName,
                    displayName = safeName,
                    type = type,
                    categoryId = categoryId,
                    iconUrl = icon,
                    number = number ?: index + 1,
                    extension = extension ?: type.defaultExtension,
                    tvgId = tvgId,
                    plot = plot,
                    rating = rating,
                    playable = type != MediaType.Series,
                    addedAtEpochSeconds = added,
                )
                if (chunk.size >= WRITE_CHUNK_SIZE) {
                    sink.writeEntries(chunk)
                    total += chunk.size
                    chunk.clear()
                }
            }
            index += 1
        }
        reader.endArray()
        if (chunk.isNotEmpty()) {
            sink.writeEntries(chunk)
            total += chunk.size
        }
        return total
    }

    private fun JsonReader.scalarString(): String? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.STRING,
        JsonToken.NUMBER,
        -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> {
            skipValue()
            null
        }
    }

    private fun fetch(url: String): String {
        return try {
            fetchOnce(url)
        } catch (first: IOException) {
            val alternate = XtreamUrlBuilder.alternateTransportUrl(url)
                ?: throw XtreamException("Impossible de joindre le serveur. Vérifiez l'adresse et la connexion.")
            try {
                fetchOnce(alternate)
            } catch (_: IOException) {
                throw XtreamException("Impossible de joindre le serveur en HTTP ou HTTPS. Vérifiez l'adresse et la connexion.")
            }
        }
    }

    @Throws(IOException::class)
    private fun fetchOnce(url: String): String {
        val connection = openConnection(url, "application/json")
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw XtreamException("Le serveur a répondu avec le code $code.")
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 30_000
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Charset", "utf-8")
            setRequestProperty("User-Agent", "Streamia-TV/1.5")
        }

    private fun parseAccount(root: JSONObject): AccountInfo {
        val user = root.optJSONObject("user_info")
            ?: throw XtreamException("Réponse Xtream invalide : informations du compte absentes.")
        if (user.optInt("auth", 0) != 1) throw XtreamException("Identifiant ou mot de passe incorrect.")
        return AccountInfo(
            username = user.optString("username"),
            status = user.optString("status", "Unknown"),
            expiresAtEpochSeconds = user.optionalLong("exp_date"),
            activeConnections = user.optionalInt("active_cons"),
            maximumConnections = user.optionalInt("max_connections"),
        )
    }

    private fun parseCategories(array: JSONArray, type: MediaType): List<MediaCategory> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("category_id")
            val name = item.optString("category_name")
            if (id.isNotBlank() && name.isNotBlank()) add(MediaCategory(id, name, type))
        }
    }.distinctBy(MediaCategory::key)

    private fun decodeMaybeBase64(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8)
                .takeIf { decoded ->
                    decoded.isNotBlank() && decoded.none { character ->
                        character == '\u0000' || character == '\uFFFD' ||
                            (character.code < 32 && character !in setOf('\n', '\r', '\t'))
                    }
                }
                ?: value
        }.getOrDefault(value)
    }

    private fun JSONObject.firstNonBlank(vararg keys: String): String? {
        for (key in keys) {
            val value = optString(key).trim()
            if (value.isNotBlank() && !value.equals("null", ignoreCase = true)) return value
        }
        return null
    }

    private fun JSONObject.optionalLong(key: String): Long? = optString(key).toLongOrNull()?.takeIf { it > 0 }
    private fun JSONObject.optionalInt(key: String): Int? = optString(key).toIntOrNull()?.takeIf { it >= 0 }

    private companion object {
        const val WRITE_CHUNK_SIZE = 1000
    }
}

/** Résultat de [XtreamClient.loadCatalog] : le compte réellement écrit par section, pas les entrées elles-mêmes. */
data class CatalogFetchResult(val account: AccountInfo, val counts: Map<MediaType, Int>)

class XtreamException(message: String) : Exception(message)

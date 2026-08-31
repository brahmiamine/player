package fr.streamia.tv.data

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import fr.streamia.tv.domain.AccountInfo
import fr.streamia.tv.domain.Catalog
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
    suspend fun loadCatalog(credentials: ServerCredentials): Catalog = withContext(Dispatchers.IO) {
        val urls = XtreamUrlBuilder(credentials)
        val account = parseAccount(fetchObject(urls.authentication()))
        if (!account.status.equals("Active", ignoreCase = true)) {
            throw XtreamException("Ce compte n'est pas actif (${account.status}).")
        }

        val categories = buildList {
            addAll(parseCategories(fetchArray(urls.api("get_live_categories")), MediaType.Live))
            addAll(runCatching { parseCategories(fetchArray(urls.api("get_vod_categories")), MediaType.Movie) }.getOrDefault(emptyList()))
            addAll(runCatching { parseCategories(fetchArray(urls.api("get_series_categories")), MediaType.Series) }.getOrDefault(emptyList()))
        }.toMutableList()

        // Les catalogues de certains fournisseurs dépassent largement 200 000 entrées.
        // JsonReader traite le tableau objet par objet et évite de conserver une énorme chaîne JSON
        // + JSONArray + tous les JSONObject simultanément en mémoire sur la TV.
        val entries = buildList {
            addAll(fetchEntriesStreaming(urls.api("get_live_streams"), MediaType.Live))
            addAll(runCatching { fetchEntriesStreaming(urls.api("get_vod_streams"), MediaType.Movie) }.getOrDefault(emptyList()))
            addAll(runCatching { fetchEntriesStreaming(urls.api("get_series"), MediaType.Series) }.getOrDefault(emptyList()))
        }.toMutableList()

        // Certains serveurs authentifient correctement player_api.php mais renvoient [] pour VOD
        // ou Séries alors que leur get.php contient bien ces médias. Dans ce cas uniquement, on lit
        // le M3U en flux et seulement pour les sections manquantes afin de ne pas doubler la mémoire.
        val missingTypes = setOf(MediaType.Movie, MediaType.Series).filterTo(linkedSetOf()) { type ->
            entries.none { it.type == type }
        }
        if (missingTypes.isNotEmpty()) {
            runCatching { fetchM3uFallback(urls.playlist(), missingTypes) }
                .getOrNull()
                ?.let { fallback ->
                    for (type in missingTypes) {
                        categories.removeAll { it.type == type }
                        entries.removeAll { it.type == type }
                        categories += fallback.catalog.categoriesFor(type)
                        entries += fallback.catalog.entriesFor(type)
                    }
                }
        }

        Catalog(
            categories = categories.distinctBy(MediaCategory::key),
            entries = entries.distinctBy(MediaEntry::key),
            account = account,
        )
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

    private fun fetchEntriesStreaming(url: String, type: MediaType): List<MediaEntry> {
        return try {
            fetchEntriesStreamingOnce(url, type)
        } catch (first: IOException) {
            val alternate = XtreamUrlBuilder.alternateTransportUrl(url)
                ?: throw XtreamException("Impossible de joindre le serveur. Vérifiez l'adresse et la connexion.")
            try {
                fetchEntriesStreamingOnce(alternate, type)
            } catch (_: IOException) {
                throw XtreamException("Impossible de joindre le serveur en HTTP ou HTTPS. Vérifiez l'adresse et la connexion.")
            }
        }
    }

    @Throws(IOException::class)
    private fun fetchEntriesStreamingOnce(url: String, type: MediaType): List<MediaEntry> {
        val connection = openConnection(url, "application/json")
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw XtreamException("Le serveur a répondu avec le code $code.")
            JsonReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                parseEntriesStreaming(reader, type)
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

    private fun parseEntriesStreaming(reader: JsonReader, type: MediaType): List<MediaEntry> {
        val entries = ArrayList<MediaEntry>()
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return emptyList()
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
                entries += MediaEntry(
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
            }
            index += 1
        }
        reader.endArray()
        return entries.sortedWith(compareBy<MediaEntry> { it.number }.thenBy { it.name.lowercase() })
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
}

class XtreamException(message: String) : Exception(message)

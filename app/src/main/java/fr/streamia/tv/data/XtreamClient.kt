package fr.streamia.tv.data

import android.util.Base64
import fr.streamia.tv.domain.AccountInfo
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaCategory
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
            addAll(runCatching {
                parseCategories(fetchArray(urls.api("get_vod_categories")), MediaType.Movie)
            }.getOrDefault(emptyList()))
            addAll(runCatching {
                parseCategories(fetchArray(urls.api("get_series_categories")), MediaType.Series)
            }.getOrDefault(emptyList()))
        }

        // Les trois grandes réponses sont traitées successivement afin de limiter le pic mémoire
        // sur les téléviseurs disposant de peu de RAM.
        val entries = buildList {
            addAll(parseEntries(fetchArray(urls.api("get_live_streams")), MediaType.Live))
            addAll(runCatching {
                parseEntries(fetchArray(urls.api("get_vod_streams")), MediaType.Movie)
            }.getOrDefault(emptyList()))
            addAll(runCatching {
                parseEntries(fetchArray(urls.api("get_series")), MediaType.Series)
            }.getOrDefault(emptyList()))
        }
        Catalog(categories = categories, entries = entries, account = account)
    }

    suspend fun loadSeriesDetails(
        credentials: ServerCredentials,
        series: MediaEntry,
    ): SeriesDetails = withContext(Dispatchers.IO) {
        val root = fetchObject(XtreamUrlBuilder(credentials).seriesInfo(series.id))
        val episodesRoot = root.optJSONObject("episodes") ?: JSONObject()
        val episodes = buildList {
            val seasons = episodesRoot.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            for (seasonKey in seasons) {
                val seasonNumber = seasonKey.toIntOrNull() ?: continue
                val array = episodesRoot.optJSONArray(seasonKey) ?: continue
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").toIntOrNull() ?: continue
                    val info = item.optJSONObject("info")
                    add(
                        SeriesEpisode(
                            id = id,
                            season = seasonNumber,
                            number = item.optInt("episode_num", index + 1),
                            title = item.optString("title").ifBlank { "Épisode ${index + 1}" },
                            extension = item.optString("container_extension", "mp4").ifBlank { "mp4" },
                            iconUrl = info?.optString("movie_image")?.takeIf(String::isNotBlank),
                            plot = info?.optString("plot")?.takeIf(String::isNotBlank),
                            duration = info?.optString("duration")?.takeIf(String::isNotBlank),
                        ),
                    )
                }
            }
        }
        SeriesDetails(series, episodes)
    }

    suspend fun loadShortEpg(
        credentials: ServerCredentials,
        streamId: Int,
    ): List<EpgProgram> = withContext(Dispatchers.IO) {
        val root = fetchObject(XtreamUrlBuilder(credentials).shortEpg(streamId))
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
                    ),
                )
            }
        }
    }

    private fun fetchObject(url: String): JSONObject = JSONObject(fetch(url))

    private fun fetchArray(url: String): JSONArray = JSONArray(fetch(url))

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Charset", "utf-8")
            setRequestProperty("User-Agent", "Streamia-TV/1.1")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw XtreamException("Le serveur a répondu avec le code $code.")
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (error: XtreamException) {
            throw error
        } catch (_: IOException) {
            throw XtreamException("Impossible de joindre le serveur. Vérifiez l'adresse et la connexion.")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAccount(root: JSONObject): AccountInfo {
        val user = root.optJSONObject("user_info")
            ?: throw XtreamException("Réponse Xtream invalide : informations du compte absentes.")
        if (user.optInt("auth", 0) != 1) {
            throw XtreamException("Identifiant ou mot de passe incorrect.")
        }
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

    private fun parseEntries(array: JSONArray, type: MediaType): List<MediaEntry> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val idKey = if (type == MediaType.Series) "series_id" else "stream_id"
            val id = item.optString(idKey).toIntOrNull() ?: item.optInt(idKey, -1)
            val name = item.optString("name")
            if (id <= 0 || name.isBlank()) continue
            val icon = when (type) {
                MediaType.Series -> item.optString("cover")
                else -> item.optString("stream_icon")
            }.takeIf(String::isNotBlank)
            add(
                MediaEntry(
                    id = id,
                    name = name,
                    displayName = name,
                    type = type,
                    categoryId = item.optString("category_id", "0"),
                    iconUrl = icon,
                    number = item.optInt("num", index + 1),
                    extension = item.optString("container_extension", type.defaultExtension)
                        .ifBlank { type.defaultExtension },
                    tvgId = item.optString("epg_channel_id").takeIf(String::isNotBlank),
                    plot = item.optString("plot").takeIf(String::isNotBlank),
                    rating = item.optString("rating_5based").toDoubleOrNull()
                        ?: item.optString("rating").toDoubleOrNull(),
                ),
            )
        }
    }.sortedWith(compareBy<MediaEntry> { it.number }.thenBy { it.name.lowercase() })

    private fun decodeMaybeBase64(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8)
                .takeIf { decoded -> decoded.none { it == '\u0000' } }
                ?: value
        }.getOrDefault(value)
    }

    private fun JSONObject.optionalLong(key: String): Long? =
        optString(key).toLongOrNull()?.takeIf { it > 0 }

    private fun JSONObject.optionalInt(key: String): Int? =
        optString(key).toIntOrNull()?.takeIf { it >= 0 }
}

class XtreamException(message: String) : Exception(message)

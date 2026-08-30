package fr.streamia.tv.data

import fr.streamia.tv.domain.AccountInfo
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.LiveCategory
import fr.streamia.tv.domain.LiveChannel
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.XtreamUrlBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class XtreamClient {
    suspend fun loadCatalog(credentials: ServerCredentials): Catalog = withContext(Dispatchers.IO) {
        val urls = XtreamUrlBuilder(credentials)
        val account = parseAccount(fetchObject(urls.authentication()))
        if (!account.status.equals("Active", ignoreCase = true)) {
            throw XtreamException("Ce compte n'est pas actif (${account.status}).")
        }

        coroutineScope {
            val categories = async { parseCategories(fetchArray(urls.api("get_live_categories"))) }
            val channels = async { parseChannels(fetchArray(urls.api("get_live_streams"))) }
            Catalog(
                categories = categories.await(),
                channels = channels.await(),
                account = account,
            )
        }
    }

    private fun fetchObject(url: String): JSONObject = JSONObject(fetch(url))

    private fun fetchArray(url: String): JSONArray = JSONArray(fetch(url))

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Streamia-TV/1.0")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw XtreamException("Le serveur a répondu avec le code $code.")
            connection.inputStream.bufferedReader().use { it.readText() }
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

    private fun parseCategories(array: JSONArray): List<LiveCategory> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("category_id")
            val name = item.optString("category_name")
            if (id.isNotBlank() && name.isNotBlank()) add(LiveCategory(id, name))
        }
    }.distinctBy(LiveCategory::id)

    private fun parseChannels(array: JSONArray): List<LiveChannel> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optInt("stream_id", -1)
            val name = item.optString("name")
            if (id <= 0 || name.isBlank()) continue
            add(
                LiveChannel(
                    id = id,
                    name = name,
                    categoryId = item.optString("category_id", "0"),
                    iconUrl = item.optString("stream_icon").takeIf(String::isNotBlank),
                    number = item.optInt("num", index + 1),
                    epgChannelId = item.optString("epg_channel_id").takeIf(String::isNotBlank),
                ),
            )
        }
    }.sortedWith(compareBy<LiveChannel> { it.number }.thenBy { it.name.lowercase() })

    private fun JSONObject.optionalLong(key: String): Long? =
        optString(key).toLongOrNull()?.takeIf { it > 0 }

    private fun JSONObject.optionalInt(key: String): Int? =
        optString(key).toIntOrNull()?.takeIf { it >= 0 }
}

class XtreamException(message: String) : Exception(message)

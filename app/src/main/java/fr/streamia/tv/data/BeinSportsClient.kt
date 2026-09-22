package fr.streamia.tv.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Accès à l'EPG utilisé par https://www.beinsports.com/en-mena/tv-guide : la page charge sa
 * grille côté client depuis `/api/opta/tv-channel` et `/api/opta/tv-event`, le HTML ne contient
 * aucun programme. Le WAF du site refuse les requêtes sans User-Agent de navigateur.
 */
internal class BeinSportsClient {
    @Throws(IOException::class)
    fun fetchChannelsJson(): String = get("$API_BASE/tv-channel?region=$REGION")

    /** Programmes qui se terminent après [from] et commencent avant [to]. */
    @Throws(IOException::class)
    fun fetchEventsJson(
        channelIds: List<String>,
        from: Instant,
        to: Instant,
    ): String {
        val query = buildString {
            append("endAfter=").append(encode(from.toString()))
            append("&startBefore=").append(encode(to.toString()))
            append("&limit=").append(EVENTS_LIMIT)
            channelIds.forEach { id -> append("&channelIds=").append(encode(id)) }
        }
        return get("$API_BASE/tv-event?$query")
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9,ar;q=0.7")
            setRequestProperty("Referer", TV_GUIDE_URL)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("beIN SPORTS a répondu avec le code $code.")
            return connection.inputStream.use { input ->
                input.reader(StandardCharsets.UTF_8).readText()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val ORIGIN = "https://www.beinsports.com"
        const val API_BASE = "$ORIGIN/api/opta"
        const val TV_GUIDE_URL = "$ORIGIN/en-mena/tv-guide"
        const val REGION = "en-mena"
        const val EVENTS_LIMIT = 3000
        const val USER_AGENT =
            "Mozilla/5.0 (Android TV; Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}

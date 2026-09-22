package fr.streamia.tv.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Télécharge le HTML/JSON de la grille TV publique https://tvguideuk.com/.
 *
 * La page d'accueil ne rend que les toutes premières chaînes ; le reste de chaque genre se charge
 * via l'endpoint AJAX `/guide-fragment.php` que le site utilise lui-même pour son défilement
 * infini (voir [fr.streamia.tv.ukguide.UkGuideParser]).
 */
internal class UkGuideClient {
    @Throws(IOException::class)
    fun fetchGuideHtml(): String = fetch(GUIDE_URL)

    /** Un lot de chaînes d'un genre donné (ex. "Sports", "Kids"). */
    @Throws(IOException::class)
    fun fetchCategoryFragmentJson(category: String, offset: Int, limit: Int): String =
        fetch("$FRAGMENT_URL?offset=$offset&limit=$limit&channel_category=${encode(category)}")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    @Throws(IOException::class)
    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json")
            setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("Referer", GUIDE_URL)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("tvguideuk.com a répondu avec le code $code.")
            return connection.inputStream.use { input -> input.reader(StandardCharsets.UTF_8).readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val GUIDE_URL = "https://tvguideuk.com/"
        const val FRAGMENT_URL = "https://tvguideuk.com/guide-fragment.php"
        const val USER_AGENT =
            "Mozilla/5.0 (Android TV; Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}

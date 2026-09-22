package fr.streamia.tv.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Télécharge le HTML de la grille TV publique https://tvguideuk.com/. */
internal class UkGuideClient {
    @Throws(IOException::class)
    fun fetchGuideHtml(): String = fetch(GUIDE_URL)

    @Throws(IOException::class)
    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
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
        const val USER_AGENT =
            "Mozilla/5.0 (Android TV; Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}

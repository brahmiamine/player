package fr.streamia.tv.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Télécharge le HTML de la page publique "Programme TV ce soir". */
internal class TvProgrammeClient {
    @Throws(IOException::class)
    fun fetchTonightHtml(): String = fetch(TONIGHT_URL)

    @Throws(IOException::class)
    fun fetchNowHtml(): String = fetch(NOW_URL)

    @Throws(IOException::class)
    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("tv-programme.com a répondu avec le code $code.")
            return connection.inputStream.use { input -> input.reader(StandardCharsets.UTF_8).readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TONIGHT_URL = "https://tv-programme.com/"
        const val NOW_URL = "https://tv-programme.com/en-ce-moment"
        const val USER_AGENT =
            "Mozilla/5.0 (Android TV; Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}

package fr.streamia.tv.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Récupère le HTML brut de la page "aujourd'hui" de liveonsat.com. Aucun parsing ici : voir
 * [fr.streamia.tv.liveonsat.LiveOnSatParser]. liveonsat.com ne fournit pas d'API — cette page
 * publique est la seule source disponible pour ces données.
 */
internal class LiveOnSatClient {
    @Throws(IOException::class)
    fun fetchTodayHtml(): String {
        val connection = (URL(TODAY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("liveonsat.com a répondu avec le code $code.")
            return connection.inputStream.use { input -> input.reader(StandardCharsets.UTF_8).readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TODAY_URL = "https://liveonsat.com/2day.php"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}

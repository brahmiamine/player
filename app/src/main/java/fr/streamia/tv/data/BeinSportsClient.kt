package fr.streamia.tv.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal class BeinSportsClient {
    @Throws(IOException::class)
    fun fetchTvGuideHtml(): String {
        val connection = (URL(TV_GUIDE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9,ar;q=0.7")
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

    private companion object {
        const val TV_GUIDE_URL = "https://www.beinsports.com/en-mena/tv-guide"
        const val USER_AGENT =
            "Mozilla/5.0 (Android TV; Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}

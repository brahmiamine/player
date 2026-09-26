package fr.streamia.tv.data

import fr.streamia.tv.net.HttpClients
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Télécharge le HTML des pages publiques de programme TV (tv-programme.com et sites de secours). */
internal class TvProgrammeClient {
    @Throws(IOException::class)
    fun fetch(url: String): String = HttpClients.getText(
        url,
        headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "fr-FR,fr;q=0.9",
        ),
        connectTimeoutMs = 15_000,
        readTimeoutMs = 20_000,
        errorMessage = { code -> java.net.URI(url).host + " a répondu avec le code $code." },
    )

    companion object {
        const val TONIGHT_URL = "https://tv-programme.com/"
        const val NOW_URL = "https://tv-programme.com/en-ce-moment"
        const val USER_AGENT =
            "Mozilla/5.0 (Android TV; Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}

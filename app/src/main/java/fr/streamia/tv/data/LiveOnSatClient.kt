package fr.streamia.tv.data

import fr.streamia.tv.net.HttpClients
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Récupère le HTML brut de la page "aujourd'hui" de liveonsat.com. Aucun parsing ici : voir
 * [fr.streamia.tv.liveonsat.LiveOnSatParser]. liveonsat.com ne fournit pas d'API — cette page
 * publique est la seule source disponible pour ces données.
 */
internal class LiveOnSatClient {
    @Throws(IOException::class)
    fun fetchTodayHtml(): String = HttpClients.getText(
        TODAY_URL,
        headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html"),
        connectTimeoutMs = 15_000,
        readTimeoutMs = 20_000,
        errorMessage = { code -> "liveonsat.com a répondu avec le code $code." },
    )

    private companion object {
        const val TODAY_URL = "https://liveonsat.com/2day.php"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}

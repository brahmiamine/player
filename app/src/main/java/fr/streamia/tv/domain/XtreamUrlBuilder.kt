package fr.streamia.tv.domain

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class XtreamUrlBuilder(private val credentials: ServerCredentials) {
    private val baseUrl: String = credentials.serverUrl.trim().trimEnd('/').also { value ->
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
            "L'adresse du serveur doit commencer par http:// ou https://"
        }
        require(credentials.username.isNotBlank() && credentials.password.isNotBlank()) {
            "L'identifiant et le mot de passe sont obligatoires"
        }
    }

    val usesSecureTransport: Boolean = baseUrl.startsWith("https://", ignoreCase = true)

    fun authentication(): String =
        "$baseUrl/player_api.php?username=${query(credentials.username)}&password=${query(credentials.password)}"

    fun api(action: String): String = "${authentication()}&action=${query(action)}"

    fun liveStream(streamId: Int): String =
        "$baseUrl/live/${path(credentials.username)}/${path(credentials.password)}/$streamId.ts"

    private fun query(value: String): String = encode(value)

    private fun path(value: String): String = encode(value)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

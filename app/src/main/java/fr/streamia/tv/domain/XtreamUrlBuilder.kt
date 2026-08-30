package fr.streamia.tv.domain

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class XtreamUrlBuilder(private val credentials: ServerCredentials) {
    private val baseUrl: String = normalizeServerUrl(credentials.serverUrl).also { value ->
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

    fun stream(entry: MediaEntry): String = stream(entry.type, entry.id, entry.extension)

    fun stream(type: MediaType, streamId: Int, extension: String = type.defaultExtension): String {
        val safeExtension = extension.trim().removePrefix(".").takeIf {
            it.matches(Regex("[A-Za-z0-9]{1,8}"))
        } ?: type.defaultExtension
        return "$baseUrl/${type.pathSegment}/${path(credentials.username)}/${path(credentials.password)}/$streamId.$safeExtension"
    }

    fun liveStream(streamId: Int): String = stream(MediaType.Live, streamId)

    fun seriesInfo(seriesId: Int): String = "${api("get_series_info")}&series_id=$seriesId"

    fun shortEpg(streamId: Int, limit: Int = 2): String =
        "${api("get_short_epg")}&stream_id=$streamId&limit=${limit.coerceIn(1, 10)}"

    private fun query(value: String): String = encode(value)

    private fun path(value: String): String = encode(value)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    companion object {
        fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return trimmed
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            return if (withScheme.endsWith("://")) withScheme else withScheme.trimEnd('/')
        }
    }
}

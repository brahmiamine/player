package fr.streamia.tv.data

import fr.streamia.tv.domain.ServerCredentials

enum class PlaylistKind { Xtream, M3u }

data class PlaylistProfile(
    val id: String,
    val name: String,
    val kind: PlaylistKind,
    val serverUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    val m3uUri: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun credentialsOrNull(): ServerCredentials? {
        val server = serverUrl?.takeIf(String::isNotBlank) ?: return null
        val user = username?.takeIf(String::isNotBlank) ?: return null
        val pass = password ?: return null
        return ServerCredentials(server, user, pass)
    }
}

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
    val m3uUrl: String? = null,
    val xmlTvUrl: String? = null,
    val autoRefreshHours: Int = 6,
    val lastRefreshAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val isRemoteM3u: Boolean get() = kind == PlaylistKind.M3u && !m3uUrl.isNullOrBlank()

    fun credentialsOrNull(): ServerCredentials? {
        val server = serverUrl?.takeIf(String::isNotBlank) ?: return null
        val user = username?.takeIf(String::isNotBlank) ?: return null
        val pass = password ?: return null
        return ServerCredentials(server, user, pass)
    }

    /** Intervalle réservé aux playlists M3U distantes. Le catalogue Xtream n'expire jamais. */
    fun isCatalogRefreshDue(now: Long = System.currentTimeMillis()): Boolean {
        val interval = autoRefreshHours.coerceIn(1, 168) * 60L * 60L * 1000L
        return lastRefreshAt <= 0L || now - lastRefreshAt >= interval
    }

    fun shouldAutoRefresh(now: Long = System.currentTimeMillis()): Boolean =
        isRemoteM3u && isCatalogRefreshDue(now)
}

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

    /**
     * Intervalle d'actualisation du catalogue. Un catalogue Xtream (souvent des centaines de milliers
     * de lignes) n'est relu qu'une fois par jour au plus, même si l'intervalle du profil est plus court.
     */
    fun isCatalogRefreshDue(now: Long = System.currentTimeMillis()): Boolean {
        val hours = autoRefreshHours.coerceIn(1, 168).let { if (kind == PlaylistKind.Xtream) maxOf(it, XTREAM_MIN_REFRESH_HOURS) else it }
        return lastRefreshAt <= 0L || now - lastRefreshAt >= hours * 60L * 60L * 1000L
    }

    fun shouldAutoRefresh(now: Long = System.currentTimeMillis()): Boolean =
        (isRemoteM3u || kind == PlaylistKind.Xtream) && isCatalogRefreshDue(now)

    private companion object {
        const val XTREAM_MIN_REFRESH_HOURS = 24
    }
}

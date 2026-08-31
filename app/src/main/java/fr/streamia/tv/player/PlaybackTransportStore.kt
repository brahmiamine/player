package fr.streamia.tv.player

import android.content.Context
import java.net.URI
import java.security.MessageDigest

/** Persiste uniquement le schéma/conteneur qui a réellement fonctionné pour un fournisseur. */
class PlaybackTransportStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun preferenceFor(streamUrl: String): PlaybackTransportPreference {
        val key = providerKey(streamUrl) ?: return PlaybackTransportPreference()
        return PlaybackTransportPreference(
            scheme = preferences.getString("$key.scheme", null),
            liveExtension = preferences.getString("$key.live_ext", null),
        )
    }

    fun recordSuccess(streamUrl: String, type: fr.streamia.tv.domain.MediaType) {
        val key = providerKey(streamUrl) ?: return
        val scheme = streamUrl.substringBefore("://").lowercase().takeIf { it == "http" || it == "https" }
        val editor = preferences.edit()
        scheme?.let { editor.putString("$key.scheme", it) }
        if (type == fr.streamia.tv.domain.MediaType.Live) {
            PlaybackUrlStrategy.liveExtension(streamUrl)?.let { editor.putString("$key.live_ext", it) }
        }
        editor.apply()
    }

    private fun providerKey(streamUrl: String): String? = runCatching {
        val uri = URI(streamUrl)
        val host = uri.host?.lowercase()?.takeIf(String::isNotBlank) ?: return@runCatching null
        val authority = if (uri.port >= 0) "$host:${uri.port}" else host
        val digest = MessageDigest.getInstance("SHA-256").digest(authority.toByteArray(Charsets.UTF_8))
        "provider_" + digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrNull()

    private companion object {
        const val PREFERENCES_NAME = "streamia-playback-transport-v1"
    }
}

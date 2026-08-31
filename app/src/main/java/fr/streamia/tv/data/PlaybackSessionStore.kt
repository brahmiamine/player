package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.json.JSONObject

/**
 * Mémorise la dernière playlist active et le dernier contenu réellement ouvert dans le lecteur.
 * Les identifiants Xtream et les URL privées ne sont jamais copiés ici.
 */
class PlaybackSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(profileId: String, entry: MediaEntry, returnToSeries: Boolean) {
        val root = JSONObject().apply {
            put("profile_id", profileId)
            put("return_to_series", returnToSeries)
            put("updated_at", System.currentTimeMillis())
            put("entry", entry.toJson())
        }
        preferences.edit()
            .putString(KEY_SESSION, root.toString())
            .putString(KEY_ACTIVE_PROFILE, profileId)
            .putBoolean(KEY_AUTO_OPEN_DISABLED, false)
            .apply()
    }

    fun saveActiveProfile(profileId: String) {
        if (profileId.isBlank()) return
        preferences.edit()
            .putString(KEY_ACTIVE_PROFILE, profileId)
            .putBoolean(KEY_AUTO_OPEN_DISABLED, false)
            .apply()
    }

    fun loadActiveProfileId(): String? =
        preferences.getString(KEY_ACTIVE_PROFILE, null)?.takeIf(String::isNotBlank)

    fun isAutoOpenDisabled(): Boolean = preferences.getBoolean(KEY_AUTO_OPEN_DISABLED, false)

    fun load(): LastPlaybackSession? = runCatching {
        val raw = preferences.getString(KEY_SESSION, null) ?: return@runCatching null
        val root = JSONObject(raw)
        val profileId = root.optString("profile_id").takeIf(String::isNotBlank) ?: return@runCatching null
        val entry = root.optJSONObject("entry")?.toMediaEntry() ?: return@runCatching null
        LastPlaybackSession(
            profileId = profileId,
            entry = entry,
            returnToSeries = root.optBoolean("return_to_series", entry.type == MediaType.Series),
            updatedAt = root.optLong("updated_at", 0L),
        )
    }.getOrNull()

    fun clearPlayback() {
        preferences.edit().remove(KEY_SESSION).apply()
    }

    /** Compatibilité avec les anciens appels : clear() efface seulement le contenu, pas le profil actif. */
    fun clear() = clearPlayback()

    fun disableAutoOpen() {
        preferences.edit()
            .remove(KEY_SESSION)
            .remove(KEY_ACTIVE_PROFILE)
            .putBoolean(KEY_AUTO_OPEN_DISABLED, true)
            .apply()
    }

    private fun MediaEntry.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("display_name", displayName)
        put("type", type.name)
        put("category_id", categoryId)
        putNullable("icon", iconUrl)
        put("number", number)
        put("extension", extension)
        putNullable("tvg_id", tvgId)
        putNullable("plot", plot)
        if (rating == null) put("rating", JSONObject.NULL) else put("rating", rating)
        put("playable", playable)
        if (addedAtEpochSeconds == null) put("added_at", JSONObject.NULL) else put("added_at", addedAtEpochSeconds)
    }

    private fun JSONObject.toMediaEntry(): MediaEntry? {
        val type = runCatching { MediaType.valueOf(optString("type")) }.getOrNull() ?: return null
        val id = optInt("id", -1)
        val name = optString("name")
        if (id <= 0 || name.isBlank()) return null
        return MediaEntry(
            id = id,
            name = name,
            displayName = optString("display_name").ifBlank { name },
            type = type,
            categoryId = optString("category_id", "0"),
            iconUrl = optNullableString("icon"),
            number = optInt("number", 0),
            extension = optString("extension").ifBlank { type.defaultExtension },
            tvgId = optNullableString("tvg_id"),
            plot = optNullableString("plot"),
            rating = if (isNull("rating")) null else optDouble("rating").takeUnless(Double::isNaN),
            playable = optBoolean("playable", type != MediaType.Series),
            addedAtEpochSeconds = if (isNull("added_at")) null else optLong("added_at"),
        )
    }

    private fun JSONObject.putNullable(name: String, value: String?) {
        if (value == null) put(name, JSONObject.NULL) else put(name, value)
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf(String::isNotBlank)
    }

    private companion object {
        const val PREFERENCES_NAME = "streamia-playback-session-v1"
        const val KEY_SESSION = "last_playback"
        const val KEY_ACTIVE_PROFILE = "active_profile"
        const val KEY_AUTO_OPEN_DISABLED = "auto_open_disabled"
    }
}

data class LastPlaybackSession(
    val profileId: String,
    val entry: MediaEntry,
    val returnToSeries: Boolean,
    val updatedAt: Long,
)

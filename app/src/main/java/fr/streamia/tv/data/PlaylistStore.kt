package fr.streamia.tv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PlaylistStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Persiste les profils de playlists dans un payload chiffré avec AndroidKeyStore.
 * Les identifiants Xtream et les URL privées restent donc chiffrés au repos.
 */
class PlaylistStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile
    private var unreadablePayload = false

    fun loadAll(): List<PlaylistProfile> = synchronized(lock) { loadAllLocked() }

    fun find(id: String): PlaylistProfile? = loadAll().firstOrNull { it.id == id }

    fun upsert(profile: PlaylistProfile) {
        mutate { profiles ->
            profiles.filterNot { it.id == profile.id } + profile.copy(updatedAt = System.currentTimeMillis())
        }
    }

    fun rename(id: String, name: String): PlaylistProfile? {
        val cleaned = name.trim()
        if (cleaned.isBlank()) return null
        var updated: PlaylistProfile? = null
        mutate { profiles ->
            val index = profiles.indexOfFirst { it.id == id }
            if (index < 0) return@mutate profiles
            val next = profiles[index].copy(name = cleaned, updatedAt = System.currentTimeMillis())
            updated = next
            profiles.toMutableList().also { it[index] = next }
        }
        return updated
    }

    fun updateRemoteSettings(
        id: String,
        m3uUrl: String?,
        xmlTvUrl: String?,
        autoRefreshHours: Int,
        lastRefreshAt: Long? = null,
    ): PlaylistProfile? {
        var updated: PlaylistProfile? = null
        mutate { profiles ->
            val index = profiles.indexOfFirst { it.id == id }
            if (index < 0) return@mutate profiles
            val current = profiles[index]
            val next = current.copy(
                m3uUrl = m3uUrl?.trim()?.takeIf(String::isNotBlank),
                xmlTvUrl = xmlTvUrl?.trim()?.takeIf(String::isNotBlank),
                autoRefreshHours = autoRefreshHours.coerceIn(1, 168),
                lastRefreshAt = lastRefreshAt ?: current.lastRefreshAt,
                updatedAt = System.currentTimeMillis(),
            )
            updated = next
            profiles.toMutableList().also { it[index] = next }
        }
        return updated
    }

    fun markRefreshed(id: String, at: Long = System.currentTimeMillis()) {
        mutate { profiles ->
            val index = profiles.indexOfFirst { it.id == id }
            if (index < 0) return@mutate profiles
            val next = profiles.toMutableList()
            next[index] = next[index].copy(lastRefreshAt = at, updatedAt = System.currentTimeMillis())
            next
        }
    }

    fun delete(id: String) {
        mutate { profiles -> profiles.filterNot { it.id == id } }
    }

    private fun mutate(transform: (List<PlaylistProfile>) -> List<PlaylistProfile>) {
        synchronized(lock) {
            saveAllLocked(transform(loadAllLocked()))
        }
    }

    private fun loadAllLocked(): List<PlaylistProfile> {
        val encryptedPayload = preferences.getString(KEY_PAYLOAD, null)
        val encodedIv = preferences.getString(KEY_IV, null)
        if (encryptedPayload.isNullOrBlank() || encodedIv.isNullOrBlank()) {
            unreadablePayload = false
            return emptyList()
        }
        return try {
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            val encrypted = Base64.decode(encryptedPayload, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            }
            val array = JSONArray(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val id = json.optString("id").takeIf(String::isNotBlank) ?: continue
                    val name = json.optString("name").takeIf(String::isNotBlank) ?: continue
                    val kind = runCatching { PlaylistKind.valueOf(json.optString("kind")) }.getOrNull() ?: continue
                    add(
                        PlaylistProfile(
                            id = id,
                            name = name,
                            kind = kind,
                            serverUrl = json.optNullableString("server"),
                            username = json.optNullableString("username"),
                            password = json.optNullableString("password"),
                            m3uUri = json.optNullableString("m3u_uri"),
                            m3uUrl = json.optNullableString("m3u_url"),
                            xmlTvUrl = json.optNullableString("xmltv_url"),
                            autoRefreshHours = json.optInt("auto_refresh_hours", 6).coerceIn(1, 168),
                            lastRefreshAt = json.optLong("last_refresh_at", 0L),
                            updatedAt = json.optLong("updated_at", 0L),
                        ),
                    )
                }
            }.sortedByDescending(PlaylistProfile::updatedAt).also {
                unreadablePayload = false
            }
        } catch (error: Exception) {
            unreadablePayload = true
            emptyList()
        }
    }

    private fun saveAllLocked(profiles: List<PlaylistProfile>) {
        if (unreadablePayload) {
            throw PlaylistStoreException(
                "Impossible d'enregistrer les listes : les identifiants existants n'ont pas pu être lus.",
            )
        }
        if (profiles.isEmpty()) {
            if (!preferences.edit().clear().commit()) {
                throw PlaylistStoreException("Impossible d'enregistrer les listes.")
            }
            return
        }
        val array = JSONArray()
        profiles.sortedByDescending(PlaylistProfile::updatedAt).forEach { profile ->
            array.put(
                JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("kind", profile.kind.name)
                    putNullable("server", profile.serverUrl)
                    putNullable("username", profile.username)
                    putNullable("password", profile.password)
                    putNullable("m3u_uri", profile.m3uUri)
                    putNullable("m3u_url", profile.m3uUrl)
                    putNullable("xmltv_url", profile.xmlTvUrl)
                    put("auto_refresh_hours", profile.autoRefreshHours)
                    put("last_refresh_at", profile.lastRefreshAt)
                    put("updated_at", profile.updatedAt)
                },
            )
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val encrypted = cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        val written = preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_PAYLOAD, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
        if (!written) {
            throw PlaylistStoreException("Impossible d'enregistrer les listes.")
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun JSONObject.putNullable(key: String, value: String?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private companion object {
        const val PREFERENCES_NAME = "playlist-profiles"
        const val KEY_ALIAS = "streamia.playlists.v1"
        const val KEY_IV = "iv"
        const val KEY_PAYLOAD = "payload"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

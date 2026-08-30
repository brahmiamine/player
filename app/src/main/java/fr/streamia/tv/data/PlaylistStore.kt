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

/**
 * Persiste les profils de playlists dans un payload chiffré avec AndroidKeyStore.
 * Les identifiants Xtream restent donc chiffrés au repos.
 */
class PlaylistStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadAll(): List<PlaylistProfile> = runCatching {
        val encryptedPayload = preferences.getString(KEY_PAYLOAD, null) ?: return emptyList()
        val encodedIv = preferences.getString(KEY_IV, null) ?: return emptyList()
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
                        updatedAt = json.optLong("updated_at", 0L),
                    ),
                )
            }
        }.sortedByDescending(PlaylistProfile::updatedAt)
    }.getOrElse { emptyList() }

    fun find(id: String): PlaylistProfile? = loadAll().firstOrNull { it.id == id }

    fun upsert(profile: PlaylistProfile) {
        val profiles = loadAll().filterNot { it.id == profile.id }.toMutableList()
        profiles += profile.copy(updatedAt = System.currentTimeMillis())
        saveAll(profiles)
    }

    fun rename(id: String, name: String): PlaylistProfile? {
        val cleaned = name.trim()
        if (cleaned.isBlank()) return null
        val profiles = loadAll().toMutableList()
        val index = profiles.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = profiles[index].copy(name = cleaned, updatedAt = System.currentTimeMillis())
        profiles[index] = updated
        saveAll(profiles)
        return updated
    }

    fun delete(id: String) {
        saveAll(loadAll().filterNot { it.id == id })
    }

    private fun saveAll(profiles: List<PlaylistProfile>) {
        if (profiles.isEmpty()) {
            preferences.edit().clear().apply()
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
                    put("updated_at", profile.updatedAt)
                },
            )
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val encrypted = cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_PAYLOAD, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
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

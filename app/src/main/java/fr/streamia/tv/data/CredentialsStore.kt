package fr.streamia.tv.data

import android.content.Context
import android.util.Base64
import fr.streamia.tv.domain.ServerCredentials
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialsStore(context: Context) {
    private val preferences = context.getSharedPreferences("secure-session", Context.MODE_PRIVATE)

    fun save(credentials: ServerCredentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val payload = JSONObject().apply {
            put("server", credentials.serverUrl)
            put("username", credentials.username)
            put("password", credentials.password)
        }.toString().toByteArray(Charsets.UTF_8)
        val encrypted = cipher.doFinal(payload)
        preferences.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(): ServerCredentials? = runCatching {
        val iv = Base64.decode(preferences.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val payload = Base64.decode(preferences.getString("payload", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        }
        val json = JSONObject(String(cipher.doFinal(payload), Charsets.UTF_8))
        ServerCredentials(
            serverUrl = json.getString("server"),
            username = json.getString("username"),
            password = json.getString("password"),
        )
    }.getOrNull()

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey = androidKeyStoreAesKey(KEY_ALIAS)

    private companion object {
        const val KEY_ALIAS = "streamia.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

package fr.streamia.tv.data

import android.content.Context
import android.util.Base64
import androidx.annotation.DrawableRes
import fr.streamia.tv.R
import fr.streamia.tv.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Fournisseurs d'IA proposés dans Paramètres. Tous parlent le format OpenAI (`/models`,
 * `/chat/completions`) sauf Claude, qui a ses propres en-têtes.
 */
enum class AiProvider(val label: String, val baseUrl: String, @DrawableRes val logo: Int) {
    OpenRouter("OpenRouter", "https://openrouter.ai/api/v1", R.drawable.ai_logo_openrouter),
    OpenCode("OpenCode Go", "https://opencode.ai/zen/go/v1", R.drawable.ai_logo_opencode),
    OpenCodeZen("OpenCode Zen", "https://opencode.ai/zen/v1", R.drawable.ai_logo_opencode_zen),
    Gemini("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", R.drawable.ai_logo_gemini),
    Grok("Grok (xAI)", "https://api.x.ai/v1", R.drawable.ai_logo_grok),
    Nvidia("NVIDIA NIM", "https://integrate.api.nvidia.com/v1", R.drawable.ai_logo_nvidia),
    Claude("Claude", "https://api.anthropic.com/v1", R.drawable.ai_logo_claude),
    ChatGpt("ChatGPT (OpenAI)", "https://api.openai.com/v1", R.drawable.ai_logo_chatgpt),
    Together("Together AI", "https://api.together.xyz/v1", R.drawable.ai_logo_together),
    HuggingFace("Hugging Face", "https://router.huggingface.co/v1", R.drawable.ai_logo_huggingface),
}

/** Fonctionnalités IA : actives seulement si l'interrupteur est sur Activé, avec une clé et un modèle choisi. */
fun AppSettings.aiActive(hasKey: Boolean): Boolean = aiEnabled && hasKey && aiModels[aiProvider] != null

object AiModelsClient {
    /** Modèles disponibles pour [key] chez [provider], triés ; échec avec un message lisible. */
    suspend fun list(provider: AiProvider, key: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(provider.baseUrl + if (provider == AiProvider.Claude) "/models?limit=1000" else "/models")
                .header("User-Agent", HttpClients.USER_AGENT)
                .apply {
                    if (provider == AiProvider.Claude) {
                        header("x-api-key", key)
                        header("anthropic-version", "2023-06-01")
                    } else if (key.isNotBlank()) {
                        header("Authorization", "Bearer $key")
                    }
                }
                .build()
            HttpClients.api.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error(
                        when (response.code) {
                            401, 403 -> "Clé refusée par ${provider.label} (${response.code})."
                            429 -> "Trop de requêtes chez ${provider.label} (429)."
                            else -> "${provider.label} a répondu ${response.code}."
                        },
                    )
                }
                parseModelIds(response.body?.string().orEmpty()).ifEmpty { error("Aucun modèle renvoyé par ${provider.label}.") }
            }
        }
    }
}

/**
 * Identifiants de modèles d'une réponse `/models` : `{"data":[…]}` (OpenAI, OpenRouter, Claude…) ou
 * tableau direct (Together). Gemini préfixe ses ids par « models/ », que l'API de chat n'attend pas.
 */
internal fun parseModelIds(body: String): List<String> {
    val text = body.trim()
    val array: JSONArray = when {
        text.startsWith("[") -> JSONArray(text)
        else -> JSONObject(text).let { it.optJSONArray("data") ?: it.optJSONArray("models") } ?: return emptyList()
    }
    return (0 until array.length())
        .mapNotNull { index ->
            val item = array.optJSONObject(index)
            (item?.optString("id")?.takeIf(String::isNotBlank) ?: item?.optString("name")?.takeIf(String::isNotBlank)
                ?: array.optString(index).takeIf(String::isNotBlank))?.removePrefix("models/")
        }
        .distinct()
        .sorted()
}

/** Clés d'API chiffrées par le Keystore Android (comme les identifiants Xtream) : jamais dans [AppSettings] ni les sauvegardes. */
class AiKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences("secure-ai-keys", Context.MODE_PRIVATE)

    fun has(provider: AiProvider): Boolean = preferences.contains(provider.name)

    fun get(provider: AiProvider): String? = runCatching {
        val packed = Base64.decode(preferences.getString(provider.name, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, androidKeyStoreAesKey(KEY_ALIAS), GCMParameterSpec(128, packed, 0, IV_BYTES))
        }
        String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    /** Clé vide : supprime celle du fournisseur. */
    fun set(provider: AiProvider, key: String) {
        val clean = key.trim()
        if (clean.isEmpty()) {
            preferences.edit().remove(provider.name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, androidKeyStoreAesKey(KEY_ALIAS)) }
        val packed = cipher.iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        preferences.edit().putString(provider.name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    private companion object {
        const val KEY_ALIAS = "streamia.ai.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

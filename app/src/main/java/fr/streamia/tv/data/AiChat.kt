package fr.streamia.tv.data

import android.content.SharedPreferences
import fr.streamia.tv.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Les trois formats d'API de chat : OpenAI `/chat/completions`, Anthropic `/messages` et OpenAI `/responses`. */
internal enum class AiFormat(val path: String, val label: String) {
    Chat("/chat/completions", "chat"),
    Messages("/messages", "messages"),
    Responses("/responses", "responses"),
}

/**
 * Format d'API et variante de paramètres qui marchent pour chaque modèle. Les passerelles comme OpenCode servent un
 * même fournisseur avec plusieurs formats selon le modèle (GPT, Grok, Muse : `/responses` ; Claude : `/messages`…) :
 * on part de la meilleure hypothèse, on bascule sur une erreur « requête invalide » et on retient ce qui a fonctionné.
 */
internal object AiCompat {
    @Volatile private var preferences: SharedPreferences? = null
    private val memory = ConcurrentHashMap<String, String>()

    fun attach(prefs: SharedPreferences) {
        preferences = prefs
    }

    /** Variantes de paramètres de tokens du format chat : 0 = max_tokens, 1 = max_completion_tokens, 2 = aucun. */
    fun chatVariants(provider: AiProvider, model: String): List<Int> {
        val known = remembered(provider, model)?.second
        val default = if (provider == AiProvider.ChatGpt) listOf(1, 2) else listOf(0, 1, 2)
        return if (known == null) default else listOf(known) + default.filter { it != known }
    }

    fun formatOrder(provider: AiProvider, model: String): List<AiFormat> {
        val id = model.lowercase()
        val guess = when {
            provider == AiProvider.Claude -> listOf(AiFormat.Messages)
            provider == AiProvider.OpenCode || provider == AiProvider.OpenCodeZen -> when {
                id.startsWith("gpt-") || id.startsWith("grok-") || id.startsWith("muse-") ->
                    listOf(AiFormat.Responses, AiFormat.Chat, AiFormat.Messages)
                id.startsWith("claude-") -> listOf(AiFormat.Messages, AiFormat.Chat, AiFormat.Responses)
                else -> listOf(AiFormat.Chat, AiFormat.Messages, AiFormat.Responses)
            }
            else -> listOf(AiFormat.Chat)
        }
        val known = remembered(provider, model)?.first ?: return guess
        return listOf(known) + guess.filter { it != known }
    }

    fun remember(provider: AiProvider, model: String, format: AiFormat, variant: Int) {
        val value = "${format.name}|$variant"
        if (memory.put(key(provider, model), value) != value) preferences?.edit()?.putString(key(provider, model), value)?.apply()
    }

    private fun remembered(provider: AiProvider, model: String): Pair<AiFormat, Int>? {
        val key = key(provider, model)
        val value = memory[key] ?: preferences?.getString(key, null)?.also { memory[key] = it } ?: return null
        val (format, variant) = value.split('|').takeIf { it.size == 2 } ?: return null
        return AiFormat.entries.firstOrNull { it.name == format }?.let { it to (variant.toIntOrNull() ?: 0) }
    }

    private fun key(provider: AiProvider, model: String) = "${provider.name}|$model"
}

/** Appel de chat : une requête logique, une réponse texte ; bascule de format ou de paramètres sur « requête invalide ». */
internal object AiChatClient {
    private val client: OkHttpClient by lazy {
        HttpClients.api.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
    }

    /** Les modèles « raisonneurs » dépensent des tokens à réfléchir avant de répondre : le plafond en tient compte. */
    private const val REASONING_MARGIN_TOKENS = 6_000

    /** Codes qui disent « ce chemin n'existe pas pour ce modèle » : on essaie toujours le format suivant. */
    private val routeCodes = setOf(404, 405, 415)

    /** Mots d'un 400/422 qui visent le format ou un paramètre (et non la clé, la session ou le quota). */
    private val formatHints = Regex(
        "unsupported|not supported|unknown|unrecognized|not allowed|invalid.*(param|field|endpoint|model)|max_tokens|" +
            "max_completion_tokens|max_output_tokens|endpoint|responses|messages|chat/completions",
        RegexOption.IGNORE_CASE,
    )

    /** Au plus 3 requêtes par appel logique, même quand le format du modèle est encore inconnu. */
    private const val MAX_ATTEMPTS = 3

    internal fun worthAnotherFormat(error: AiCallException): Boolean = when {
        error.code in routeCodes -> true
        error.code == 400 || error.code == 422 ->
            // Une erreur de session ou d'authentification se répéterait à l'identique sur tous les formats.
            !error.message.orEmpty().contains("session", ignoreCase = true) && formatHints.containsMatchIn(error.message.orEmpty())
        else -> false
    }

    suspend fun complete(
        provider: AiProvider,
        key: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int,
        session: String,
    ): Result<AiReply> = withContext(Dispatchers.IO) {
        runCatching {
            var firstError: AiCallException? = null
            var attempts = 0
            for (format in AiCompat.formatOrder(provider, model)) {
                val variants = if (format == AiFormat.Chat) AiCompat.chatVariants(provider, model) else listOf(0)
                for (variant in variants) {
                    if (attempts++ >= MAX_ATTEMPTS) throw firstError ?: AiCallException("${provider.label} : aucun format d'API utilisable.")
                    try {
                        val reply = send(provider, key, model, system, user, maxTokens + REASONING_MARGIN_TOKENS, format, variant, session)
                        AiCompat.remember(provider, model, format, variant)
                        return@runCatching reply
                    } catch (error: AiCallException) {
                        if (!worthAnotherFormat(error)) throw error
                        if (firstError == null) firstError = error
                    }
                }
            }
            throw firstError ?: AiCallException("${provider.label} : aucun format d'API utilisable.")
        }
    }

    private fun send(
        provider: AiProvider,
        key: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int,
        format: AiFormat,
        variant: Int,
        session: String,
    ): AiReply {
        val body = JSONObject().put("model", model)
        when (format) {
            AiFormat.Chat -> {
                body.put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)),
                )
                when (variant) {
                    0 -> body.put("max_tokens", maxTokens)
                    1 -> body.put("max_completion_tokens", maxTokens)
                }
            }
            AiFormat.Messages -> body.put("max_tokens", maxTokens)
                .put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            AiFormat.Responses -> body.put("max_output_tokens", maxTokens)
                .put("instructions", system)
                .put("input", user)
        }
        val request = Request.Builder()
            .url(provider.baseUrl + format.path)
            .header("User-Agent", HttpClients.USER_AGENT)
            .apply {
                // OpenCode (Go et Zen) refuse depuis septembre 2026 toute requête sans identifiant de session (400
                // MissingSessionID) : il sert à router une même conversation vers le même serveur et à garder son cache.
                if (provider == AiProvider.OpenCode || provider == AiProvider.OpenCodeZen) header("x-opencode-session", session)
                if (format == AiFormat.Messages) {
                    header("x-api-key", key)
                    header("anthropic-version", "2023-06-01")
                    // Les passerelles qui parlent le format Anthropic acceptent aussi le jeton porteur.
                    if (provider != AiProvider.Claude) header("Authorization", "Bearer $key")
                } else {
                    header("Authorization", "Bearer $key")
                }
            }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val requestQuota = quota(response, "requests")
            val tokenQuota = quota(response, "tokens")
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AiCallException(
                    "${provider.label} a répondu ${response.code}" + errorDetail(text).let { if (it.isEmpty()) "." else " : $it" } +
                        " (${format.label})",
                    response.code, requestQuota, tokenQuota,
                )
            }
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw AiCallException("Réponse illisible de ${provider.label}.", 0, requestQuota, tokenQuota)
            // Certains fournisseurs répondent 200 avec un objet « error » : on affiche leur message plutôt que « réponse vide ».
            if (json.has("error") && !json.isNull("error")) {
                throw AiCallException("${provider.label} : ${errorDetail(text)} (${format.label})", 0, requestQuota, tokenQuota)
            }
            val answer = when (format) {
                AiFormat.Chat -> json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                AiFormat.Messages -> json.optJSONArray("content")?.let { blocks ->
                    (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
                        .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                }
                AiFormat.Responses -> json.optString("output_text").takeIf(String::isNotBlank)
                    ?: json.optJSONArray("output")?.let { items ->
                        (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                            .flatMap { item -> item.optJSONArray("content")?.let { parts -> (0 until parts.length()).mapNotNull { parts.optJSONObject(it) } }.orEmpty() }
                            .filter { it.optString("type") == "output_text" }.joinToString("") { it.optString("text") }
                    }
            }
            val usage = json.optJSONObject("usage")
            return AiReply(
                text = answer?.takeIf(String::isNotBlank)
                    ?: throw AiCallException("Réponse vide de ${provider.label} (${format.label}) : ${(json.optJSONArray("choices")?.optJSONObject(0)?.toString() ?: text).replace(Regex("\\s+"), " ").take(600)}", 0, requestQuota, tokenQuota),
                promptTokens = usage?.let { it.optLong("prompt_tokens", it.optLong("input_tokens")) } ?: 0L,
                completionTokens = usage?.let { it.optLong("completion_tokens", it.optLong("output_tokens")) } ?: 0L,
                requestQuota = requestQuota,
                tokenQuota = tokenQuota,
            )
        }
    }

    /** Message d'erreur du fournisseur (`error.message`, `message` ou début du corps), raccourci. */
    private fun errorDetail(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val message = json?.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)
            ?: json?.optString("error")?.takeIf { it.isNotBlank() && it != "null" }
            ?: json?.optString("message")?.takeIf(String::isNotBlank)
            ?: body
        return message.replace(Regex("\\s+"), " ").trim().take(160)
    }

    /** « restant / limite » lu dans les en-têtes de quota (OpenAI, Groq, Anthropic…), ou null si le fournisseur n'en donne pas. */
    private fun quota(response: Response, kind: String): String? {
        val remaining = response.header("x-ratelimit-remaining-$kind") ?: response.header("anthropic-ratelimit-$kind-remaining")
        val limit = response.header("x-ratelimit-limit-$kind") ?: response.header("anthropic-ratelimit-$kind-limit")
        return when {
            remaining != null && limit != null -> "$remaining / $limit"
            remaining != null -> remaining
            else -> null
        }
    }
}

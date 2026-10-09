package fr.streamia.tv.data

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Fonctions IA de l'application, pour ventiler la consommation. */
enum class AiFeature(val label: String) {
    Translation("Traductions"),
    Similar("Similaires"),
    Titles("Titres"),
}

/** Ce que le fournisseur annonce d'un modèle dans sa liste `/models` (tout est facultatif). */
data class AiModelInfo(
    val contextTokens: Long? = null,
    val maxOutputTokens: Long? = null,
    /** Prix en dollars par million de tokens. */
    val inputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
)

/** Consommation cumulée d'un modèle chez un fournisseur. */
data class AiUsage(
    val provider: AiProvider,
    val model: String,
    val requests: Int = 0,
    val failures: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalMillis: Long = 0,
    val lastAtMillis: Long = 0,
    val lastError: String? = null,
    val byFeature: Map<AiFeature, Int> = emptyMap(),
    /** Derniers quotas annoncés par le fournisseur dans ses en-têtes (« restant / limite »), s'il en donne. */
    val requestQuota: String? = null,
    val tokenQuota: String? = null,
    val info: AiModelInfo? = null,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
    val averageMillis: Long get() = if (requests - failures > 0) totalMillis / (requests - failures) else 0L

    /** Coût estimé en dollars d'après les tarifs annoncés ; null si le fournisseur n'en donne pas. */
    val estimatedCost: Double?
        get() {
            val input = info?.inputPricePerMillion ?: return null
            val output = info.outputPricePerMillion ?: return null
            return promptTokens * input / 1_000_000.0 + completionTokens * output / 1_000_000.0
        }
}

/** Résultat d'un appel : texte, tokens facturés et quotas annoncés. */
internal class AiReply(
    val text: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val requestQuota: String?,
    val tokenQuota: String?,
)

/** Échec d'un appel, avec les quotas lus dans les en-têtes (utiles surtout pour un 429). */
internal class AiCallException(message: String, val requestQuota: String? = null, val tokenQuota: String? = null) : Exception(message)

/** Compteurs d'utilisation par fournisseur et modèle, gardés sur la TV (jamais dans les sauvegardes). */
class AiUsageStore(context: Context) {
    private val preferences = context.getSharedPreferences("ai-usage", Context.MODE_PRIVATE)

    @Synchronized
    internal fun record(
        provider: AiProvider,
        model: String,
        feature: AiFeature,
        reply: AiReply?,
        error: AiCallException?,
        elapsedMillis: Long,
    ) {
        val key = key(provider, model)
        val json = runCatching { JSONObject(preferences.getString(key, null)!!) }.getOrDefault(JSONObject())
        json.put("requests", json.optInt("requests") + 1)
        if (reply != null) {
            json.put("prompt", json.optLong("prompt") + reply.promptTokens)
            json.put("completion", json.optLong("completion") + reply.completionTokens)
            json.put("millis", json.optLong("millis") + elapsedMillis)
            json.put("lastError", JSONObject.NULL)
        } else {
            json.put("failures", json.optInt("failures") + 1)
            json.put("lastError", error?.message ?: "Erreur inconnue")
        }
        json.put("lastAt", System.currentTimeMillis())
        json.put(feature.name, json.optInt(feature.name) + 1)
        (reply?.requestQuota ?: error?.requestQuota)?.let { json.put("requestQuota", it) }
        (reply?.tokenQuota ?: error?.tokenQuota)?.let { json.put("tokenQuota", it) }
        preferences.edit().putString(key, json.toString()).apply()
    }

    /** Mémorise ce que le fournisseur annonce du modèle choisi, pour l'afficher même hors ligne. */
    @Synchronized
    fun rememberInfo(provider: AiProvider, model: String) {
        val info = AiModelInfos.get(provider, model) ?: return
        val json = JSONObject()
        info.contextTokens?.let { json.put("context", it) }
        info.maxOutputTokens?.let { json.put("maxOutput", it) }
        info.inputPricePerMillion?.let { json.put("inputPrice", it) }
        info.outputPricePerMillion?.let { json.put("outputPrice", it) }
        preferences.edit().putString("info|${key(provider, model)}", json.toString()).apply()
    }

    @Synchronized
    fun all(): List<AiUsage> = preferences.all.keys
        .filterNot { it.startsWith("info|") }
        .mapNotNull { key ->
            val (providerName, model) = key.split('|', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val provider = AiProvider.entries.firstOrNull { it.name == providerName } ?: return@mapNotNull null
            val json = runCatching { JSONObject(preferences.getString(key, null)!!) }.getOrNull() ?: return@mapNotNull null
            AiUsage(
                provider = provider,
                model = model,
                requests = json.optInt("requests"),
                failures = json.optInt("failures"),
                promptTokens = json.optLong("prompt"),
                completionTokens = json.optLong("completion"),
                totalMillis = json.optLong("millis"),
                lastAtMillis = json.optLong("lastAt"),
                lastError = json.optString("lastError").takeIf { it.isNotBlank() && it != "null" },
                byFeature = AiFeature.entries.associateWith { json.optInt(it.name) }.filterValues { it > 0 },
                requestQuota = json.optString("requestQuota").takeIf(String::isNotBlank),
                tokenQuota = json.optString("tokenQuota").takeIf(String::isNotBlank),
                info = storedInfo(key) ?: AiModelInfos.get(provider, model),
            )
        }
        .sortedByDescending(AiUsage::lastAtMillis)

    @Synchronized
    fun clear() {
        val infos = preferences.all.filterKeys { it.startsWith("info|") }
        val editor = preferences.edit().clear()
        infos.forEach { (key, value) -> editor.putString(key, value as String) }
        editor.apply()
    }

    private fun storedInfo(key: String): AiModelInfo? = runCatching {
        val json = JSONObject(preferences.getString("info|$key", null)!!)
        AiModelInfo(
            contextTokens = json.optLong("context").takeIf { it > 0 },
            maxOutputTokens = json.optLong("maxOutput").takeIf { it > 0 },
            inputPricePerMillion = json.optDouble("inputPrice").takeIf { !it.isNaN() },
            outputPricePerMillion = json.optDouble("outputPrice").takeIf { !it.isNaN() },
        )
    }.getOrNull()

    private fun key(provider: AiProvider, model: String) = "${provider.name}|$model"
}

/** Infos de modèles lues dans les dernières listes `/models` chargées (en mémoire). */
internal object AiModelInfos {
    private val byModel = ConcurrentHashMap<String, AiModelInfo>()

    fun get(provider: AiProvider, model: String): AiModelInfo? = byModel["${provider.name}|$model"]

    fun putAll(provider: AiProvider, infos: Map<String, AiModelInfo>) {
        infos.forEach { (model, info) -> byModel["${provider.name}|$model"] = info }
    }
}

/**
 * Infos de chaque modèle d'une réponse `/models`, selon ce que le fournisseur donne : fenêtre de contexte,
 * sortie maximale et tarifs (OpenRouter : par token ; Together, Hugging Face : par million de tokens).
 */
internal fun parseModelInfos(body: String): Map<String, AiModelInfo> = runCatching {
    val text = body.trim()
    val array = if (text.startsWith("[")) org.json.JSONArray(text) else JSONObject(text).let { it.optJSONArray("data") ?: it.optJSONArray("models") }
    val result = HashMap<String, AiModelInfo>()
    for (index in 0 until (array?.length() ?: 0)) {
        val item = array?.optJSONObject(index) ?: continue
        val id = (item.optString("id").takeIf(String::isNotBlank) ?: item.optString("name")).removePrefix("models/")
        if (id.isBlank()) continue
        val source = item.optJSONArray("providers")?.optJSONObject(0)?.takeIf { item.optJSONObject("pricing") == null } ?: item
        val pricing = source.optJSONObject("pricing")
        val perToken = pricing?.optString("prompt")?.toDoubleOrNull()?.times(1_000_000.0) to
            pricing?.optString("completion")?.toDoubleOrNull()?.times(1_000_000.0)
        val perMillion = pricing?.optDouble("input")?.takeIf { !it.isNaN() } to pricing?.optDouble("output")?.takeIf { !it.isNaN() }
        val info = AiModelInfo(
            contextTokens = listOf("context_length", "context_window", "max_input_tokens", "inputTokenLimit")
                .firstNotNullOfOrNull { source.optLong(it).takeIf { v -> v > 0 } ?: item.optLong(it).takeIf { v -> v > 0 } },
            maxOutputTokens = (
                listOf("max_completion_tokens", "max_output_tokens", "max_tokens", "outputTokenLimit")
                    .firstNotNullOfOrNull { item.optLong(it).takeIf { v -> v > 0 } }
                    ?: item.optJSONObject("top_provider")?.optLong("max_completion_tokens")?.takeIf { it > 0 }
                ),
            inputPricePerMillion = perToken.first ?: perMillion.first,
            outputPricePerMillion = perToken.second ?: perMillion.second,
        )
        if (info != AiModelInfo()) result[id] = info
    }
    result
}.getOrDefault(emptyMap())

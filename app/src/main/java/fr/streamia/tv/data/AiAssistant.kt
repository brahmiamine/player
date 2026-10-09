package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Miroir observable de « l'assistant IA est actif » (interrupteur sur Activé, clé enregistrée, modèle choisi).
 * Tous les écrans le lisent pour se remettre à l'état sans IA dès que l'assistant est désactivé.
 */
object AiGate {
    private val state = MutableStateFlow(false)
    val active: StateFlow<Boolean> = state
    fun set(value: Boolean) {
        state.value = value
    }
}

/** Langues proposées pour la traduction des descriptions : code → nom (dans la langue de l'interface). */
object AiLanguages {
    val all: List<Pair<String, String>> = listOf(
        "fr" to "Français",
        "en" to "Anglais",
        "es" to "Espagnol",
        "de" to "Allemand",
        "it" to "Italien",
        "pt" to "Portugais",
        "ar" to "Arabe",
    )

    fun name(code: String): String = all.firstOrNull { it.first == code }?.second ?: "Français"
}

/**
 * Fonctions IA de l'application : traduction des descriptions et classement des contenus similaires. Chaque appel vérifie d'abord que l'assistant est actif : désactivé, rien n'est
 * envoyé au fournisseur. Les réponses sont gardées sur disque pour ne jamais payer deux fois la même.
 */
class AiAssistant(context: Context, private val keyStore: AiKeyStore, val usage: AiUsageStore) {
    private val appContext = context.applicationContext

    private class Config(val provider: AiProvider, val model: String, val language: String)

    @Volatile private var config: Config? = null

    private val cache by lazy { AiCache(File(context.applicationContext.cacheDir, "ai-cache.json")) }
    fun isActive(): Boolean = config != null

    /** À appeler à chaque changement de réglage ou de clé. Ne touche pas au Keystore (la clé est lue à l'appel). */
    fun sync(settings: AppSettings) {
        val model = settings.aiModels[settings.aiProvider]
        val usable = settings.aiEnabled && model != null && keyStore.has(settings.aiProvider)
        config = if (usable) Config(settings.aiProvider, model!!, settings.aiLanguage) else null
        AiGate.set(usable)
    }

    private suspend fun chat(cfg: Config, feature: AiFeature, system: String, user: String, maxTokens: Int): String? {
        val key = withContext(Dispatchers.IO) { keyStore.get(cfg.provider) } ?: return null
        val startedAt = System.currentTimeMillis()
        val result = AiChatClient.complete(cfg.provider, key, cfg.model, system, user, maxTokens)
        usage.record(
            cfg.provider, cfg.model, feature,
            reply = result.getOrNull(),
            error = result.exceptionOrNull()?.let { it as? AiCallException ?: AiCallException(it.message ?: "Erreur réseau") },
            elapsedMillis = System.currentTimeMillis() - startedAt,
        )
        val answer = result.getOrNull()?.text
        // Désactivé pendant l'attente : la réponse est jetée.
        return answer?.trim()?.takeIf { it.isNotEmpty() && config === cfg }
    }

    /** Description traduite dans la langue choisie ; null si inutile (déjà dans cette langue), impossible ou IA coupée. */
    suspend fun translatePlot(plot: String): String? {
        val cfg = config ?: return null
        val text = plot.trim()
        if (text.length < MIN_PLOT_CHARS || LanguageGuess.isLikely(text, cfg.language)) return null
        val cacheKey = "t|${cfg.language}|${text.length}|${text.hashCode()}"
        cache.get(cacheKey)?.let { return it }
        val translated = chat(
            cfg,
            AiFeature.Translation,
            system = "Tu es un traducteur. Traduis le texte en ${AiLanguages.name(cfg.language)}. " +
                "Réponds uniquement par la traduction, sans commentaire ni guillemets.",
            user = text.take(MAX_PLOT_CHARS),
            maxTokens = 1_500,
        ) ?: return null
        cache.put(cacheKey, translated)
        return translated
    }

    /**
     * Texte d'un sous-titre SRT/WebVTT ([vtt]) traduit de [sourceLanguage] vers la langue choisie ; null si l'IA est
     * coupée, si la traduction échoue ou si elle est inutile. Peu de requêtes : de gros lots de texte seul (ni numéros ni
     * temps), chaque lot terminé est gardé sur disque (une reprise ne repaie rien) et le résultat complet aussi.
     */
    suspend fun translateSubtitle(
        text: String,
        vtt: Boolean,
        sourceLanguage: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String? {
        val cfg = config ?: return null
        if (sourceLanguage == cfg.language) return null
        val cues = parseCues(text)
        if (cues.isEmpty()) return null
        val key = "${text.length}-${text.hashCode().toUInt()}-${cfg.language}-${if (vtt) "vtt" else "srt"}"
        val store = withContext(Dispatchers.IO) { AiSubtitleCache(File(appContext.cacheDir, "ai-subtitles")) }
        withContext(Dispatchers.IO) { store.full(key).takeIf(File::exists)?.readText() }?.let { return it }
        val batches = packCues(cues)
        val translated = HashMap<Int, List<String>>()
        for ((position, batch) in batches.withIndex()) {
            if (config !== cfg) return null
            onProgress(position, batches.size)
            val saved = withContext(Dispatchers.IO) { store.batch(key, position).takeIf(File::exists)?.readText() }
            val answer = saved ?: chat(
                cfg,
                AiFeature.Subtitles,
                system = "Tu traduis des sous-titres de ${AiLanguages.name(sourceLanguage)} vers ${AiLanguages.name(cfg.language)}. " +
                    "Chaque ligne est « numéro|texte » ; « // » sépare deux lignes d'un même sous-titre. " +
                    "Traduis de façon naturelle et concise, garde les balises (<i>…), la ponctuation et les « // ». " +
                    "Réponds uniquement par les mêmes lignes « numéro|traduction », sans rien ajouter.",
                user = batch.joinToString("\n") { (number, line) -> "$number|$line" },
                maxTokens = batch.sumOf { it.second.length } / 2 + 300,
            )?.also { reply -> withContext(Dispatchers.IO) { store.batch(key, position).writeText(reply) } } ?: return null
            val lines = parseTranslatedLines(answer)
            // Plus de 30 % de lignes manquantes : réponse inexploitable, on abandonne (les lots déjà payés restent).
            if (batch.count { it.first !in lines } * 10 > batch.size * 3) return null
            translated += lines
        }
        onProgress(batches.size, batches.size)
        val result = renderCues(cues.mapIndexed { index, cue -> SubtitleCue(cue.timing, translated[index + 1] ?: cue.lines) }, vtt)
        withContext(Dispatchers.IO) {
            store.full(key).writeText(result)
            store.finish(key)
        }
        return result
    }

    /** Clés de [candidates] classées de la plus proche à la moins proche de [source] ; null si indisponible. */
    suspend fun rerankSimilar(source: MediaEntry, candidates: List<MediaEntry>): List<String>? {
        val cfg = config ?: return null
        if (candidates.size < 3) return null
        val items = candidates.take(MAX_RERANK_CANDIDATES)
        val cacheKey = "r|${source.key}|${items.joinToString(",") { it.key }.hashCode()}"
        cache.get(cacheKey)?.let { return it.split(',').filter(String::isNotEmpty) }
        val answer = chat(
            cfg,
            AiFeature.Similar,
            system = "Tu classes des films ou séries par proximité (genre, ambiance, public) avec un titre de référence. " +
                "Réponds uniquement par un tableau JSON des numéros, du plus proche au moins proche, par exemple [3,1,2]. " +
                "N'invente aucun numéro et n'en répète aucun.",
            user = "Référence : ${source.displayName}\nCandidats :\n" +
                items.mapIndexed { index, entry -> "${index + 1}. ${entry.displayName}" }.joinToString("\n"),
            maxTokens = 200,
        ) ?: return null
        val order = parseRanking(answer, items.size) ?: return null
        val keys = order.map { items[it].key } + candidates.drop(items.size).map(MediaEntry::key)
        cache.put(cacheKey, keys.joinToString(","))
        return keys
    }

    private companion object {
        const val MIN_PLOT_CHARS = 30
        const val MAX_PLOT_CHARS = 1_500
        const val MAX_RERANK_CANDIDATES = 20
    }
}

/** Numéros (1 à [size]) de la réponse du modèle, sans doublon ; les oubliés sont ajoutés à la fin. Null si inexploitable. */
internal fun parseRanking(answer: String, size: Int): List<Int>? {
    val seen = LinkedHashSet<Int>()
    Regex("\\d+").findAll(answer).forEach { match ->
        val number = match.value.toIntOrNull() ?: return@forEach
        if (number in 1..size) seen += number - 1
    }
    if (seen.size < (size + 1) / 2) return null
    for (index in 0 until size) seen += index
    return seen.toList()
}

/** Détection grossière de la langue d'un texte (mots courants), pour ne pas traduire ce qui l'est déjà. */
internal object LanguageGuess {
    private val commonWords: Map<String, Set<String>> = mapOf(
        "fr" to setOf("le", "la", "les", "des", "un", "une", "et", "est", "dans", "qui", "que", "pour", "avec", "son", "ses", "sur", "au", "du", "il", "elle", "sont", "pas", "ce", "cette"),
        "en" to setOf("the", "and", "of", "to", "in", "is", "with", "his", "her", "for", "that", "who", "on", "as", "an", "from", "by", "are", "their", "he", "she", "it"),
        "es" to setOf("el", "la", "los", "las", "de", "y", "en", "un", "una", "que", "con", "por", "su", "sus", "es", "del", "al", "se", "para", "como"),
        "de" to setOf("der", "die", "das", "und", "ein", "eine", "ist", "mit", "von", "zu", "den", "dem", "sich", "auf", "nicht", "für", "im", "er", "sie"),
        "it" to setOf("il", "lo", "la", "gli", "le", "un", "una", "di", "che", "con", "per", "del", "della", "nel", "sono", "si", "da", "ma", "suo", "sua"),
        "pt" to setOf("o", "os", "as", "um", "uma", "de", "que", "em", "com", "para", "por", "do", "da", "dos", "das", "seu", "sua", "se", "não", "é"),
    )

    fun isLikely(text: String, language: String): Boolean {
        val letters = text.count(Char::isLetter)
        if (letters == 0) return true
        val arabic = text.count { it in '؀'..'ۿ' }
        if (language == "ar") return arabic * 2 > letters
        if (arabic * 2 > letters) return false
        val tokens = text.lowercase().split(Regex("[^\\p{L}]+")).filter(String::isNotEmpty)
        val scores = commonWords.mapValues { (_, words) -> tokens.count { it in words } }
        val own = scores[language] ?: return false
        return own >= 3 && own >= (scores.values.maxOrNull() ?: 0)
    }
}

/** Petit cache clé/valeur sur disque (JSON), borné aux entrées les plus récemment utilisées. */
private class AiCache(private val file: File) {
    private val entries = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_ENTRIES
    }
    private var loaded = false

    @Synchronized private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val json = JSONObject(file.readText())
            json.keys().forEach { entries[it] = json.getString(it) }
        }
    }

    @Synchronized fun get(key: String): String? {
        ensureLoaded()
        return entries[key]
    }

    @Synchronized fun put(key: String, value: String) = putAll(mapOf(key to value))

    @Synchronized fun putAll(values: Map<String, String>) {
        ensureLoaded()
        entries.putAll(values)
        runCatching {
            val temp = File(file.parentFile, file.name + ".tmp")
            val json = JSONObject()
            entries.forEach { (key, value) -> json.put(key, value) }
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 2_000
    }
}

/** Appel `/chat/completions` (format OpenAI) ou `/messages` (Claude) : une requête, une réponse texte. */
internal object AiChatClient {
    private val client: OkHttpClient by lazy {
        HttpClients.api.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()
    }

    suspend fun complete(
        provider: AiProvider,
        key: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int,
    ): Result<AiReply> = withContext(Dispatchers.IO) {
        runCatching {
            val claude = provider == AiProvider.Claude
            val body = JSONObject().put("model", model)
            if (claude) {
                body.put("max_tokens", maxTokens)
                    .put("system", system)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            } else {
                body.put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)),
                )
                // Les modèles récents d'OpenAI refusent max_tokens et toute température autre que 1.
                if (provider == AiProvider.ChatGpt) {
                    body.put("max_completion_tokens", maxTokens + REASONING_MARGIN_TOKENS)
                } else {
                    body.put("max_tokens", maxTokens).put("temperature", 0)
                }
            }
            val request = Request.Builder()
                .url(provider.baseUrl + if (claude) "/messages" else "/chat/completions")
                .header("User-Agent", HttpClients.USER_AGENT)
                .apply {
                    if (claude) {
                        header("x-api-key", key)
                        header("anthropic-version", "2023-06-01")
                    } else {
                        header("Authorization", "Bearer $key")
                    }
                }
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val requestQuota = quota(response, "requests")
                val tokenQuota = quota(response, "tokens")
                if (!response.isSuccessful) {
                    throw AiCallException("${provider.label} a répondu ${response.code}.", requestQuota, tokenQuota)
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val usage = json.optJSONObject("usage")
                val text = if (claude) {
                    json.optJSONArray("content")?.optJSONObject(0)?.optString("text")
                } else {
                    json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                }
                AiReply(
                    text = text?.takeIf(String::isNotBlank) ?: throw AiCallException("Réponse vide de ${provider.label}.", requestQuota, tokenQuota),
                    promptTokens = usage?.let { it.optLong("prompt_tokens", it.optLong("input_tokens")) } ?: 0L,
                    completionTokens = usage?.let { it.optLong("completion_tokens", it.optLong("output_tokens")) } ?: 0L,
                    requestQuota = requestQuota,
                    tokenQuota = tokenQuota,
                )
            }
        }
    }

    /** « restant / limite » lu dans les en-têtes de quota (OpenAI, Groq, Anthropic…), ou null si le fournisseur n'en donne pas. */
    private fun quota(response: okhttp3.Response, kind: String): String? {
        val remaining = response.header("x-ratelimit-remaining-$kind") ?: response.header("anthropic-ratelimit-$kind-remaining")
        val limit = response.header("x-ratelimit-limit-$kind") ?: response.header("anthropic-ratelimit-$kind-limit")
        return when {
            remaining != null && limit != null -> "$remaining / $limit"
            remaining != null -> remaining
            else -> null
        }
    }

    private const val REASONING_MARGIN_TOKENS = 1_000
}

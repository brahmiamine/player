package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

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
        "ar" to "Arabe",
        "fr" to "Français",
        "en" to "Anglais",
    )

    fun name(code: String): String = all.firstOrNull { it.first == code }?.second ?: "Arabe"
}

/**
 * Fonctions IA de l'application : traduction des descriptions et classement des contenus similaires. Chaque appel vérifie d'abord que l'assistant est actif : désactivé, rien n'est
 * envoyé au fournisseur. Les réponses sont gardées sur disque pour ne jamais payer deux fois la même.
 */
class AiAssistant(context: Context, private val keyStore: AiKeyStore, val usage: AiUsageStore) {
    private val appContext = context.applicationContext

    init {
        AiCompat.attach(appContext.getSharedPreferences("ai-compat", Context.MODE_PRIVATE))
    }

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

    /** [session] : même valeur pour les requêtes d'un même travail (les lots d'un sous-titre), nouvelle sinon. */
    private suspend fun chat(
        cfg: Config,
        feature: AiFeature,
        system: String,
        user: String,
        maxTokens: Int,
        session: String = newSession(),
    ): String? {
        val key = withContext(Dispatchers.IO) { keyStore.get(cfg.provider) } ?: return null
        val startedAt = System.currentTimeMillis()
        val result = AiChatClient.complete(cfg.provider, key, cfg.model, system, user, maxTokens, session)
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
        val text = plotToTranslate(cfg, plot) ?: return null
        val cacheKey = plotCacheKey(cfg, text)
        cached(cacheKey)?.let { return it }
        val translated = chat(
            cfg,
            AiFeature.Translation,
            system = "Tu es un traducteur. Traduis le texte en ${AiLanguages.name(cfg.language)}. " +
                "Réponds uniquement par la traduction, sans commentaire ni guillemets.",
            user = text.take(MAX_PLOT_CHARS),
            maxTokens = 1_500,
        ) ?: return null
        store(cacheKey, translated)
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
        val session = newSession()
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
                session = session,
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
        val items = rankable(candidates) ?: return null
        val cacheKey = rankCacheKey(source, items)
        cached(cacheKey)?.let { return it.split(',').filter(String::isNotEmpty) }
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
        store(cacheKey, keys.joinToString(","))
        return keys
    }

    /**
     * Fonctions IA d'une fiche en **une seule requête** quand la description est à traduire et les similaires à
     * classer : la moitié des appels d'une fiche jamais ouverte. Ce qui est déjà en cache n'est pas redemandé ;
     * s'il ne reste qu'une tâche, elle part seule.
     */
    suspend fun enrichFiche(source: MediaEntry, plot: String?, candidates: List<MediaEntry>): FicheAi {
        val cfg = config ?: return FicheAi(null, null)
        val text = plot?.let { plotToTranslate(cfg, it) }
        val plotKey = text?.let { plotCacheKey(cfg, it) }
        val items = rankable(candidates)
        val rankKey = items?.let { rankCacheKey(source, it) }
        val cachedPlot = plotKey?.let { cached(it) }
        val cachedKeys = rankKey?.let { key -> cached(key)?.split(',')?.filter(String::isNotEmpty) }
        val needPlot = text != null && cachedPlot == null
        val needRank = items != null && cachedKeys == null
        if (!needPlot || !needRank) {
            return FicheAi(
                plot = cachedPlot ?: if (needPlot && plot != null) translatePlot(plot) else null,
                similarKeys = cachedKeys ?: if (needRank) rerankSimilar(source, candidates) else null,
            )
        }
        val answer = chat(
            cfg,
            AiFeature.Fiche,
            system = "Tu fais deux tâches pour la fiche d'un film ou d'une série. " +
                "1) Classe les candidats par proximité (genre, ambiance, public) avec la référence. " +
                "2) Traduis la description en ${AiLanguages.name(cfg.language)}. " +
                "Réponds exactement sous cette forme, sans rien d'autre :\n" +
                "ORDRE: [numéros du plus proche au moins proche, chacun une fois]\nTRADUCTION:\n<la traduction>",
            user = "Référence : ${source.displayName}\nCandidats :\n" +
                items!!.mapIndexed { index, entry -> "${index + 1}. ${entry.displayName}" }.joinToString("\n") +
                "\n\nDescription :\n" + text!!.take(MAX_PLOT_CHARS),
            maxTokens = 1_700,
        ) ?: return FicheAi(null, null)
        val (order, translated) = parseFicheAnswer(answer, items.size)
        val keys = order?.let { indexes -> indexes.map { items[it].key } + candidates.drop(items.size).map(MediaEntry::key) }
        translated?.let { store(plotKey!!, it) }
        keys?.let { store(rankKey!!, it.joinToString(",")) }
        return FicheAi(translated, keys)
    }

    private fun plotToTranslate(cfg: Config, plot: String): String? =
        plot.trim().takeUnless { it.length < MIN_PLOT_CHARS || LanguageGuess.isLikely(it, cfg.language) || LanguageGuess.isLikely(it, "ar") }

    private fun plotCacheKey(cfg: Config, text: String) = "t|${cfg.language}|${text.length}|${text.hashCode()}"

    private fun rankable(candidates: List<MediaEntry>): List<MediaEntry>? =
        candidates.takeIf { it.size >= 3 }?.take(MAX_RERANK_CANDIDATES)

    private fun rankCacheKey(source: MediaEntry, items: List<MediaEntry>) = "r|${source.key}|${items.joinToString(",") { it.key }.hashCode()}"

    // Cache disque lu et écrit hors du thread principal : les fiches appellent ces fonctions depuis Main.
    private suspend fun cached(key: String): String? = withContext(Dispatchers.IO) { cache.get(key) }

    private suspend fun store(key: String, value: String) = withContext(Dispatchers.IO) { cache.put(key, value) }

    private fun newSession(): String = "streamia-" + java.util.UUID.randomUUID()

    private companion object {
        const val MIN_PLOT_CHARS = 30
        const val MAX_PLOT_CHARS = 1_500
        const val MAX_RERANK_CANDIDATES = 20
    }
}

/** Ce que l'IA apporte à une fiche : description traduite et ordre des similaires (null = inutile ou indisponible). */
class FicheAi(val plot: String?, val similarKeys: List<String>?)

/** Réponse combinée « ORDRE: […] / TRADUCTION: … » : ordre (indices) et traduction, chacun null s'il manque. */
internal fun parseFicheAnswer(answer: String, size: Int): Pair<List<Int>?, String?> {
    val orderMarker = Regex("^\\W*ORDRE\\s*:(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).find(answer)
    val order = orderMarker?.groupValues?.get(1)?.let { parseRanking(it, size) }
    val marker = Regex("TRADUCTION\\s*:", RegexOption.IGNORE_CASE).find(answer)
    // Traduction : après « TRADUCTION: », sans la ligne ORDRE si le modèle l'a mise en dernier.
    val translation = marker?.let { found ->
        val end = orderMarker?.range?.first?.takeIf { it > found.range.last } ?: answer.length
        answer.substring(found.range.last + 1, end).trim { it.isWhitespace() || it in "\"«»*" }
    }?.takeIf(String::isNotEmpty)
    return order to translation
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

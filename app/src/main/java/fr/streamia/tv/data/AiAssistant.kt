package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
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
 * Fonctions IA de l'application : traduction des descriptions, classement des similaires, recherche en langage naturel,
 * « Ce soir ? », résumés de séries, avis rapides, « Quoi de neuf ? », conseils de lecture et télécommande.
 * Chaque appel vérifie d'abord que l'assistant est actif : désactivé, rien n'est envoyé au fournisseur et une réponse
 * en vol est jetée. Les réponses valides sont gardées sur disque pour ne jamais payer deux fois la même, et chaque
 * fonction tient en **une seule requête** qui répond en JSON.
 */
class AiAssistant(context: Context, private val keyStore: AiKeyStore, val usage: AiUsageStore) {
    private val appContext = context.applicationContext

    init {
        AiCompat.attach(appContext.getSharedPreferences("ai-compat", Context.MODE_PRIVATE))
    }

    private class Config(val provider: AiProvider, val model: String, val language: String)

    @Volatile private var config: Config? = null

    private val cache by lazy { AiCache(File(context.applicationContext.cacheDir, "ai-cache.json")) }
    private val glossaryStore by lazy { AiGlossaryStore(File(context.applicationContext.filesDir, "ai-glossary")) }
    fun isActive(): Boolean = config != null

    /** Langue choisie pour les textes de l'assistant (code), null si l'assistant est coupé. */
    val language: String? get() = config?.language

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

    /**
     * Une requête « structurée » : réponse en cache si elle y est (et reste exploitable), sinon un appel dont la réponse
     * n'est gardée que si [parse] sait la lire. Null si l'assistant est coupé, si l'appel échoue ou si la réponse est inutilisable.
     */
    private suspend fun <T : Any> askParsed(
        cfg: Config,
        feature: AiFeature,
        cacheKey: String?,
        system: String,
        user: String,
        maxTokens: Int,
        parse: (String) -> T?,
    ): T? {
        if (cacheKey != null) cached(cacheKey)?.let { raw -> parse(raw)?.let { return it } }
        val answer = chat(cfg, feature, system, user, maxTokens) ?: return null
        val parsed = parse(answer) ?: return null
        if (cacheKey != null && config === cfg) store(cacheKey, answer)
        return parsed
    }

    private fun languageLabel(cfg: Config) = AiLanguages.name(cfg.language).lowercase()

    private fun today(): String = java.time.LocalDate.now().toString()

    private fun week(): String = java.time.LocalDate.now().let { "${it.year}-${it.dayOfYear / 7}" }

    /** Plan de recherche pour une demande en langage naturel ; null si l'assistant est coupé ou ne comprend pas. */
    suspend fun interpretSearch(query: String, forcedType: MediaType?): AiSearchPlan? {
        val cfg = config ?: return null
        val normalized = normalizeForMatch(query)
        if (normalized.length < MIN_NATURAL_QUERY_CHARS) return null
        return askParsed(
            cfg, AiFeature.Search,
            cacheKey = "s|${cfg.language}|${forcedType?.name}|$normalized",
            system = searchPlanSystemPrompt(languageLabel(cfg)),
            user = query.trim().take(200),
            maxTokens = 600,
        ) { parseSearchPlan(it, forcedType) }
    }

    /** Cinq propositions pour ce soir parmi [pool] ; les identifiants rendus sont tous dans [pool]. */
    suspend fun tonightPicks(pool: List<AiCandidate>, answers: TonightAnswers, tastes: List<String>): List<AiPick>? {
        val cfg = config ?: return null
        if (pool.size < MIN_POOL) return null
        val ids = pool.mapTo(HashSet(), AiCandidate::id)
        val key = "n|${cfg.language}|${today()}|${pool.joinToString("|") { it.label }.hashCode()}|${answers.mood}|${answers.length}|${answers.company}|${tastes.hashCode()}"
        return askParsed(
            cfg, AiFeature.Tonight, key,
            system = tonightSystemPrompt(languageLabel(cfg), pool),
            user = tonightUserPrompt(answers, tastes),
            maxTokens = 700,
        ) { parsePicks(it, ids) }
    }

    /** Raison (une phrase) pour chaque recommandation de [items] d'après les [tastes] ; identifiant ↦ raison. */
    suspend fun explainRecommendations(tastes: List<String>, items: List<AiCandidate>): Map<String, String>? {
        val cfg = config ?: return null
        if (tastes.isEmpty() || items.isEmpty()) return null
        val ids = items.mapTo(HashSet(), AiCandidate::id)
        return askParsed(
            cfg, AiFeature.Explain,
            cacheKey = "x|${cfg.language}|${today()}|${(tastes + items.map { it.label }).hashCode()}",
            system = explainSystemPrompt(languageLabel(cfg)),
            user = explainUserPrompt(tastes, items),
            maxTokens = 900,
        ) { parseReasons(it, ids) }
    }

    /** Résumé de ce qui passe maintenant d'après [lines] (données réelles uniquement) ; valable dix minutes. */
    suspend fun whatsNew(lines: List<AiCandidate>, nowLabel: String): AiBrief? {
        val cfg = config ?: return null
        if (lines.isEmpty()) return null
        val refs = lines.mapTo(HashSet(), AiCandidate::id)
        return askParsed(
            cfg, AiFeature.Brief,
            cacheKey = "b|${cfg.language}|${System.currentTimeMillis() / BRIEF_TTL_MS}|${lines.joinToString("|") { it.label }.hashCode()}",
            system = briefSystemPrompt(languageLabel(cfg), nowLabel, lines),
            user = BRIEF_USER_PROMPT,
            maxTokens = 800,
        ) { parseBrief(it, refs) }
    }

    /** « Précédemment dans… » : rappel sans spoiler de ce qui a été vu ; null sans matière suffisante. */
    suspend fun recap(input: RecapInput): String? {
        val cfg = config ?: return null
        if (!input.hasMaterial) return null
        return askParsed(
            cfg, AiFeature.Recap,
            cacheKey = "rc|${cfg.language}|${input.cacheKey()}",
            system = recapSystemPrompt(languageLabel(cfg)),
            user = input.asPrompt(),
            maxTokens = 600,
        ) { answer -> answer.trim().trim('"', '«', '»').takeIf { it.length >= MIN_RECAP_CHARS } }
    }

    /** Conseil de lecture d'après le relevé [report] du lecteur ; mis en cache tant que le relevé ne change pas. */
    suspend fun playbackAdvice(report: String): String? {
        val cfg = config ?: return null
        return askParsed(
            cfg, AiFeature.Advice,
            cacheKey = "a|${cfg.language}|${report.hashCode()}",
            system = adviceSystemPrompt(languageLabel(cfg)),
            user = report.take(1_500),
            maxTokens = 500,
        ) { answer -> answer.trim().takeIf { it.length >= MIN_ADVICE_CHARS } }
    }

    /** Identifiant (de [lines]) du match qui correspond à [query] — surnoms et abréviations compris (« PSG ») ; null si aucun. */
    suspend fun resolveMatch(query: String, lines: List<AiCandidate>): String? {
        val cfg = config ?: return null
        if (lines.isEmpty()) return null
        val ids = lines.mapTo(HashSet(), AiCandidate::id)
        return askParsed(
            cfg, AiFeature.Remote,
            cacheKey = "m|${today()}|${normalizeForMatch(query)}|${lines.joinToString("|") { it.label }.hashCode()}",
            system = matchSystemPrompt(lines),
            user = query.trim().take(120),
            maxTokens = 60,
        ) { answer -> extractJsonObject(answer)?.optString("id")?.trim()?.takeIf { it in ids } }
    }

    /** Ce que veut dire un message de la télécommande du téléphone ; jamais mis en cache (c'est une conversation). */
    suspend fun interpretRemote(message: String): RemoteIntent? {
        val cfg = config ?: return null
        return askParsed(
            cfg, AiFeature.Remote, cacheKey = null,
            system = remoteSystemPrompt(languageLabel(cfg)),
            user = message.trim().take(300),
            maxTokens = 300,
        ) { parseRemoteIntent(it) }
    }

    /**
     * Traduit en peu de requêtes (lots de [PLOT_BATCH]) les descriptions de [plots] pas encore en cache, et les garde
     * sous la clé qu'utilisent les fiches : la fiche s'ouvre ensuite sans aucun appel. Retourne le nombre traduit ;
     * s'arrête dès que l'assistant est coupé.
     */
    suspend fun precomputePlots(plots: List<String>): Int {
        val cfg = config ?: return 0
        val todo = LinkedHashMap<String, String>()
        for (plot in plots) {
            val text = plotToTranslate(cfg, plot) ?: continue
            val key = plotCacheKey(cfg, text)
            if (key !in todo && cached(key) == null) todo[key] = text.take(MAX_PLOT_CHARS)
        }
        var done = 0
        for (batch in todo.entries.chunked(PLOT_BATCH)) {
            if (config !== cfg) break
            val answer = chat(
                cfg, AiFeature.Nightly,
                system = "Tu es un traducteur. Chaque ligne est « numéro|texte ». Traduis chaque texte en ${AiLanguages.name(cfg.language)}. " +
                    "Réponds uniquement par les mêmes lignes « numéro|traduction », une par ligne, sans rien ajouter.",
                user = batch.mapIndexed { index, (_, text) -> "${index + 1}|${text.replace(Regex("\\s+"), " ")}" }.joinToString("\n"),
                maxTokens = batch.sumOf { it.value.length } / 2 + 300,
            ) ?: break
            val translated = parseTranslatedLines(answer)
            val entries = HashMap<String, String>()
            batch.forEachIndexed { index, (key, _) -> translated[index + 1]?.joinToString(" ")?.takeIf(String::isNotBlank)?.let { entries[key] = it } }
            if (entries.isNotEmpty() && config === cfg) withContext(Dispatchers.IO) { cache.putAll(entries) }
            done += entries.size
        }
        return done
    }

    /** Description déjà traduite en cache (ou null) : permet d'afficher une fiche sans aucun appel. */
    suspend fun cachedPlotTranslation(plot: String): String? {
        val cfg = config ?: return null
        val text = plotToTranslate(cfg, plot) ?: return null
        return cached(plotCacheKey(cfg, text))
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
     *
     * [bilingual] : chaque sous-titre garde son original en italique sous la traduction (sans requête de plus : l'original
     * est déjà là). [glossaryKey] : identité de la série ou du film ; ses noms propres sont traduits une fois (le premier lot
     * les relève) puis imposés aux lots et aux épisodes suivants, pour une traduction cohérente.
     */
    suspend fun translateSubtitle(
        text: String,
        vtt: Boolean,
        sourceLanguage: String,
        bilingual: Boolean = false,
        glossaryKey: String? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String? {
        val cfg = config ?: return null
        if (sourceLanguage == cfg.language) return null
        val cues = parseCues(text)
        if (cues.isEmpty()) return null
        val key = "${text.length}-${text.hashCode().toUInt()}-${cfg.language}-${if (vtt) "vtt" else "srt"}${if (bilingual) "-bi" else ""}"
        val store = withContext(Dispatchers.IO) { AiSubtitleCache(File(appContext.cacheDir, "ai-subtitles")) }
        withContext(Dispatchers.IO) { store.full(key).takeIf(File::exists)?.readText() }?.let { return it }
        val glossaries = glossaryKey?.let { withContext(Dispatchers.IO) { glossaryStore } }
        var glossary = if (glossaryKey != null && glossaries != null) withContext(Dispatchers.IO) { glossaries.load(glossaryKey) } else emptyMap()
        val batches = packCues(cues)
        val translated = HashMap<Int, List<String>>()
        val session = newSession()
        for ((position, batch) in batches.withIndex()) {
            if (config !== cfg) return null
            onProgress(position, batches.size)
            val saved = withContext(Dispatchers.IO) { store.batch(key, position).takeIf(File::exists)?.readText() }
            // Le premier lot relève les noms propres d'une série dont le glossaire est encore mince.
            val collectNames = glossaryKey != null && position == 0 && glossary.size < GLOSSARY_COLLECT_BELOW
            val answer = saved ?: chat(
                cfg,
                AiFeature.Subtitles,
                system = "Tu traduis des sous-titres de ${AiLanguages.name(sourceLanguage)} vers ${AiLanguages.name(cfg.language)}. " +
                    "Chaque ligne est « numéro|texte » ; « // » sépare deux lignes d'un même sous-titre. " +
                    "Traduis de façon naturelle et concise, garde les balises (<i>…), la ponctuation et les « // ». " +
                    "Réponds uniquement par les mêmes lignes « numéro|traduction », sans rien ajouter." +
                    glossaryPrompt(glossary) +
                    if (collectNames) " Après les lignes, ajoute une dernière ligne « $GLOSSARY_MARKER Nom=Traduction; Autre=Traduction » avec au plus 8 noms propres (personnages, lieux) rencontrés, seulement s'il y en a." else "",
                user = batch.joinToString("\n") { (number, line) -> "$number|$line" },
                maxTokens = batch.sumOf { it.second.length } / 2 + 300 + if (collectNames) 150 else 0,
                session = session,
            )?.also { reply -> withContext(Dispatchers.IO) { store.batch(key, position).writeText(reply) } } ?: return null
            if (glossaryKey != null && glossaries != null) {
                val found = parseGlossary(answer)
                if (found.isNotEmpty()) glossary = withContext(Dispatchers.IO) { glossaries.merge(glossaryKey, found) }
            }
            val lines = parseTranslatedLines(answer)
            // Plus de 30 % de lignes manquantes : réponse inexploitable, on abandonne (les lots déjà payés restent).
            if (batch.count { it.first !in lines } * 10 > batch.size * 3) return null
            translated += lines
        }
        onProgress(batches.size, batches.size)
        val result = renderCues(
            cues.mapIndexed { index, cue ->
                val lines = translated[index + 1]
                SubtitleCue(cue.timing, if (lines == null) cue.lines else if (bilingual) bilingualLines(lines, cue.lines) else lines)
            },
            vtt,
        )
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
     * Fonctions IA d'une fiche en **une seule requête** : description traduite, similaires classés et avis rapide
     * ([info] donne la matière de l'avis ; sans elle, pas d'avis). Ce qui est déjà en cache n'est pas redemandé ;
     * s'il ne reste qu'une tâche de traduction ou de classement, elle part seule avec son invite dédiée.
     */
    suspend fun enrichFiche(source: MediaEntry, plot: String?, candidates: List<MediaEntry>, info: FicheInfo? = null): FicheAi {
        val cfg = config ?: return FicheAi(null, null, null)
        val text = plot?.let { plotToTranslate(cfg, it) }
        val plotKey = text?.let { plotCacheKey(cfg, it) }
        val items = rankable(candidates)
        val rankKey = items?.let { rankCacheKey(source, it) }
        val reviewInfo = info?.takeIf(FicheInfo::hasMaterial)
        val reviewKey = reviewInfo?.let { reviewCacheKey(cfg, source, it) }
        val cachedPlot = plotKey?.let { cached(it) }
        val cachedKeys = rankKey?.let { key -> cached(key)?.split(',')?.filter(String::isNotEmpty) }
        val cachedReview = reviewKey?.let { key -> cached(key)?.let(::parseReview) }
        val needPlot = text != null && cachedPlot == null
        val needRank = items != null && cachedKeys == null
        val needReview = reviewInfo != null && cachedReview == null
        val needed = listOf(needPlot, needRank, needReview).count { it }
        if (needed == 0) return FicheAi(cachedPlot, cachedKeys, cachedReview)
        if (needed == 1 && !needReview) {
            return FicheAi(
                plot = cachedPlot ?: if (needPlot && plot != null) translatePlot(plot) else null,
                similarKeys = cachedKeys ?: if (needRank) rerankSimilar(source, candidates) else null,
                review = cachedReview,
            )
        }
        val language = AiLanguages.name(cfg.language)
        val sections = buildList {
            if (needRank) add("ORDRE: [numéros du plus proche au moins proche, chacun une fois] — classe les candidats par proximité (genre, ambiance, public) avec la référence.")
            if (needReview) add("AVIS: {\"audience\":\"…\",\"mood\":\"…\",\"caution\":\"…\"} — avis rapide en $language, 14 mots maximum par champ : public visé, ambiance, et ce qui peut choquer (violence, langage, scènes sensibles) seulement si les informations le suggèrent, sinon dis qu'aucun point sensible n'est signalé. N'invente rien.")
            if (needPlot) add("TRADUCTION:\n<la traduction> — traduis la description en $language.")
        }
        val answer = chat(
            cfg,
            AiFeature.Fiche,
            system = "Tu fais ${if (sections.size > 1) "plusieurs tâches" else "une tâche"} pour la fiche d'un film ou d'une série. " +
                "Réponds exactement sous cette forme, sans rien d'autre, avec uniquement ces sections et dans cet ordre :\n" + sections.joinToString("\n"),
            user = buildString {
                append(reviewInfo?.let { if (needPlot) it.copy(plot = null) else it }?.asPrompt(source.displayName) ?: "Référence : ${source.displayName}")
                if (needRank) {
                    append("\nCandidats :\n")
                    append(items!!.mapIndexed { index, entry -> "${index + 1}. ${entry.displayName}" }.joinToString("\n"))
                }
                if (needPlot) append("\n\nDescription (à traduire, et base de l'avis) :\n").append(text!!.take(MAX_PLOT_CHARS))
            },
            maxTokens = (if (needPlot) 1_500 else 0) + (if (needRank) 150 else 0) + (if (needReview) 250 else 0),
        ) ?: return FicheAi(cachedPlot, cachedKeys, cachedReview)
        val sectionsRead = parseFicheSections(answer, items?.size ?: 0)
        val keys = sectionsRead.order?.takeIf { needRank }?.let { indexes -> indexes.map { items!![it].key } + candidates.drop(items!!.size).map(MediaEntry::key) }
        val translated = sectionsRead.translation?.takeIf { needPlot }
        val review = sectionsRead.review?.takeIf { needReview }
        val toStore = HashMap<String, String>()
        translated?.let { toStore[plotKey!!] = it }
        keys?.let { toStore[rankKey!!] = it.joinToString(",") }
        review?.let { toStore[reviewKey!!] = it.encode() }
        if (toStore.isNotEmpty() && config === cfg) withContext(Dispatchers.IO) { cache.putAll(toStore) }
        return FicheAi(translated ?: cachedPlot, keys ?: cachedKeys, review ?: cachedReview)
    }

    private fun reviewCacheKey(cfg: Config, source: MediaEntry, info: FicheInfo) =
        "v|${cfg.language}|${source.key}|${info.plot?.length ?: 0}|${info.genre.orEmpty().hashCode()}"

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
        const val MIN_NATURAL_QUERY_CHARS = 4
        const val MIN_POOL = 3
        const val MIN_RECAP_CHARS = 40
        const val MIN_ADVICE_CHARS = 20
        const val BRIEF_TTL_MS = 10 * 60_000L
        const val PLOT_BATCH = 8
        const val GLOSSARY_COLLECT_BELOW = 25
    }
}

/** Ce que l'IA apporte à une fiche : description traduite, ordre des similaires et avis rapide (null = inutile ou indisponible). */
class FicheAi(val plot: String?, val similarKeys: List<String>?, val review: AiReview? = null)

/** Sections lues dans la réponse combinée d'une fiche. */
internal class FicheSections(val order: List<Int>?, val review: AiReview?, val translation: String?)

private val FICHE_MARKER = Regex("^\\W*(ORDRE|AVIS|TRADUCTION)\\s*:", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

/**
 * Réponse combinée « ORDRE: […] / AVIS: {…} / TRADUCTION: … » découpée par marqueur, dans l'ordre où le modèle les a
 * écrits : une section va jusqu'au marqueur suivant. Chacune est null si elle manque ou si elle est illisible.
 */
internal fun parseFicheSections(answer: String, size: Int): FicheSections {
    val markers = FICHE_MARKER.findAll(answer).toList()
    fun section(name: String): String? {
        val index = markers.indexOfFirst { it.groupValues[1].equals(name, ignoreCase = true) }
        if (index < 0) return null
        val end = markers.getOrNull(index + 1)?.range?.first ?: answer.length
        return answer.substring(markers[index].range.last + 1, end)
    }
    return FicheSections(
        order = section("ORDRE")?.let { parseRanking(it, size) },
        review = section("AVIS")?.let(::parseReview),
        translation = section("TRADUCTION")?.trim { it.isWhitespace() || it in "\"«»*" }?.takeIf(String::isNotEmpty),
    )
}

/** Réponse combinée « ORDRE: […] / TRADUCTION: … » : ordre (indices) et traduction, chacun null s'il manque. */
internal fun parseFicheAnswer(answer: String, size: Int): Pair<List<Int>?, String?> =
    parseFicheSections(answer, size).let { it.order to it.translation }

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

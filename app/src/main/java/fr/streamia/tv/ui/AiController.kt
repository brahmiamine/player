package fr.streamia.tv.ui

import fr.streamia.tv.data.AiCandidate
import fr.streamia.tv.data.AiGate
import fr.streamia.tv.data.AiPoolBuilder
import fr.streamia.tv.data.AiSearchPlan
import fr.streamia.tv.data.AiSearchResult
import fr.streamia.tv.data.RECAP_AUTO_GAP_MS
import fr.streamia.tv.data.RemoteAction
import fr.streamia.tv.data.isResumable
import fr.streamia.tv.data.normalizeForMatch
import fr.streamia.tv.data.PhoneChatServer
import fr.streamia.tv.data.TonightAnswers
import fr.streamia.tv.data.recapInputOf
import fr.streamia.tv.data.watchedEpisodesOf
import fr.streamia.tv.domain.SeriesDetails
import fr.streamia.tv.data.allowedEntries
import fr.streamia.tv.data.filterByYears
import fr.streamia.tv.data.matchingCategories
import fr.streamia.tv.data.mergeSearchResults
import fr.streamia.tv.data.pickTitleMatches
import fr.streamia.tv.data.poolOf
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch
import fr.streamia.tv.recommendation.RecommendationRowKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Fonctions de l'assistant IA affichées sur leurs propres écrans : recherche en langage naturel, « Ce soir ? »,
 * « Quoi de neuf maintenant ? » et raisons des recommandations de l'accueil.
 *
 * Règles communes :
 *  - **assistant coupé = tout s'arrête** : les travaux en cours sont annulés, l'état est remis à zéro, aucune
 *    requête ne part (chaque appel de [fr.streamia.tv.data.AiAssistant] se garde lui-même) ;
 *  - **une requête par action**, déclenchée par l'utilisateur (jamais à la frappe), précédée d'un calcul local qui
 *    prépare une liste courte de candidats réels ;
 *  - un titre n'est montré que s'il existe dans la playlist, et jamais un contenu masqué ou verrouillé.
 */
internal class AiController(
    host: StreamiaStateHolder,
    /** Ouvre une chaîne en direct (appelé sur le thread principal). */
    private val openLive: (MediaEntry) -> Unit = {},
    /** Reprend un film ou un épisode à sa position. */
    private val resumeEntry: (MediaEntry) -> Unit = {},
    /** Ouvre la recherche avec ce texte. */
    private val openSearch: (String) -> Unit = {},
) : StreamiaController(host) {
    val state = MutableStateFlow(AiUiState())

    private val pools by lazy { AiPoolBuilder(repository) }
    private val ai get() = repository.ai

    private var searchJob: Job? = null
    private var tonightJob: Job? = null
    private var briefJob: Job? = null
    private var remoteServer: PhoneChatServer? = null
    private val remoteStarting = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        viewModelScope.launch {
            AiGate.active.collect { active -> if (!active) reset() }
        }
        // Raisons des recommandations : une seule requête, un instant après le calcul des rangées de l'accueil.
        viewModelScope.launch {
            combine(AiGate.active, _homeState.map { it.homeRecommendationRows }.distinctUntilChanged()) { active, rows -> active to rows }
                .collectLatest { (active, rows) ->
                    if (!active || rows.isEmpty()) return@collectLatest
                    delay(EXPLAIN_DELAY_MS)
                    explainRecommendations(rows.filter { it.kind in EXPLAINED_KINDS }.flatMap { row -> row.items.map { it.entry } })
                }
        }
    }

    /** Assistant coupé (ou changement de liste) : plus rien en cours, plus rien d'affiché. */
    fun reset() {
        searchJob?.cancel()
        tonightJob?.cancel()
        briefJob?.cancel()
        recapJob?.cancel()
        stopRemote()
        state.value = AiUiState()
        // Fiche ouverte : ce que l'assistant y avait mis disparaît avec lui.
        _uiState.update {
            if (it.aiPlot == null && it.aiSimilarKeys == null && it.aiReview == null && it.aiRecap == null && !it.aiRecapAvailable &&
                !it.aiPlotLoading && !it.aiSimilarLoading && !it.aiReviewLoading && !it.aiRecapLoading
            ) {
                it
            } else {
                it.copy(
                    aiPlot = null, aiSimilarKeys = null, aiReview = null, aiRecap = null, aiRecapError = null, aiRecapAvailable = false,
                    aiPlotLoading = false, aiSimilarLoading = false, aiReviewLoading = false, aiRecapLoading = false,
                )
            }
        }
    }

    // ---------- Recherche en langage naturel ----------

    /** Comprend [query] (« un film d'action des années 90 ») et cherche dans la playlist. Une requête au modèle, résultat gardé. */
    fun searchWithAi(query: String, type: MediaType?) {
        val text = query.trim()
        if (!AiGate.active.value || text.length < MIN_QUERY_CHARS) return
        searchJob?.cancel()
        state.update { it.copy(search = AiSearchUiState(query = text, type = type, loading = true)) }
        searchJob = viewModelScope.launch {
            val outcome = runCatching { naturalSearch(text, type) }
            if (outcome.exceptionOrNull() is CancellationException || !AiGate.active.value) return@launch
            state.update {
                it.copy(
                    search = outcome.getOrNull()?.let { result -> AiSearchUiState(query = text, type = type, result = result) }
                        ?: AiSearchUiState(query = text, type = type, error = "L'assistant n'a pas compris cette demande. Reformulez-la ou précisez le genre, la langue ou l'époque."),
                )
            }
        }
    }

    fun clearAiSearch() {
        searchJob?.cancel()
        state.update { if (it.search == AiSearchUiState()) it else it.copy(search = AiSearchUiState()) }
    }

    private suspend fun naturalSearch(query: String, type: MediaType?): AiSearchResult? {
        val snapshot = _uiState.value
        val profileId = snapshot.activeProfileId ?: return null
        val plan = ai.interpretSearch(query, type) ?: return null
        if (!AiGate.active.value) return null
        val categories = snapshot.catalog?.categories.orEmpty()
        val allowed = allowedEntries(categories, snapshot.library, snapshot.appSettings.parentalControlEnabled, snapshot.parentalUnlocked)
        val entries = withContext(Dispatchers.IO) { resolvePlan(profileId, plan, categories, allowed) }
        return AiSearchResult(query, plan, entries)
    }

    private suspend fun resolvePlan(
        profileId: String,
        plan: AiSearchPlan,
        categories: List<MediaCategory>,
        allowed: (MediaEntry) -> Boolean,
    ): List<MediaEntry> {
        // Titres proposés par le modèle : montrés seulement s'ils existent vraiment dans la playlist.
        val suggested = ArrayList<MediaEntry>()
        for (title in plan.titles.take(MAX_SUGGESTED_LOOKUPS)) {
            val hits = runCatching { repository.search(profileId, title, plan.type, SUGGESTION_LOOKUP_LIMIT) }.getOrDefault(emptyList())
            suggested += pickTitleMatches(title, hits.filter { it.type != MediaType.Live || plan.type == MediaType.Live })
        }
        val matched = matchingCategories(plan, categories)
        val matchedByType = matched.groupBy(MediaCategory::type).mapValues { (_, list) -> list.mapTo(HashSet(), MediaCategory::id) }
        val pool: List<MediaEntry> = when {
            plan.keywords.isNotEmpty() -> {
                val hits = runCatching { repository.search(profileId, plan.keywords.joinToString(" "), plan.type, KEYWORD_LIMIT) }.getOrDefault(emptyList())
                val inCategories = hits.filter { it.categoryId in matchedByType[it.type].orEmpty() }
                inCategories.ifEmpty { hits }
            }
            matched.isNotEmpty() ->
                runCatching { repository.searchInCategories(profileId, matchedByType, plan.sort, POOL_LIMIT) }.getOrDefault(emptyList())
            // Ni genre ni région reconnus dans les catégories, mais une époque : tout le type, les mieux notés d'abord.
            plan.hasYears && plan.titles.isEmpty() -> {
                val types = plan.type?.let(::listOf) ?: listOf(MediaType.Movie, MediaType.Series)
                runCatching { repository.searchInCategories(profileId, types.associateWith { emptySet<String>() }, plan.sort, POOL_LIMIT) }
                    .getOrDefault(emptyList())
            }
            else -> emptyList()
        }
        val checkedSuggestions = filterByYears(suggested.filter(allowed), plan.yearFrom, plan.yearTo, enoughKnown = Int.MAX_VALUE)
        val ranked = filterByYears(pool.filter(allowed), plan.yearFrom, plan.yearTo)
        return mergeSearchResults(checkedSuggestions, ranked, MAX_RESULTS)
    }

    // ---------- Ce soir ? ----------

    /** Cinq propositions pour ce soir d'après les trois réponses : humeur, durée, compagnie. */
    fun startTonight(answers: TonightAnswers) {
        if (!AiGate.active.value) return
        tonightJob?.cancel()
        state.update { it.copy(tonight = TonightUiState(loading = true)) }
        tonightJob = viewModelScope.launch {
            val outcome = runCatching { computeTonight(answers) }
            if (outcome.exceptionOrNull() is CancellationException || !AiGate.active.value) return@launch
            val picks = outcome.getOrNull().orEmpty()
            state.update {
                it.copy(
                    tonight = if (picks.isEmpty()) {
                        TonightUiState(error = "Pas de proposition pour l'instant. Vérifiez la connexion à l'assistant et réessayez.")
                    } else {
                        TonightUiState(picks = picks)
                    },
                )
            }
        }
    }

    fun resetTonight() {
        tonightJob?.cancel()
        state.update { if (it.tonight == TonightUiState()) it else it.copy(tonight = TonightUiState()) }
    }

    private suspend fun computeTonight(answers: TonightAnswers): List<TonightPick> {
        val snapshot = _uiState.value
        val profileId = snapshot.activeProfileId ?: return emptyList()
        val categories = snapshot.catalog?.categories.orEmpty()
        val library = snapshot.library
        val allowed = allowedEntries(categories, library, snapshot.appSettings.parentalControlEnabled, snapshot.parentalUnlocked)
        val recommended = _homeState.value.homeRecommendationRows.flatMap { row -> row.items.map { it.entry } }.distinctBy(MediaEntry::key)
        val media = pools.tonightPool(profileId, answers, categories, library, recommended, allowed)
        // Programmes TV de ce soir, rapprochés des chaînes de la playlist.
        val tonightTv = _homeState.value.homeTvProgrammeTonight.filter { allowed(it.channel) }.take(MAX_TV_IN_POOL)
        val tvCandidates = tonightTv.mapIndexed { index, item ->
            AiCandidate("T${index + 1}", "${item.programme.time} ${item.programme.channelName} : ${item.programme.title}")
        }
        val candidates = media.candidates + tvCandidates
        val tastes = pools.tastes(profileId, library, allowed)
        val picks = ai.tonightPicks(candidates, answers, tastes) ?: return emptyList()
        return picks.mapNotNull { pick ->
            media.entries[pick.id]?.let { entry -> TonightPick(entry, pick.why, entryDetail(entry)) }
                ?: pick.id.removePrefix("T").toIntOrNull()?.let { tonightTv.getOrNull(it - 1) }?.let { item ->
                    TonightPick(item.channel, pick.why, "${item.programme.time} · ${item.programme.channelName}")
                }
        }
    }

    // ---------- Quoi de neuf maintenant ? ----------

    /** Résumé de ce qui passe maintenant, d'après les matchs et les guides déjà chargés sur l'accueil (aucune donnée de plus à télécharger). */
    fun loadBrief(force: Boolean = false) {
        if (!AiGate.active.value) return
        val current = state.value.brief
        if (current.loading) return
        if (!force && current.loaded && System.currentTimeMillis() - briefAtMillis < BRIEF_REUSE_MS) return
        briefJob?.cancel()
        state.update { it.copy(brief = it.brief.copy(loading = true, error = null)) }
        briefJob = viewModelScope.launch {
            val outcome = runCatching { computeBrief() }
            if (outcome.exceptionOrNull() is CancellationException || !AiGate.active.value) return@launch
            val brief = outcome.getOrNull()
            // Rien à résumer (guides pas encore chargés) : pas de délai de réutilisation, le prochain appel réessaie sans coût.
            briefAtMillis = if (brief != null) System.currentTimeMillis() else 0L
            state.update {
                it.copy(
                    brief = when {
                        brief == null -> BriefUiState(loaded = true, error = "Pas de résumé pour l'instant : aucun match ni programme en direct n'est chargé, ou l'assistant est injoignable.")
                        else -> brief
                    },
                )
            }
        }
    }

    private var briefAtMillis = 0L

    private suspend fun computeBrief(): BriefUiState? {
        val home = _homeState.value
        val snapshot = _uiState.value
        val allowed = allowedEntries(snapshot.catalog?.categories.orEmpty(), snapshot.library, snapshot.appSettings.parentalControlEnabled, snapshot.parentalUnlocked)
        val nowSeconds = System.currentTimeMillis() / 1_000
        val targets = LinkedHashMap<String, MediaEntry?>()
        val lines = ArrayList<AiCandidate>()
        fun add(prefix: String, text: String, channel: MediaEntry?) {
            val id = prefix + (lines.count { it.id.startsWith(prefix) } + 1)
            lines += AiCandidate(id, text)
            targets[id] = channel
        }
        home.liveOnSatMatches
            .filter { it.match.startEpochSeconds in (nowSeconds - MATCH_PAST_SECONDS)..(nowSeconds + MATCH_AHEAD_SECONDS) }
            .sortedBy { it.match.startEpochSeconds }
            .mapNotNull { resolved -> matchLine(resolved, nowSeconds, allowed)?.let { resolved to it } }
            .take(MAX_MATCH_LINES)
            .forEach { (resolved, text) -> add("M", text, firstChannel(resolved, allowed)) }
        home.homeBeinSportsNow.filter { allowed(it.channel) }.take(MAX_PROGRAMME_LINES).forEach {
            add("B", "${it.programme.channelName} : ${it.programme.title} (${it.programme.timeRangeLabel})", it.channel)
        }
        home.homeTvProgrammeNow.filter { allowed(it.channel) }.take(MAX_PROGRAMME_LINES).forEach {
            add("T", "${it.programme.channelName} : ${it.programme.title} (${it.programme.timeRangeLabel})", it.channel)
        }
        if (lines.isEmpty()) return null
        val brief = ai.whatsNew(lines, CLOCK.format(Instant.now().atZone(ZoneId.systemDefault()))) ?: return null
        return BriefUiState(
            loaded = true,
            headline = brief.headline.takeIf(String::isNotBlank),
            items = brief.items.map { BriefItemUi(it.text, targets[it.ref]) },
        )
    }

    private fun matchLine(resolved: ResolvedLiveOnSatMatch, nowSeconds: Long, allowed: (MediaEntry) -> Boolean): String? {
        val channel = firstChannel(resolved, allowed) ?: return null
        val match = resolved.match
        val start = CLOCK.format(Instant.ofEpochSecond(match.startEpochSeconds).atZone(ZoneId.systemDefault()))
        val status = if (match.startEpochSeconds <= nowSeconds) "commencé à $start" else "à $start"
        return "${match.competition} : ${match.participantA} - ${match.participantB}, $status, sur ${channel.displayName}"
    }

    private fun firstChannel(resolved: ResolvedLiveOnSatMatch, allowed: (MediaEntry) -> Boolean): MediaEntry? =
        resolved.matchedChannels.values.asSequence().flatten().firstOrNull { it.type == MediaType.Live && allowed(it) }

    // ---------- Télécommande téléphone ----------

    /**
     * Démarre (une seule fois) la page de chat du téléphone : elle vit tant que l'assistant est actif, quel que soit l'écran,
     * pour que le QR code de l'accueil reste valable. Sans réseau local, l'adresse reste vide et un prochain appel réessaie.
     */
    fun startRemote(logo: ByteArray?) {
        if (!AiGate.active.value || remoteServer != null || !remoteStarting.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val server = PhoneChatServer(logo) { text -> kotlinx.coroutines.runBlocking { handleRemote(text) } }
                val url = runCatching { server.start() }.getOrNull()
                if (url == null || !AiGate.active.value) {
                    server.close()
                    return@launch
                }
                remoteServer = server
                state.update { it.copy(remote = it.remote.copy(url = url)) }
            } finally {
                remoteStarting.set(false)
            }
        }
    }

    private fun stopRemote() {
        remoteServer?.close()
        remoteServer = null
    }

    /**
     * Message écrit depuis le téléphone (voir [fr.streamia.tv.data.PhoneChatServer]) : une requête au modèle pour comprendre
     * ce que veut l'utilisateur, puis la TV exécute l'action avec ses données locales. Retourne la réponse pour le téléphone.
     */
    suspend fun handleRemote(message: String): String {
        if (!AiGate.active.value) return "L'assistant IA est désactivé sur la TV."
        state.update { it.copy(remote = it.remote.copy(busy = true)) }
        val reply = try {
            runCatching { executeRemote(message) }.getOrElse { "Je n'ai pas pu traiter ce message." }
        } finally {
            state.update { it.copy(remote = it.remote.copy(busy = false)) }
        }
        if (!AiGate.active.value) return "L'assistant IA a été désactivé sur la TV."
        state.update { it.copy(remote = it.remote.copy(busy = false, log = (it.remote.log + RemoteExchange(message, reply)).takeLast(MAX_REMOTE_LOG))) }
        return reply
    }

    private suspend fun executeRemote(message: String): String {
        val intent = ai.interpretRemote(message)
            ?: return "Je n'ai pas compris. Essayez « mets beIN Sports 1 », « reprends ma série » ou « trouve le match du PSG »."
        val snapshot = _uiState.value
        val profileId = snapshot.activeProfileId ?: return "Aucune liste n'est ouverte sur la TV."
        val allowed = allowedEntries(snapshot.catalog?.categories.orEmpty(), snapshot.library, snapshot.appSettings.parentalControlEnabled, snapshot.parentalUnlocked)
        suspend fun onMain(action: () -> Unit) = withContext(Dispatchers.Main.immediate) { action() }
        return when (intent.action) {
            RemoteAction.Say -> intent.reply.ifBlank { "Je peux ouvrir une chaîne, reprendre une lecture, chercher un contenu ou trouver un match." }
            RemoteAction.WatchChannel -> {
                val channel = bestChannel(profileId, intent.query, allowed) ?: return "Je n'ai pas trouvé la chaîne « ${intent.query} »."
                onMain { openLive(channel) }
                "J'ouvre ${channel.displayName}."
            }
            RemoteAction.Resume -> {
                val wanted = normalizeForMatch(intent.query)
                val item = snapshot.library.history
                    .filter { it.entry.type != MediaType.Live && it.isResumable() && allowed(it.entry) }
                    .filter { wanted.isEmpty() || wanted in normalizeForMatch(it.entry.displayName) }
                    .maxByOrNull { it.updatedAt }
                    ?: return "Aucune lecture à reprendre."
                onMain { resumeEntry(item.entry) }
                "Je reprends ${item.entry.displayName}."
            }
            RemoteAction.Search -> {
                if (snapshot.screen is StreamiaScreen.Player) return "Arrêtez d'abord la lecture sur la TV pour ouvrir la recherche."
                val natural = intent.query.trim().split(' ').size >= NATURAL_SEARCH_MIN_WORDS
                val result = if (natural) runCatching { naturalSearch(intent.query, null) }.getOrNull() else null
                val entries = result?.entries
                    ?: withContext(Dispatchers.IO) { runCatching { repository.search(profileId, intent.query, null, REMOTE_SEARCH_LIMIT) }.getOrDefault(emptyList()) }.filter(allowed)
                if (result != null) state.update { it.copy(search = AiSearchUiState(query = intent.query.trim(), result = result)) }
                onMain { openSearch(intent.query) }
                if (entries.isEmpty()) "Aucun résultat pour « ${intent.query} »."
                else "${entries.size} résultat${if (entries.size > 1) "s" else ""} affiché${if (entries.size > 1) "s" else ""} sur la TV : " + entries.take(REMOTE_TITLES_IN_REPLY).joinToString(", ") { it.displayName }
            }
            RemoteAction.Match -> remoteMatch(intent.query, allowed) { channel -> onMain { openLive(channel) } }
        }
    }

    private suspend fun bestChannel(profileId: String, query: String, allowed: (MediaEntry) -> Boolean): MediaEntry? {
        val needle = normalizeForMatch(query)
        if (needle.isEmpty()) return null
        val hits = withContext(Dispatchers.IO) { runCatching { repository.search(profileId, query, MediaType.Live, REMOTE_CHANNEL_LOOKUP) }.getOrDefault(emptyList()) }
            .filter { it.type == MediaType.Live && allowed(it) }
        fun rank(entry: MediaEntry): Int {
            val name = normalizeForMatch(entry.displayName)
            return when {
                name == needle -> 0
                name.startsWith("$needle ") -> 1
                " $name ".contains(" $needle ") -> 2
                name.contains(needle) -> 3
                else -> 4
            }
        }
        // Le fournisseur range ses chaînes par numéro : à rang égal, la première (souvent la version principale) gagne.
        return hits.withIndex().minWithOrNull(compareBy({ rank(it.value) }, { it.index }))?.value?.takeIf { rank(it) < 4 }
    }

    private suspend fun remoteMatch(query: String, allowed: (MediaEntry) -> Boolean, open: suspend (MediaEntry) -> Unit): String {
        val nowSeconds = System.currentTimeMillis() / 1_000
        val today = _homeState.value.liveOnSatMatches
            .filter { it.match.startEpochSeconds in (nowSeconds - MATCH_PAST_SECONDS)..(nowSeconds + REMOTE_MATCH_AHEAD_SECONDS) }
            .filter { firstChannel(it, allowed) != null }
            .sortedBy { it.match.startEpochSeconds }
            .take(MAX_REMOTE_MATCHES)
        if (today.isEmpty()) return "Aucun match avec une chaîne de votre liste n'est annoncé pour le moment."
        val tokens = normalizeForMatch(query).split(' ').filter { it.length >= 2 }
        fun text(m: ResolvedLiveOnSatMatch) = normalizeForMatch("${m.match.competition} ${m.match.participantA} ${m.match.participantB}")
        var found = today.firstOrNull { m -> tokens.isNotEmpty() && tokens.all { it in text(m) } }
        if (found == null) {
            // Surnoms et abréviations (« PSG ») : le modèle choisit parmi les matchs du jour, sans rien inventer.
            val lines = today.mapIndexed { index, m -> AiCandidate("M${index + 1}", "${m.match.competition} : ${m.match.participantA} - ${m.match.participantB}") }
            val id = ai.resolveMatch(query, lines)
            found = id?.removePrefix("M")?.toIntOrNull()?.let { today.getOrNull(it - 1) }
        }
        val match = found?.match ?: return "Je ne trouve pas de match « $query » dans la liste du jour."
        val channel = firstChannel(found, allowed) ?: return "Ce match n'a pas de chaîne dans votre liste."
        val label = "${match.participantA} - ${match.participantB}"
        val start = CLOCK.format(Instant.ofEpochSecond(match.startEpochSeconds).atZone(ZoneId.systemDefault()))
        if (match.startEpochSeconds > nowSeconds + REMOTE_OPEN_BEFORE_SECONDS) {
            return "$label commence à $start sur ${channel.displayName}. Je ne lance pas la chaîne maintenant."
        }
        open(channel)
        return "$label : j'ouvre ${channel.displayName}."
    }

    // ---------- Précédemment dans… ----------

    private var recapJob: Job? = null

    /**
     * Série ouverte : le « Précédemment dans… » devient disponible si des épisodes ont été vus et qu'on a de quoi les
     * résumer (sinon le modèle ne pourrait qu'inventer). Il se prépare seul après une longue absence, sinon sur demande.
     */
    fun prepareRecap(series: MediaEntry, details: SeriesDetails?) {
        if (!AiGate.active.value || details == null) return
        val watched = watchedEpisodesOf(details, _uiState.value.library) ?: return
        if (!recapInputOf(details, watched).hasMaterial) return
        _uiState.update { if (isSeriesScreen(it, series)) it.copy(aiRecapAvailable = true) else it }
        val absentMillis = System.currentTimeMillis() - watched.lastWatchedAtMillis
        if (watched.lastWatchedAtMillis == 0L || absentMillis >= RECAP_AUTO_GAP_MS) loadRecap()
    }

    /** Demande (ou relit du cache) le résumé de la série ouverte. */
    fun loadRecap() {
        if (!AiGate.active.value) return
        val snapshot = _uiState.value
        val screen = snapshot.screen as? StreamiaScreen.Series ?: return
        val details = snapshot.seriesDetails ?: return
        val watched = watchedEpisodesOf(details, snapshot.library) ?: return
        val input = recapInputOf(details, watched)
        if (!input.hasMaterial || snapshot.aiRecapLoading) return
        recapJob?.cancel()
        _uiState.update { if (isSeriesScreen(it, screen.series)) it.copy(aiRecapLoading = true, aiRecapError = null) else it }
        recapJob = viewModelScope.launch {
            val text = runCatching { ai.recap(input) }.getOrNull()
            _uiState.update {
                if (!isSeriesScreen(it, screen.series)) {
                    it
                } else if (text != null && AiGate.active.value) {
                    it.copy(aiRecap = text, aiRecapLoading = false)
                } else {
                    it.copy(aiRecapLoading = false, aiRecapError = "Résumé indisponible pour l'instant.")
                }
            }
        }
    }

    private fun isSeriesScreen(state: StreamiaUiState, series: MediaEntry) =
        (state.screen as? StreamiaScreen.Series)?.series?.key == series.key

    // ---------- Raisons des recommandations ----------

    private suspend fun explainRecommendations(entries: List<MediaEntry>) {
        val snapshot = _uiState.value
        val profileId = snapshot.activeProfileId ?: return
        val candidates = entries.distinctBy(MediaEntry::key).filter { it.type != MediaType.Live }.take(MAX_EXPLAINED)
        if (candidates.size < 2) return
        val allowed = allowedEntries(snapshot.catalog?.categories.orEmpty(), snapshot.library, snapshot.appSettings.parentalControlEnabled, snapshot.parentalUnlocked)
        val tastes = pools.tastes(profileId, snapshot.library, allowed)
        // Sans goût connu, « parce que vous avez aimé… » n'aurait aucun sens : pas de requête.
        if (tastes.size < MIN_TASTES) return
        val pool = poolOf(candidates.filter(allowed)) { it.displayName.replace('|', '/') }
        val reasons = runCatching { ai.explainRecommendations(tastes, pool.candidates) }.getOrNull() ?: return
        if (!AiGate.active.value) return
        val byKey = reasons.mapNotNull { (id, reason) -> pool.entries[id]?.let { it.key to reason } }.toMap()
        if (byKey.isNotEmpty()) state.update { it.copy(reasons = it.reasons + byKey) }
    }

    private companion object {
        const val MIN_QUERY_CHARS = 4
        const val MAX_SUGGESTED_LOOKUPS = 20
        const val SUGGESTION_LOOKUP_LIMIT = 8
        const val KEYWORD_LIMIT = 400
        const val POOL_LIMIT = 800
        const val MAX_RESULTS = 80
        const val MAX_TV_IN_POOL = 6
        const val MAX_MATCH_LINES = 12
        const val MAX_PROGRAMME_LINES = 8
        const val MATCH_PAST_SECONDS = 150L * 60
        const val MATCH_AHEAD_SECONDS = 6L * 3_600
        const val BRIEF_REUSE_MS = 15 * 60_000L
        const val EXPLAIN_DELAY_MS = 1_500L
        const val MAX_EXPLAINED = 12
        const val MIN_TASTES = 2
        const val MAX_REMOTE_LOG = 6
        const val REMOTE_CHANNEL_LOOKUP = 40
        const val REMOTE_SEARCH_LIMIT = 40
        const val REMOTE_TITLES_IN_REPLY = 4
        const val NATURAL_SEARCH_MIN_WORDS = 3
        const val MAX_REMOTE_MATCHES = 30
        const val REMOTE_MATCH_AHEAD_SECONDS = 18L * 3_600
        const val REMOTE_OPEN_BEFORE_SECONDS = 20L * 60
        val EXPLAINED_KINDS = setOf(RecommendationRowKind.ForYou, RecommendationRowKind.BecauseYouWatched, RecommendationRowKind.Discover)
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/** « Film · 7,4 » / « Série » : de quoi situer une proposition en un coup d'œil. */
internal fun entryDetail(entry: MediaEntry): String {
    val kind = when (entry.type) {
        MediaType.Movie -> "Film"
        MediaType.Series -> "Série"
        MediaType.Live -> "Direct"
    }
    val rating = entry.rating?.takeIf { it in 0.1..10.0 }?.let { "%.1f".format(java.util.Locale.FRANCE, it) }
    return listOfNotNull(kind, rating?.let { "★ $it" }).joinToString(" · ")
}

package fr.streamia.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import fr.streamia.tv.domain.AccountInfo
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgGuide
import fr.streamia.tv.domain.EpgNowContext
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaDetails
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.SeriesDetails
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.XtreamUrlBuilder
import fr.streamia.tv.recommendation.ContentCandidateIndex
import fr.streamia.tv.recommendation.ContentFeatures
import fr.streamia.tv.recommendation.IndexedContent
import fr.streamia.tv.recommendation.MetadataSimilarityEngine
import fr.streamia.tv.recommendation.MovieLensNeighbors
import fr.streamia.tv.recommendation.SimilarityBoost
import fr.streamia.tv.recommendation.releaseYear
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Issue d'une demande d'installation de mise à jour (voir [XtreamRepository.installDownloadedUpdate]). */
enum class UpdateInstallStart { Started, PermissionRequested, PermissionStillMissing, BlockedByAdmin }

/** Rangée JustWatch lue sur disque ; [fresh] faux quand elle doit être recalculée. */
data class CachedJustWatchRow(val entries: List<MediaEntry>, val fresh: Boolean)

class XtreamRepository(context: Context) {
    private val appContext = context.applicationContext
    private val client = XtreamClient()
    private val cache = CatalogCache(context)
    private val epgCache = EpgCache(context)
    private val credentialsStore = CredentialsStore(context)
    private val playlistStore = PlaylistStore(context)
    private val libraryStore = UserLibraryStore(context)
    private val appSettingsStore = AppSettingsStore(context)
    private val recommendationStore = RecommendationStore(context)
    private val liveOnSatRepository = LiveOnSatRepository(context)
    private val tvProgrammeRepository = TvProgrammeRepository(context)
    private val tvProgrammeNowRepository = TvProgrammeNowRepository(context)
    private val beinSportsGuideRepository = BeinSportsGuideRepository(context)
    private val ukGuideRepository = UkGuideRepository(context)
    private val xmlTvRepository = XmlTvRepository()
    private val m3uParser = M3uParser()
    private val updateChecker = UpdateChecker()
    private val backupManager = BackupManager(context)

    fun profiles(): List<PlaylistProfile> = playlistStore.loadAll()
    fun profile(profileId: String): PlaylistProfile? = playlistStore.find(profileId)
    fun library(profileId: String): UserLibrarySnapshot = libraryStore.snapshot(profileId)
    fun appSettings(): AppSettings = appSettingsStore.load()
    fun updateAppSettings(transform: (AppSettings) -> AppSettings): AppSettings = appSettingsStore.update(transform)
    fun customizedCatalog(profileId: String, catalog: Catalog): Catalog = libraryStore.applyToCatalog(profileId, catalog)

    suspend fun checkForUpdate(currentBuild: Int): UpdateCheckResult =
        withContext(Dispatchers.IO) { updateChecker.checkForUpdate(currentBuild) }

    private val updateApk get() = File(appContext.cacheDir, "updates/streamia-tv.apk")

    /**
     * Télécharge l'APK de la release puis lance son installation. Renvoie false quand Streamia n'a
     * pas encore l'autorisation « Installer des applis inconnues » : le réglage est alors ouvert et
     * [installDownloadedUpdate] reprend l'installation au retour dans l'app.
     */
    suspend fun downloadAndInstallUpdate(release: ReleaseInfo): UpdateInstallStart {
        withContext(Dispatchers.IO) { updateChecker.downloadApk(release, updateApk) }
        return installDownloadedUpdate(openSettingsIfNeeded = true)
    }

    /**
     * Installe l'APK déjà téléchargé. Sans autorisation, le réglage n'est ouvert qu'une fois par
     * session : si l'autorisation manque encore au retour, l'app l'explique au lieu de renvoyer
     * indéfiniment vers un interrupteur qui paraît déjà activé.
     */
    suspend fun installDownloadedUpdate(openSettingsIfNeeded: Boolean = false): UpdateInstallStart {
        if (UpdateInstaller.blockedByAdmin(appContext)) return UpdateInstallStart.BlockedByAdmin
        if (!UpdateInstaller.canInstall(appContext)) {
            if (!openSettingsIfNeeded || unknownSourcesSettingsOpened) return UpdateInstallStart.PermissionStillMissing
            unknownSourcesSettingsOpened = true
            UpdateInstaller.openUnknownSourcesSettings(appContext)
            return UpdateInstallStart.PermissionRequested
        }
        withContext(Dispatchers.IO) { UpdateInstaller.install(appContext, updateApk) }
        return UpdateInstallStart.Started
    }

    private var unknownSourcesSettingsOpened = false

    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) { cache.databaseFileSizeBytes() }
    suspend fun epgCacheSizeBytes(): Long = withContext(Dispatchers.IO) { epgCache.databaseFileSizeBytes() }
    suspend fun clearEpgCache(profileId: String) = epgCache.clear(profileId)

    suspend fun exportBackup(profileId: String?): String = withContext(Dispatchers.IO) { backupManager.export(profileId) }

    suspend fun importBackup(profileId: String?, json: String): String =
        withContext(Dispatchers.IO) { backupManager.import(profileId, json) }

    /**
     * Lecture rapide du cache disque, sans jamais contacter le fournisseur. Sert à afficher la
     * liste déjà connue dès la relance de l'application pendant qu'[openProfile] confirme/actualise
     * en arrière-plan.
     */
    suspend fun cachedCatalog(profileId: String): Catalog? =
        cache.load(profileId)?.takeIf { it.hasPlayableContent() }

    /**
     * Charge une page bornée d'une catégorie (ou de « Tout » via [Catalog.ALL_CATEGORY_ID])
     * directement depuis l'index SQLite, sans matérialiser le reste du catalogue fournisseur.
     * [offset] doit correspondre au nombre d'entrées déjà chargées pour cette catégorie afin que
     * les pages s'enchaînent sans trou ni doublon une fois fusionnées via [Catalog.withMaterializedEntries].
     */
    suspend fun loadCategoryPage(
        profileId: String,
        type: MediaType,
        categoryId: String,
        offset: Int,
        order: VodSortOrder = VodSortOrder.Provider,
        limit: Int = DEFAULT_CATEGORY_PAGE_SIZE,
    ): CatalogPage = cache.loadCategoryPage(profileId, type, categoryId, offset, limit, order)

    /**
     * Charge tout un type (ex. Live) en une requête. Réservé aux sections qu'on choisit d'hydrater
     * entièrement — le Live proactivement au démarrage, ou Films/Séries à l'ouverture de
     * l'organisateur qui a besoin des listes complètes pour réordonner/déplacer des entrées.
     */
    suspend fun loadSection(profileId: String, type: MediaType): List<MediaEntry> = cache.loadType(profileId, type)

    suspend fun entriesByKeys(profileId: String, keys: Set<String>): List<MediaEntry> = cache.loadEntriesByKeys(profileId, keys)

    /** Contenus récents pour l'accueil ; les goûts complètent ce pool dans le ViewModel. */
    suspend fun homeRecommendationCandidates(profileId: String, type: MediaType, limit: Int): List<MediaEntry> =
        cache.loadHomeRecommendationCandidates(profileId, type, limit)

    /**
     * Candidats « similaires » : d'abord les plus proches dans TOUT le catalogue enrichi (genre,
     * intrigue, personnes, saga — voir [ContentCandidateIndex]), puis les voisins de catégorie.
     * Sert la fiche détail et les rangées « Parce que vous avez regardé » de l'accueil.
     */
    suspend fun similarityCandidates(
        profileId: String,
        source: MediaEntry,
        limit: Int,
        sourceFeatures: ContentFeatures? = null,
    ): List<MediaEntry> {
        val neighbours = cache.loadSimilarityCandidates(profileId, source, limit)
        val tmdbKeys = tmdbRelated(profileId, source).keys
        val index = runCatching { candidateIndex(profileId) }.getOrNull()
            ?: return (cache.loadEntriesByKeys(profileId, LinkedHashSet(tmdbKeys)) + neighbours).distinctBy(MediaEntry::key).take(limit)
        val indexed = indexedSource(source, sourceFeatures)
        val matchedKeys = withContext(Dispatchers.Default) {
            // Liens forts (saga, MovieLens, TMDB) d'abord, puis proximité genre / intrigue / mots-clés / personnes.
            (index.related(indexed, movieLens.of(indexed.tmdbId)).keys + tmdbKeys + index.topMatches(indexed, limit = (limit / 2).coerceAtLeast(1)))
                .distinct()
                .take(limit)
        }
        if (matchedKeys.isEmpty()) return neighbours
        val matched = cache.loadEntriesByKeys(profileId, LinkedHashSet(matchedKeys))
        return (matched + neighbours).distinctBy(MediaEntry::key).take(limit)
    }

    /** Liens forts (même saga Wikidata, voisins MovieLens) à faire remonter dans le classement. */
    suspend fun similarityBoosts(
        profileId: String,
        source: MediaEntry,
        sourceFeatures: ContentFeatures? = null,
    ): Map<String, SimilarityBoost> {
        val tmdb = tmdbRelated(profileId, source)
        val index = runCatching { candidateIndex(profileId) }.getOrNull() ?: return tmdb
        val indexed = indexedSource(source, sourceFeatures)
        val related = withContext(Dispatchers.Default) { index.related(indexed, movieLens.of(indexed.tmdbId)) }
        // Saga / MovieLens gardent la priorité quand ils sont plus sûrs que la recommandation TMDB.
        return tmdb + related.filter { (key, boost) -> (tmdb[key]?.score ?: 0.0) < boost.score }
    }

    private val tmdb = TmdbClient()
    // ponytail: cache mémoire non borné (une entrée par fiche ouverte dans la session), LRU si besoin.
    private val tmdbRelatedCache = ConcurrentHashMap<String, Map<String, SimilarityBoost>>()

    /**
     * Données TMDB d'un contenu (résumé anglais, genres, mots-clés) substituées aux métadonnées du
     * fournisseur pour la comparaison ; interroge TMDB une fois si le contenu n'a jamais été vu.
     */
    suspend fun withTmdb(profileId: String, features: ContentFeatures): ContentFeatures = withContext(Dispatchers.IO) {
        val key = features.entry.key
        val info = recommendationStore.tmdb(profileId, key) ?: run {
            val tmdbId = features.tmdbId?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it != "0" }
            if (!tmdb.enabled || tmdbId == null) return@withContext features
            val fetched = runCatching { tmdb.info(features.entry.type, tmdbId) }.getOrElse { return@withContext features }
            recommendationStore.saveTmdb(profileId, key, fetched)
            fetched
        } ?: return@withContext features
        features.copy(
            plot = info.overview ?: features.plot,
            genre = info.genres.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: features.genre,
            keywords = info.keywords,
        )
    }

    /**
     * Recommandations TMDB retrouvées dans le catalogue : par TMDB ID pour les contenus enrichis,
     * sinon par titre + année (recherche en base, comme « Autres versions »). Bonus modéré : elles
     * amènent des candidats, mais la ressemblance de contenu reste décisive dans le classement.
     */
    private suspend fun tmdbRelated(profileId: String, source: MediaEntry): Map<String, SimilarityBoost> {
        val cacheKey = "$profileId|${source.key}"
        tmdbRelatedCache[cacheKey]?.let { return it }
        val info = withContext(Dispatchers.IO) { recommendationStore.tmdb(profileId, source.key) } ?: return emptyMap()
        val titles = info.recommendations.take(TMDB_RELATED_LIMIT)
        val byId = withContext(Dispatchers.IO) { recommendationStore.keysByTmdbId(profileId, source.type, titles.map { it.id }) }
        val tokenizer = MetadataSimilarityEngine()
        val result = LinkedHashMap<String, SimilarityBoost>()
        titles.forEachIndexed { rank, title ->
            val key = byId[title.id] ?: listOfNotNull(title.title, title.originalTitle).firstNotNullOfOrNull { name ->
                val wanted = tokenizer.titleTokens(name).takeIf { it.isNotEmpty() } ?: return@firstNotNullOfOrNull null
                search(profileId, wanted.joinToString(" "), source.type, TMDB_SEARCH_LIMIT).firstOrNull { entry ->
                    val year = ContentFeatures(entry).releaseYear()
                    tokenizer.titleTokens(entry.displayName) == wanted && (year == null || title.year == null || year == title.year)
                }?.key
            } ?: return@forEachIndexed
            if (key != source.key) result.putIfAbsent(key, SimilarityBoost(TMDB_TOP_SCORE - rank * TMDB_RANK_STEP, "Recommandé par TMDB"))
        }
        tmdbRelatedCache[cacheKey] = result
        return result
    }

    /** Interroge TMDB pour un lot de contenus enrichis jamais vus. Renvoie le nombre traité (0 = fini). */
    suspend fun fetchTmdbBatch(profileId: String, batchSize: Int, pauseMs: Long): Int = withContext(Dispatchers.IO) {
        if (!tmdb.enabled) return@withContext 0
        val pending = recommendationStore.missingTmdbLookups(profileId, batchSize)
        var failures = 0
        for ((key, tmdbId) in pending) {
            val type = MediaType.entries.first { it.name == key.substringBefore(':') }
            // Échec réseau : non mémorisé, retenté au prochain passage ; plusieurs de suite = TMDB injoignable.
            runCatching { tmdb.info(type, tmdbId) }
                .onSuccess { recommendationStore.saveTmdb(profileId, key, it); failures = 0 }
                .onFailure { if (++failures >= TMDB_MAX_FAILURES) throw it }
            kotlinx.coroutines.delay(pauseMs)
        }
        pending.size
    }

    private fun indexedSource(source: MediaEntry, features: ContentFeatures?) = IndexedContent(
        source.key,
        source.displayName,
        features?.plot ?: source.plot,
        features?.genre,
        features?.cast,
        features?.director,
        tmdbId = features?.tmdbId,
        keywords = features?.keywords.orEmpty(),
    )

    private val movieLens = MovieLensNeighbors.fromAssets(context)

    private val candidateIndexLock = Mutex()
    @Volatile private var candidateIndexState: CandidateIndexState? = null
    private class CandidateIndexState(val profileId: String, val sourceCount: Int, val index: ContentCandidateIndex)

    /**
     * Index reconstruit seulement quand l'enrichissement a nettement progressé (+5 %, au moins 200
     * fiches) : l'enrichissement en arrière-plan ajoute des fiches en continu, reconstruire à chaque
     * ouverture de fiche coûterait une seconde de CPU sur un petit boîtier.
     */
    private suspend fun candidateIndex(profileId: String): ContentCandidateIndex? {
        val count = withContext(Dispatchers.IO) { recommendationStore.featureCount(profileId) + recommendationStore.sagaCount(profileId) }
        if (count == 0) return null
        fun fresh() = candidateIndexState?.takeIf {
            it.profileId == profileId && count - it.sourceCount < maxOf(INDEX_REBUILD_MIN_DELTA, it.sourceCount / 20)
        }?.index
        fresh()?.let { return it }
        return candidateIndexLock.withLock {
            fresh() ?: run {
                val features = withContext(Dispatchers.IO) { recommendationStore.features(profileId) }
                val sagas = withContext(Dispatchers.IO) { recommendationStore.sagas(profileId) }
                // Seulement les contenus enrichis : charger tout Films + Séries (plus de 200 000
                // entrées avec résumés) saturait la mémoire et faisait planter l'app (OOM).
                val entries = cache.loadEntriesByKeys(profileId, features.keys).associateBy(MediaEntry::key)
                val index = withContext(Dispatchers.Default) {
                    ContentCandidateIndex(
                        features.mapNotNull { (key, stored) ->
                            val entry = entries[key] ?: return@mapNotNull null
                            IndexedContent(
                                key,
                                entry.displayName,
                                stored.plot ?: entry.plot,
                                stored.genre,
                                stored.cast,
                                stored.director,
                                tmdbId = stored.tmdbId,
                                sagas = sagas[key].orEmpty(),
                                keywords = stored.keywords,
                            )
                        },
                    )
                }
                candidateIndexState = CandidateIndexState(profileId, count, index)
                index
            }
        }
    }

    private val wikidata = WikidataClient()
    private val justWatch = JustWatchClient()
    private val justWatchCache = ConcurrentHashMap<JustWatchSection, Pair<Long, List<TrendingTitle>>>()

    /** Section JustWatch (cache mémoire 6 h) réduite aux titres présents dans le catalogue, ordre JustWatch. */
    /**
     * Dernier rapprochement JustWatch ↔ playlist de cette rangée, gardé sur disque : l'accueil
     * l'affiche dès l'ouverture, sans réseau ni recherche. [CachedJustWatchRow.fresh] faux au-delà
     * de [JUSTWATCH_TTL_MS] : à recalculer (l'ancienne rangée reste affichée en attendant).
     */
    suspend fun cachedJustWatch(profileId: String, section: JustWatchSection): CachedJustWatchRow? = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(justWatchFile(profileId, section).readText())
            val keys = root.getJSONArray("keys").let { array -> (0 until array.length()).map(array::getString) }
            val byKey = entriesByKeys(profileId, keys.toSet()).associateBy(MediaEntry::key)
            CachedJustWatchRow(keys.mapNotNull(byKey::get), fresh = System.currentTimeMillis() - root.getLong("at") < JUSTWATCH_TTL_MS)
        }.getOrNull()
    }

    private fun justWatchTitlesFile(section: JustWatchSection) = File(appContext.filesDir, "justwatch-titles-${section.name}.json")

    /** Titres encore frais sur disque (horodatage d'origine gardé), sinon null. */
    private fun loadJustWatchTitles(section: JustWatchSection, now: Long): Pair<Long, List<TrendingTitle>>? = runCatching {
        val root = JSONObject(justWatchTitlesFile(section).readText())
        val at = root.getLong("at").takeIf { now - it < JUSTWATCH_TTL_MS } ?: return@runCatching null
        val array = root.getJSONArray("titles")
        at to (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            TrendingTitle(
                type = section.type,
                title = item.getString("title"),
                originalTitle = item.optString("originalTitle").ifBlank { null },
                year = item.optInt("year").takeIf { it > 0 },
            )
        }
    }.getOrNull()

    private fun fetchJustWatchTitles(section: JustWatchSection, now: Long): Pair<Long, List<TrendingTitle>> {
        val titles = justWatch.titles(section)
        runCatching {
            val array = JSONArray(titles.map { JSONObject().put("title", it.title).put("originalTitle", it.originalTitle).put("year", it.year) })
            justWatchTitlesFile(section).writeText(JSONObject().put("at", now).put("titles", array).toString())
        }
        return now to titles
    }

    private fun justWatchFile(profileId: String, section: JustWatchSection) =
        File(appContext.filesDir, "justwatch-$profileId-${section.name}.json")

    suspend fun justWatch(profileId: String, section: JustWatchSection, limit: Int): List<MediaEntry> {
        val now = System.currentTimeMillis()
        // Titres JustWatch communs à toutes les listes : mémoire, puis disque, puis réseau.
        val titles = justWatchCache[section]?.takeIf { now - it.first < JUSTWATCH_TTL_MS }?.second
            ?: withContext(Dispatchers.IO) { loadJustWatchTitles(section, now) ?: fetchJustWatchTitles(section, now) }
                .also { justWatchCache[section] = it }.second
        val result = LinkedHashMap<String, MediaEntry>()
        for (title in titles) {
            if (result.size >= limit) break
            val match = search(profileId, title.title, title.type, JUSTWATCH_SEARCH_LIMIT).firstOrNull { matchesTrending(it, title) }
                ?: title.originalTitle?.let { original ->
                    search(profileId, original, title.type, JUSTWATCH_SEARCH_LIMIT).firstOrNull { matchesTrending(it, title) }
                }
            match?.let { result.putIfAbsent(titleKey(it.displayName), it) }
        }
        withContext(Dispatchers.IO) {
            runCatching {
                justWatchFile(profileId, section).writeText(
                    JSONObject().put("at", now).put("keys", JSONArray(result.values.map(MediaEntry::key))).toString(),
                )
            }
        }
        return result.values.toList()
    }

    /**
     * Interroge Wikidata pour un lot de contenus enrichis jamais vérifiés. Renvoie le nombre traité
     * (0 = plus rien à faire). Les contenus sans saga sont mémorisés vides pour ne pas redemander.
     */
    suspend fun fetchSagaBatch(profileId: String, batchSize: Int): Int = withContext(Dispatchers.IO) {
        val pending = recommendationStore.missingSagaLookups(profileId, batchSize)
        if (pending.isEmpty()) return@withContext 0
        val found = pending.entries.groupBy { MediaType.entries.first { t -> t.name == it.key.substringBefore(':') } }
            .flatMap { (type, rows) ->
                val byTmdb = wikidata.sagas(type, rows.map { it.value }.distinct())
                rows.map { it.key to byTmdb[it.value].orEmpty() }
            }
            .toMap()
        recommendationStore.saveSagas(profileId, found)
        pending.size
    }

    /** Clés déjà enrichies, pour l'enrichissement en arrière-plan. */
    suspend fun enrichedRecommendationKeys(profileId: String): Set<String> =
        withContext(Dispatchers.IO) { recommendationStore.enrichedKeys(profileId) }

    /** Retours explicites déjà persistés : « plus comme ça » / « moins comme ça ». */
    suspend fun recommendationFeedback(profileId: String) =
        withContext(Dispatchers.IO) { recommendationStore.feedback(profileId) }

    /**
     * Fusionne, pour les entrées demandées, les métadonnées enrichies (genre/casting/réalisateur…)
     * déjà mises en cache lors d'une visite de fiche avec le reste du catalogue, qui ne connaît que
     * les champs de base ([MediaEntry.plot]).
     */
    suspend fun recommendationContentFeatures(
        profileId: String,
        entries: Collection<MediaEntry>,
    ): Map<String, ContentFeatures> = withContext(Dispatchers.IO) {
        val stored = recommendationStore.features(profileId)
        entries.associate { entry -> entry.key to (stored[entry.key]?.merge(entry) ?: ContentFeatures.from(entry)) }
    }

    /** Enrichit le moteur de recommandation dès qu'une fiche film/série est réellement consultée. */
    suspend fun cacheRecommendationDetails(profileId: String, details: MediaDetails) =
        withContext(Dispatchers.IO) { recommendationStore.saveDetails(profileId, details) }

    /**
     * Matchs du jour scrapés depuis liveonsat.com, sans lien avec un profil Xtream/M3U particulier
     * (seule leur mise en correspondance avec les chaînes, faite par l'appelant, en dépend).
     */
    suspend fun loadLiveOnSatMatches(forceRefresh: Boolean = false): LiveOnSatFetchResult =
        liveOnSatRepository.loadMatches(forceRefresh, maxAgeMillis = LIVE_ONSAT_CACHE_MAX_AGE_MS)

    /**
     * Les trois sources du rapprochement liveonsat : scrape, actualisation de la playlist (chaînes)
     * et synchronisation EPG (horaires). À calculer avant le rapprochement et à réutiliser pour
     * l'enregistrer, pour qu'une synchronisation survenue pendant le calcul le fasse refaire.
     */
    suspend fun liveOnSatResolutionVersion(profileId: String, fetch: LiveOnSatFetchResult): String {
        val catalogRefreshedAt = playlistStore.find(profileId)?.lastRefreshAt ?: 0L
        val epgSyncedAt = epgCache.metadata(profileId)?.syncedAtMillis ?: 0L
        return "${fetch.fetchedAtEpochMillis}|$catalogRefreshedAt|$epgSyncedAt"
    }

    /** Rapprochement chaînes/EPG déjà calculé pour cette [version], ou null s'il faut le refaire. */
    suspend fun cachedLiveOnSatResolution(profileId: String, version: String, fetch: LiveOnSatFetchResult): List<ResolvedLiveOnSatMatch>? {
        val saved = liveOnSatRepository.loadResolution(profileId, version)
            ?.takeIf { it.size == fetch.matches.size }
            ?: return null
        val keys = saved.flatMapTo(mutableSetOf()) { it.channelKeys.values.flatten() }
        val entries = entriesByKeys(profileId, keys).associateBy(MediaEntry::key)
        return fetch.matches.zip(saved) { match, resolution ->
            ResolvedLiveOnSatMatch(
                match = match,
                matchedChannels = resolution.channelKeys
                    .mapValues { (_, channelKeys) -> channelKeys.mapNotNull(entries::get) }
                    .filterValues { it.isNotEmpty() },
                epgStartEpochSeconds = resolution.epgStartEpochSeconds,
                epgEndEpochSeconds = resolution.epgEndEpochSeconds,
            )
        }
    }

    suspend fun saveLiveOnSatResolution(profileId: String, version: String, resolved: List<ResolvedLiveOnSatMatch>) =
        liveOnSatRepository.saveResolution(
            profileId,
            version,
            resolved.map {
                LiveOnSatResolution(
                    channelKeys = it.matchedChannels.mapValues { (_, channels) -> channels.map(MediaEntry::key) },
                    epgStartEpochSeconds = it.epgStartEpochSeconds,
                    epgEndEpochSeconds = it.epgEndEpochSeconds,
                )
            },
        )

    /** Cache disque encore frais : les chargements correspondants ne contacteront aucun site. */
    suspend fun hasFreshLiveOnSatCache() = liveOnSatRepository.hasFreshCache(LIVE_ONSAT_CACHE_MAX_AGE_MS)
    suspend fun hasFreshTvProgrammeTonightCache() = tvProgrammeRepository.hasFreshCache(TV_PROGRAMME_CACHE_MAX_AGE_MS)
    suspend fun hasFreshTvProgrammeNowCache() = tvProgrammeNowRepository.hasFreshCache(TV_PROGRAMME_NOW_CACHE_MAX_AGE_MS)
    suspend fun hasFreshBeinSportsGuideCache() = beinSportsGuideRepository.hasFreshCache(BEIN_SPORTS_GUIDE_CACHE_MAX_AGE_MS)
    suspend fun hasFreshUkGuideCache() = ukGuideRepository.hasFreshCache(UK_GUIDE_CACHE_MAX_AGE_MS)

    /** Programmes TV français du soir scrapés depuis tv-programme.com avec cache local. */
    suspend fun loadTvProgrammeTonight(forceRefresh: Boolean = false): TvProgrammeFetchResult =
        tvProgrammeRepository.loadTonight(forceRefresh, maxAgeMillis = TV_PROGRAMME_CACHE_MAX_AGE_MS)

    /** Programmes TV français actuellement diffusés, rafraîchis fréquemment. */
    suspend fun loadTvProgrammeNow(forceRefresh: Boolean = false): TvProgrammeNowFetchResult =
        tvProgrammeNowRepository.loadNow(forceRefresh, maxAgeMillis = TV_PROGRAMME_NOW_CACHE_MAX_AGE_MS)

    /** Grille MENA beIN SPORTS : programmes en cours et suivants avec cache court. */
    suspend fun loadBeinSportsGuide(forceRefresh: Boolean = false): BeinSportsGuideFetchResult =
        beinSportsGuideRepository.loadGuide(forceRefresh, maxAgeMillis = BEIN_SPORTS_GUIDE_CACHE_MAX_AGE_MS)

    /** Grille TV britannique (tvguideuk.com) : programmes en cours et suivants avec cache court. */
    suspend fun loadUkGuide(forceRefresh: Boolean = false): UkGuideFetchResult =
        ukGuideRepository.loadGuide(forceRefresh, maxAgeMillis = UK_GUIDE_CACHE_MAX_AGE_MS)

    /**
     * Recherche indexée en base plutôt que dans le sous-ensemble matérialisé en mémoire : sous
     * chargement paresseux, un film ou une série jamais parcouru par l'utilisateur n'existe pas
     * encore dans `Catalog.entries`, donc [fr.streamia.tv.domain.Catalog.search] le raterait.
     */
    suspend fun search(profileId: String, query: String, type: MediaType?, limit: Int = 600): List<MediaEntry> =
        cache.search(profileId, query, type, limit)

    /**
     * Catalogue déjà résolu (favoris/ordre appliqués) tel qu'obtenu à la fin de la dernière
     * réconciliation réussie pour ce profil, réutilisable seulement si [librarySnapshot]
     * correspond toujours à l'organisation utilisée pour le produire (voir
     * [UserLibrarySnapshot.catalogLayoutFingerprint]). Permet à une relance de l'app de sauter
     * entièrement le passage par [prepareCatalogPresentation] plutôt que juste la lecture réseau :
     * l'appelant doit tout de même laisser [openProfile] + la réconciliation habituelle tourner en
     * arrière-plan pour rattraper un éventuel changement côté fournisseur depuis.
     */
    suspend fun resolvedCatalogIfLayoutUnchanged(profileId: String, librarySnapshot: UserLibrarySnapshot): Catalog? =
        cache.loadResolved(profileId, librarySnapshot.catalogLayoutFingerprint())

    /** Persiste le résultat d'une réconciliation réussie pour que la prochaine relance de l'app puisse le réutiliser directement. */
    suspend fun saveResolvedCatalog(profileId: String, catalog: Catalog, librarySnapshot: UserLibrarySnapshot) {
        cache.saveResolved(profileId, catalog, librarySnapshot.catalogLayoutFingerprint())
    }

    /** Prépare les index d'un catalogue massif hors du thread d'interface. */
    suspend fun prepareCatalogPresentation(
        profileId: String,
        catalog: Catalog,
        librarySnapshot: UserLibrarySnapshot? = null,
    ): CatalogPresentation =
        withContext(Dispatchers.Default) {
            val library = librarySnapshot ?: libraryStore.snapshot(profileId)
            CatalogPresentation(
                catalog = libraryStore.applyToCatalog(catalog, library),
                library = library,
                profiles = playlistStore.loadAll(),
            )
        }

    /**
     * Ouvre d'abord le cache local afin que l'interface soit disponible immédiatement.
     * Un cache Xtream est toujours renvoyé comme Local, sans date d'expiration.
     *
     * [knownCache] évite de relire et reparser le fichier de cache disque quand l'appelant l'a déjà
     * fait juste avant (typiquement via [cachedCatalog] pour afficher l'écran immédiatement) : sur
     * un catalogue volumineux, reparser le même JSON une seconde fois pour rien coûte de l'I/O et du
     * CPU à chaque relance de l'app, ce qui ralentit d'autant l'affichage des catégories/chaînes.
     */
    suspend fun openProfile(profileId: String, knownCache: Catalog? = null): LoadedCatalog {
        val profile = playlistStore.find(profileId) ?: throw XtreamException("Cette liste n'existe plus.")
        val credentials = profile.credentialsOrNull()
        val cached = knownCache
            ?: cache.load(profile.id)?.takeIf { profile.kind != PlaylistKind.Xtream || it.hasPlayableContent() }
        if (credentials != null && cached != null) {
            credentialsStore.save(credentials)
            // Catalogue expiré : servi tout de suite depuis le cache, puis actualisé en arrière-plan.
            val source = if (profile.shouldAutoRefresh()) {
                CatalogSource.Cache
            } else {
                CatalogSource.Local
            }
            return LoadedCatalog(cached, credentials, source, profile.id)
        }
        return when (profile.kind) {
            PlaylistKind.Xtream -> openXtreamProfile(profile)
            PlaylistKind.M3u -> openM3uProfile(profile)
        }
    }

    suspend fun signIn(
        credentials: ServerCredentials,
        profileId: String? = null,
        profileName: String? = null,
    ): LoadedCatalog {
        XtreamUrlBuilder(credentials)
        val id = profileId ?: UUID.randomUUID().toString()
        val catalog = fetchAndStoreXtreamCatalog(id, credentials)
        val previous = profileId?.let(playlistStore::find)
        val profile = PlaylistProfile(
            id = id,
            name = profileName.cleanName(defaultValue = credentials.serverUrl),
            kind = PlaylistKind.Xtream,
            serverUrl = credentials.serverUrl,
            username = credentials.username,
            password = credentials.password,
            xmlTvUrl = previous?.xmlTvUrl,
            autoRefreshHours = previous?.autoRefreshHours ?: 6,
            lastRefreshAt = System.currentTimeMillis(),
        )
        playlistStore.upsert(profile)
        credentialsStore.save(credentials)
        return LoadedCatalog(catalog, credentials, CatalogSource.Network, id)
    }

    suspend fun refreshProfile(profileId: String): LoadedCatalog = withContext(Dispatchers.IO) {
        val profile = playlistStore.find(profileId) ?: throw XtreamException("Cette liste n'existe plus.")
        when (profile.kind) {
            PlaylistKind.Xtream -> {
                val credentials = profile.credentialsOrNull() ?: throw XtreamException("Identifiants Xtream incomplets.")
                val catalog = fetchAndStoreXtreamCatalog(profileId, credentials)
                playlistStore.markRefreshed(profileId)
                LoadedCatalog(catalog, credentials, CatalogSource.Network, profileId)
            }
            PlaylistKind.M3u -> if (profile.isRemoteM3u) {
                importM3uUrl(
                    url = profile.m3uUrl!!,
                    profileId = profile.id,
                    profileName = profile.name,
                    xmlTvUrl = profile.xmlTvUrl,
                    autoRefreshHours = profile.autoRefreshHours,
                )
            } else {
                val uri = profile.m3uUri?.let(Uri::parse)
                    ?: throw XtreamException("Le fichier M3U associé à cette liste est introuvable.")
                importM3u(uri, profile.id, profile.name)
            }
        }
    }

    suspend fun importM3u(
        uri: Uri,
        profileId: String? = null,
        profileName: String? = null,
    ): LoadedCatalog = withContext(Dispatchers.IO) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw XtreamException("Impossible d'ouvrir le fichier M3U sélectionné.")
        val imported = input.use { m3uParser.parse(InputStreamReader(it, StandardCharsets.UTF_8)) }
        val id = profileId ?: UUID.randomUUID().toString()
        val previous = playlistStore.find(id)
        saveM3uImport(
            id = id,
            name = profileName.cleanName(defaultValue = queryDisplayName(uri) ?: "Playlist M3U"),
            imported = imported,
            m3uUri = uri.toString(),
            m3uUrl = null,
            xmlTvUrl = previous?.xmlTvUrl,
            autoRefreshHours = previous?.autoRefreshHours ?: 6,
        )
    }

    suspend fun importM3uUrl(
        url: String,
        profileId: String? = null,
        profileName: String? = null,
        xmlTvUrl: String? = null,
        autoRefreshHours: Int = 6,
    ): LoadedCatalog = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeRemoteUrl(url)
        val temporary = File.createTempFile("streamia-remote-", ".m3u", appContext.cacheDir)
        try {
            downloadToFile(normalizedUrl, temporary)
            val imported = temporary.inputStream().use {
                m3uParser.parse(InputStreamReader(it, StandardCharsets.UTF_8))
            }
            val id = profileId ?: UUID.randomUUID().toString()
            saveM3uImport(
                id = id,
                name = profileName.cleanName(defaultValue = "Playlist M3U distante"),
                imported = imported,
                m3uUri = null,
                m3uUrl = normalizedUrl,
                xmlTvUrl = xmlTvUrl,
                autoRefreshHours = autoRefreshHours,
            )
        } finally {
            temporary.delete()
        }
    }

    fun updateRemoteSettings(profileId: String, m3uUrl: String?, xmlTvUrl: String?, autoRefreshHours: Int): PlaylistProfile? =
        playlistStore.updateRemoteSettings(profileId, m3uUrl, xmlTvUrl, autoRefreshHours)

    fun renameProfile(profileId: String, name: String): PlaylistProfile? = playlistStore.rename(profileId, name)

    suspend fun deleteProfile(profileId: String) {
        playlistStore.delete(profileId)
        cache.clear(profileId)
        epgCache.clear(profileId)
        liveOnSatRepository.clearResolution(profileId)
    }

    /** Contrôle léger des identifiants (sans charger le catalogue), utilisé avant l'enregistrement. */
    suspend fun testConnection(credentials: ServerCredentials): AccountInfo = client.testConnection(credentials)

    suspend fun movieDetails(credentials: ServerCredentials, movie: MediaEntry): MediaDetails =
        client.loadMovieDetails(credentials, movie)

    suspend fun seriesDetails(credentials: ServerCredentials, series: MediaEntry): SeriesDetails =
        client.loadSeriesDetails(credentials, series)

    /**
     * Enrichissement à la demande des candidats "similaires". Les métadonnées récupérées sont
     * immédiatement persistées pour que les ouvertures suivantes n'aient plus besoin du réseau.
     */
    suspend fun enrichRecommendationDetails(
        profileId: String,
        credentials: ServerCredentials,
        media: MediaEntry,
    ): ContentFeatures {
        val details = client.loadSimilarityDetails(credentials, media)
        withContext(Dispatchers.IO) { recommendationStore.saveDetails(profileId, details) }
        return ContentFeatures.from(media, details)
    }

    suspend fun shortEpg(credentials: ServerCredentials, streamId: Int): List<EpgProgram> =
        client.loadShortEpg(credentials, streamId)

    suspend fun epgMetadata(profileId: String): EpgCacheMetadata? = epgCache.metadata(profileId)

    suspend fun cachedEpgNow(
        profileId: String,
        entry: MediaEntry,
        nowEpochSeconds: Long,
        offsetHours: Int,
    ): EpgNowContext? = epgCache.nowContext(profileId, entry, nowEpochSeconds, offsetHours)

    suspend fun cachedEpgGuide(
        profileId: String,
        displayStartEpochSeconds: Long,
        displayEndEpochSeconds: Long,
        offsetHours: Int,
    ): EpgGuide = epgCache.guide(
        profileId = profileId,
        displayStartEpochSeconds = displayStartEpochSeconds,
        displayEndEpochSeconds = displayEndEpochSeconds,
        offsetHours = offsetHours,
    )

    /**
     * Synchronise le XMLTV sans bloquer l'UI et sans matérialiser le guide complet en RAM. Le
     * remplacement SQLite est transactionnel : une source cassée laisse l'ancien EPG intact.
     */
    /** EPG encore dans sa fenêtre de fraîcheur (`autoRefreshHours` du profil) : aucun téléchargement utile. */
    suspend fun isEpgFresh(profileId: String): Boolean = withContext(Dispatchers.IO) { isEpgFreshOnIo(profileId) }

    private fun isEpgFreshOnIo(profileId: String): Boolean {
        val maxAgeMillis = (playlistStore.find(profileId)?.autoRefreshHours ?: 6).coerceIn(1, 168) * 3_600_000L
        return epgCache.metadataOnIo(profileId)?.isFreshAt(System.currentTimeMillis(), maxAgeMillis) == true
    }

    suspend fun refreshEpg(
        profileId: String,
        credentials: ServerCredentials,
        liveEntries: List<MediaEntry>,
        force: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        if (liveEntries.isEmpty()) return@withContext false
        epgSyncMutexFor(profileId).withLock {
            val profile = playlistStore.find(profileId)
            if (!force && isEpgFreshOnIo(profileId)) return@withLock false

            val preferred = profile?.xmlTvUrl?.trim()?.takeIf(String::isNotBlank)?.let(::normalizeRemoteUrl)
            val provider = XtreamUrlBuilder(credentials).xmlTv()
            val sources = buildList {
                preferred?.let(::add)
                add(provider)
            }.distinct()

            var lastError: Throwable? = null
            for (source in sources) {
                val session = epgCache.beginReplaceOnIo(profileId)
                try {
                    xmlTvRepository.syncOnIo(source, liveEntries, session)
                    if (session.writtenProgramCount <= 0) {
                        throw XtreamException("La source EPG ne contient aucun programme exploitable correspondant aux chaînes.")
                    }
                    epgCache.commitReplaceOnIo(session, source)
                    return@withLock true
                } catch (error: Throwable) {
                    epgCache.abortReplaceOnIo(session)
                    lastError = error
                }
            }
            throw lastError ?: XtreamException("Aucune source EPG disponible.")
        }
    }

    suspend fun fullEpg(profileId: String, credentials: ServerCredentials, catalog: Catalog): EpgGuide {
        val profile = playlistStore.find(profileId)
        val preferred = profile?.xmlTvUrl?.trim()?.takeIf(String::isNotBlank)
        val provider = XtreamUrlBuilder(credentials).xmlTv()
        if (preferred != null) {
            runCatching { return xmlTvRepository.load(normalizeRemoteUrl(preferred), catalog.entriesFor(MediaType.Live)) }
        }
        return xmlTvRepository.load(provider, catalog.entriesFor(MediaType.Live))
    }

    fun toggleEntryFavorite(profileId: String, entry: MediaEntry): Boolean = libraryStore.toggleEntryFavorite(profileId, entry)
    fun toggleEntryHidden(profileId: String, entry: MediaEntry): Boolean = libraryStore.toggleEntryHidden(profileId, entry)
    fun toggleCategoryFavorite(profileId: String, category: MediaCategory): Boolean = libraryStore.toggleCategoryFavorite(profileId, category)
    fun toggleCategoryHidden(profileId: String, category: MediaCategory): Boolean = libraryStore.toggleCategoryHidden(profileId, category)
    fun toggleCategoryLocked(profileId: String, category: MediaCategory): Boolean = libraryStore.toggleCategoryLocked(profileId, category)
    fun toggleEntryWatched(profileId: String, entry: MediaEntry): Boolean = libraryStore.toggleEntryWatched(profileId, entry)
    fun setParentalPin(pin: String): AppSettings = appSettingsStore.setParentalPin(pin)
    fun clearParentalPin(): AppSettings = appSettingsStore.clearParentalPin()
    fun verifyParentalPin(pin: String): Boolean = appSettingsStore.verifyParentalPin(pin)
    fun recordPlayback(profileId: String, entry: MediaEntry, positionMs: Long, durationMs: Long) =
        libraryStore.recordPlayback(profileId, entry, positionMs, durationMs)
    private val watchNextPublisher = WatchNextPublisher(appContext)

    /** Met à jour la rangée « Continuer à regarder » de Google TV depuis l'historique (sur IO). */
    fun publishWatchNext(profileId: String) = watchNextPublisher.publish(profileId, libraryStore.snapshot(profileId).history)

    fun resumePosition(profileId: String, entryKey: String): Long = libraryStore.resumePosition(profileId, entryKey)
    fun clearHistory(profileId: String, type: MediaType? = null) = libraryStore.clearHistory(profileId, type)
    fun setCategoryOrder(profileId: String, type: MediaType, categoryKeys: List<String>) =
        libraryStore.setCategoryOrder(profileId, type, categoryKeys)
    fun moveEntries(profileId: String, entryKeys: Set<String>, targetCategoryId: String) =
        libraryStore.moveEntries(profileId, entryKeys, targetCategoryId)
    fun resetEntryMoves(profileId: String, entryKeys: Set<String>) = libraryStore.resetEntryMoves(profileId, entryKeys)

    suspend fun logout() { credentialsStore.clear() }

    private suspend fun openXtreamProfile(profile: PlaylistProfile): LoadedCatalog {
        val credentials = profile.credentialsOrNull()
            ?: throw XtreamException("Les identifiants de cette liste Xtream sont incomplets.")
        return try {
            val catalog = fetchAndStoreXtreamCatalog(profile.id, credentials)
            credentialsStore.save(credentials)
            playlistStore.markRefreshed(profile.id)
            LoadedCatalog(catalog, credentials, CatalogSource.Network, profile.id)
        } catch (error: Exception) {
            val cached = cache.load(profile.id)?.takeIf { it.hasPlayableContent() } ?: throw error
            credentialsStore.save(credentials)
            LoadedCatalog(cached, credentials, CatalogSource.Cache, profile.id)
        }
    }

    /**
     * Récupère le catalogue Xtream et l'écrit directement en base par lots pendant le parsing
     * ([XtreamClient.loadCatalogOnIo]) plutôt que de matérialiser toutes les entrées en mémoire
     * avant de les persister. La session n'est validée que si le fournisseur a bien renvoyé du
     * contenu lisible ; sinon elle est annulée et l'ancien catalogue valide reste en place,
     * exactement comme avant ce changement — voir [CatalogDatabase.ReplaceSession].
     *
     * Le tout doit s'exécuter sur un seul et même thread : une transaction SQLite Android est
     * confinée au thread qui l'a ouverte (`beginTransaction`/`endTransaction` utilisent un état par
     * thread), donc begin/écriture/commit/abort doivent tous tourner sur le même thread. Plutôt que
     * de compter sur le fait que des `withContext(Dispatchers.IO)` imbriqués ne redistribuent pas
     * quand le dispatcher ne change pas — un détail d'implémentation de kotlinx.coroutines, pas une
     * garantie de son API publique — un seul [withContext] englobant est utilisé ici, et
     * begin/commit/abort/[XtreamClient.loadCatalogOnIo] sont de simples fonctions synchrones :
     * aucun point de suspension n'existe entre l'ouverture et la fin de la transaction, donc rien
     * ne peut ni migrer de thread ni être interrompu par une annulation de coroutine au milieu.
     */
    private suspend fun fetchAndStoreXtreamCatalog(profileId: String, credentials: ServerCredentials): Catalog =
        withContext(Dispatchers.IO) {
            val session = cache.beginReplaceOnIo(profileId)
            val result = try {
                client.loadCatalogOnIo(credentials, session)
            } catch (error: Throwable) {
                cache.abortReplaceOnIo(session)
                throw error
            }
            if (result.counts.values.none { it > 0 }) {
                cache.abortReplaceOnIo(session)
                throw XtreamException("Le fournisseur a renvoyé un catalogue vide. Le cache existant est conservé.")
            }
            cache.commitReplaceOnIo(session, result.account)
            cache.load(profileId) ?: throw XtreamException("Le catalogue vient d'être enregistré mais ne peut pas être relu.")
        }

    private suspend fun openM3uProfile(profile: PlaylistProfile): LoadedCatalog {
        if (profile.isRemoteM3u) {
            if (profile.shouldAutoRefresh() || cache.load(profile.id) == null) {
                return try {
                    importM3uUrl(
                        url = profile.m3uUrl!!,
                        profileId = profile.id,
                        profileName = profile.name,
                        xmlTvUrl = profile.xmlTvUrl,
                        autoRefreshHours = profile.autoRefreshHours,
                    )
                } catch (error: Exception) {
                    val credentials = profile.credentialsOrNull() ?: throw error
                    val cached = cache.load(profile.id) ?: throw error
                    credentialsStore.save(credentials)
                    LoadedCatalog(cached, credentials, CatalogSource.Cache, profile.id)
                }
            }
            val credentials = profile.credentialsOrNull()
                ?: throw XtreamException("Les identifiants extraits de la liste distante sont incomplets.")
            val cached = cache.load(profile.id)
                ?: return importM3uUrl(profile.m3uUrl!!, profile.id, profile.name, profile.xmlTvUrl, profile.autoRefreshHours)
            credentialsStore.save(credentials)
            return LoadedCatalog(cached, credentials, CatalogSource.Local, profile.id)
        }

        val uri = profile.m3uUri?.let(Uri::parse)
            ?: throw XtreamException("Le fichier M3U associé à cette liste est introuvable.")
        return try {
            importM3u(uri, profile.id, profile.name)
        } catch (error: Exception) {
            val credentials = profile.credentialsOrNull() ?: throw error
            val cached = cache.load(profile.id) ?: throw error
            credentialsStore.save(credentials)
            LoadedCatalog(cached, credentials, CatalogSource.Cache, profile.id)
        }
    }

    private suspend fun saveM3uImport(
        id: String,
        name: String,
        imported: M3uImport,
        m3uUri: String?,
        m3uUrl: String?,
        xmlTvUrl: String?,
        autoRefreshHours: Int,
    ): LoadedCatalog {
        val profile = PlaylistProfile(
            id = id,
            name = name,
            kind = PlaylistKind.M3u,
            serverUrl = imported.credentials.serverUrl,
            username = imported.credentials.username,
            password = imported.credentials.password,
            m3uUri = m3uUri,
            m3uUrl = m3uUrl,
            xmlTvUrl = xmlTvUrl?.trim()?.takeIf(String::isNotBlank),
            autoRefreshHours = autoRefreshHours.coerceIn(1, 168),
            lastRefreshAt = System.currentTimeMillis(),
        )
        playlistStore.upsert(profile)
        credentialsStore.save(imported.credentials)
        cache.save(id, imported.catalog)
        val catalog = imported.catalog
        val summary = buildString {
            append("${imported.parsedEntries} médias")
            append(" · ${catalog.count(MediaType.Live)} chaînes")
            append(" · ${catalog.count(MediaType.Movie)} films")
            append(" · ${catalog.count(MediaType.Series)} séries")
            append(" · ${catalog.categories.size} catégories")
            if ("tvg-logo" in imported.detectedAttributes) append(" · logos")
            if ("tvg-id" in imported.detectedAttributes) append(" · EPG/TVG")
            if (imported.skippedEntries > 0) append(" · ${imported.skippedEntries} ignorés")
        }
        return LoadedCatalog(catalog, imported.credentials, CatalogSource.Import, id, summary)
    }

    private fun downloadToFile(url: String, target: File) {
        try {
            downloadOnce(url, target)
        } catch (first: IOException) {
            val alternate = XtreamUrlBuilder.alternateTransportUrl(url) ?: throw first
            downloadOnce(alternate, target)
        }
    }

    @Throws(IOException::class)
    private fun downloadOnce(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/x-mpegURL,text/plain,*/*")
            setRequestProperty("User-Agent", "Streamia-TV/1.5")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw XtreamException("Le serveur M3U a répondu avec le code $code.")
            connection.inputStream.use { input -> target.outputStream().buffered().use { output -> input.copyTo(output, 128 * 1024) } }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeRemoteUrl(raw: String): String {
        val value = raw.trim()
        require(value.isNotBlank()) { "L'URL ne peut pas être vide." }
        return if (value.contains("://")) value else "https://$value"
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(0)?.takeIf(String::isNotBlank)
        }
    }.getOrNull()

    private fun String?.cleanName(defaultValue: String): String = this?.trim()?.takeIf(String::isNotBlank) ?: defaultValue

    private fun Catalog.hasPlayableContent(): Boolean = MediaType.entries.any { count(it) > 0 }

    companion object {
        const val DEFAULT_CATEGORY_PAGE_SIZE = 500

        // Chargé au démarrage de l'app puis relu depuis ce cache disque (page Matchs, accueil) :
        // un nouveau scrape au plus toutes les 2 h, ou sur le bouton Actualiser. liveonsat.com n'a
        // pas d'API et ne doit pas être sollicité plus souvent que nécessaire.
        const val LIVE_ONSAT_CACHE_MAX_AGE_MS = 2 * 60 * 60_000L

        // Le programme du soir change beaucoup moins souvent que les matchs live. Deux heures
        // limitent les requêtes vers tv-programme.com tout en renouvelant les données dans la soirée.
        private const val TV_PROGRAMME_CACHE_MAX_AGE_MS = 2 * 60 * 60_000L

        // Le cache garde toute la grille (programme en cours recalculé localement) : pas besoin de
        // re-scraper souvent, et tv-programme.com bloque (403) les requêtes trop fréquentes.
        private const val TV_PROGRAMME_NOW_CACHE_MAX_AGE_MS = 30 * 60_000L

        // La grille couvre 24 h et "maintenant"/"suivant" est recalculé localement depuis le cache
        // (boucle de 2 min de l'accueil) : re-télécharger plus souvent ne changerait rien.
        private const val BEIN_SPORTS_GUIDE_CACHE_MAX_AGE_MS = 30 * 60_000L

        // Grille complète en cache, créneau courant recalculé localement : ~16 requêtes par
        // téléchargement, donc pas plus d'une fois par demi-heure vers tvguideuk.com.
        private const val UK_GUIDE_CACHE_MAX_AGE_MS = 30 * 60_000L
private const val INDEX_REBUILD_MIN_DELTA = 200

        // Partagé par toutes les instances de XtreamRepository du process : le worker EPG en
        // arrière-plan (EpgSyncWorker) et le ViewModel créent chacun leur propre instance, mais
        // toutes deux visent le même fichier SQLite pour un profil donné. Sans ce verrou, un
        // premier passage du worker au tout premier lancement de l'app (WorkManager exécute une
        // périodique sans délai initial dès que le réseau est disponible) peut chevaucher la
        // synchronisation au premier accès à l'écran EPG : les deux beginReplace() se marchent
        // dessus, l'un vide la table pendant que l'autre la relit, ce qui fait réapparaître le
        // spinner de chargement après un premier affichage réussi.
        private val epgSyncMutexes = ConcurrentHashMap<String, Mutex>()

        // computeIfAbsent (pas l'extension Kotlin getOrPut, qui fait un get()+put() non atomique
        // et peut donc créer deux Mutex distincts pour le même profil sous contention, ce qui
        // annulerait la garantie visée ici) : ConcurrentHashMap.computeIfAbsent garantit qu'un
        // seul Mutex est créé et vu par tous les appelants pour un profileId donné.
        private fun epgSyncMutexFor(profileId: String): Mutex =
            epgSyncMutexes.computeIfAbsent(profileId) { Mutex() }
    }
}

data class CatalogPresentation(
    val catalog: Catalog,
    val library: UserLibrarySnapshot,
    val profiles: List<PlaylistProfile>,
)

data class LoadedCatalog(
    val catalog: Catalog,
    val credentials: ServerCredentials,
    val source: CatalogSource,
    val profileId: String,
    val importSummary: String? = null,
)

enum class CatalogSource { Network, Cache, Local, Import }

private val titleTokenizer = MetadataSimilarityEngine()
private val YEAR_IN_NAME = Regex("\\b(19|20)\\d{2}\\b")
private const val JUSTWATCH_TTL_MS = 6 * 60 * 60 * 1000L
private const val TMDB_RELATED_LIMIT = 20
private const val TMDB_SEARCH_LIMIT = 20
private const val TMDB_TOP_SCORE = 0.70
private const val TMDB_RANK_STEP = 0.01
private const val TMDB_MAX_FAILURES = 5
private const val JUSTWATCH_SEARCH_LIMIT = 30

/** Titre normalisé sans année, qualité ni préfixe de langue : « FR - Dune (2021) 4K » → « dune ». */
internal fun titleKey(name: String): String = titleTokenizer.titleTokens(name).joinToString(" ")

/** Même titre (français ou original) et, si le nom IPTV porte une année, à un an près. */
internal fun matchesTrending(entry: MediaEntry, title: TrendingTitle): Boolean {
    val key = titleKey(entry.displayName)
    if (key.isEmpty() || (key != titleKey(title.title) && key != title.originalTitle?.let(::titleKey))) return false
    val year = YEAR_IN_NAME.findAll(entry.displayName).lastOrNull()?.value?.toInt() ?: return true
    return title.year == null || kotlin.math.abs(year - title.year) <= 1
}

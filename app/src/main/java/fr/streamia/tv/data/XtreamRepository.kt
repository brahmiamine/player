package fr.streamia.tv.data

import fr.streamia.tv.net.HttpClients
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import fr.streamia.tv.domain.AccountInfo
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgChannel
import fr.streamia.tv.domain.EpgGuide
import fr.streamia.tv.domain.EpgNowContext
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaDetails
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.SeriesDetails
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.XtreamUrlBuilder
import fr.streamia.tv.recommendation.ContentFeatures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Issue d'une demande d'installation de mise à jour (voir [AppUpdateRepository.installDownloadedUpdate]). */
enum class UpdateInstallStart { Started, PermissionRequested, PermissionStillMissing, BlockedByAdmin }

/** Rangée JustWatch lue sur disque ; [fresh] faux quand elle doit être recalculée. */
data class CachedJustWatchRow(val entries: List<MediaEntry>, val fresh: Boolean)

class XtreamRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val client = XtreamClient()
    private val cache = CatalogCache(context)
    private val epgCache = EpgCache(context)
    private val credentialsStore = CredentialsStore(context)
    private val playlistStore = PlaylistStore(context)
    private val libraryStore = UserLibraryStore(context)
    private val appSettingsStore = AppSettingsStore(context)
    private val aiKeyStore = AiKeyStore(context)
    private val recommendationStore = RecommendationStore(context)
    private val xmlTvRepository = XmlTvRepository()
    private val epgValidators = EpgHttpValidatorsStore(context)
    private val m3uParser = M3uParser()
    private val backupManager = BackupManager(context)

    /** Mises à jour de l'application : vérification, téléchargement et installation de l'APK. */
    val updates = AppUpdateRepository(appContext)

    /** Contenus similaires : index enrichi, liens TMDB, sagas Wikidata et voisins MovieLens. */
    val similarity = SimilarityRepository(context, cache, recommendationStore)

    /** Guides tiers de l'accueil (matchs du jour, programmes FR, beIN, UK) et leurs caches disque. */
    val guides = HomeGuidesRepository(context, cache, playlistStore, epgCache)

    /** Rangées « tendances » JustWatch rapprochées de la playlist. */
    val trending = TrendingRepository(appContext, cache)

    fun profiles(): List<PlaylistProfile> = playlistStore.loadAll()
    fun profile(profileId: String): PlaylistProfile? = playlistStore.find(profileId)
    fun library(profileId: String): UserLibrarySnapshot = libraryStore.snapshot(profileId)
    fun appSettings(): AppSettings = appSettingsStore.load()
    /** Fonctions IA (traduction, similaires) : inactives tant que [syncAi] n'a pas validé les réglages. */
    val ai = AiAssistant(appContext, aiKeyStore, AiUsageStore(appContext))
    fun hasAiKey(provider: AiProvider): Boolean = aiKeyStore.has(provider)
    fun saveAiKey(provider: AiProvider, key: String) {
        aiKeyStore.set(provider, key)
        ai.sync(appSettingsStore.load())
    }
    /** Recalcule si l'assistant IA est actif (interrupteur, clé, modèle) ; à appeler après chaque changement. */
    fun syncAi(settings: AppSettings = appSettingsStore.load()) = ai.sync(settings)
    suspend fun aiModels(provider: AiProvider): Result<List<String>> =
        AiModelsClient.list(provider, aiKeyStore.get(provider).orEmpty())
    fun updateAppSettings(transform: (AppSettings) -> AppSettings): AppSettings =
        appSettingsStore.update(transform).also { syncAi(it) }
    fun customizedCatalog(profileId: String, catalog: Catalog): Catalog = libraryStore.applyToCatalog(profileId, catalog)

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
        afterKey: String? = null,
    ): CatalogPage = cache.loadCategoryPage(profileId, type, categoryId, offset, limit, order, afterKey)

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
        val stored = recommendationStore.featuresFor(profileId, entries.map(MediaEntry::key))
        entries.associate { entry -> entry.key to (stored[entry.key]?.merge(entry) ?: ContentFeatures.from(entry)) }
    }

    /** Enrichit le moteur de recommandation dès qu'une fiche film/série est réellement consultée. */
    suspend fun cacheRecommendationDetails(profileId: String, details: MediaDetails) =
        withContext(Dispatchers.IO) { recommendationStore.saveDetails(profileId, details) }

    /**
     * Recherche indexée en base plutôt que dans le sous-ensemble matérialisé en mémoire : sous
     * chargement paresseux, un film ou une série jamais parcouru par l'utilisateur n'existe pas
     * encore dans `Catalog.entries`, donc [fr.streamia.tv.domain.Catalog.search] le raterait.
     */
    suspend fun search(profileId: String, query: String, type: MediaType?, limit: Int = 600): List<MediaEntry> =
        cache.search(profileId, query, type, limit)

    /** Contenus de catégories choisies (voir [CatalogDatabase.searchInCategories]), pour la recherche en langage naturel. */
    suspend fun searchInCategories(
        profileId: String,
        categories: Map<MediaType, Set<String>>,
        sort: SearchSort,
        limit: Int = 600,
    ): List<MediaEntry> = cache.searchInCategories(profileId, categories, sort, limit)

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

    suspend fun refreshProfile(profileId: String): LoadedCatalog = withContext(BackgroundWork.dispatcher) {
        val profile = playlistStore.find(profileId) ?: throw XtreamException("Cette liste n'existe plus.")
        when (profile.kind) {
            PlaylistKind.Xtream -> {
                val credentials = profile.credentialsOrNull() ?: throw XtreamException("Identifiants Xtream incomplets.")
                // Actualisation d'un catalogue déjà affiché : basse priorité, l'interface reste fluide.
                val catalog = fetchAndStoreXtreamCatalog(profileId, credentials, BackgroundWork.dispatcher)
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
        guides.clearLiveOnSatResolution(profileId)
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

    /** Description d'un programme du guide (horaires déjà décalés de [offsetHours]). */
    suspend fun epgDescription(profileId: String, program: EpgProgram, offsetHours: Int): String? {
        val channelId = program.channelId ?: return null
        val start = program.startEpochSeconds ?: return null
        return epgCache.description(profileId, channelId, start - offsetHours * 3_600L)
    }

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
    ): Boolean = withContext(BackgroundWork.dispatcher) {
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

            val job = coroutineContext.job
            var lastError: Throwable? = null
            // Guide déjà en base pour cette même source : requête conditionnelle (ETag / date).
            val current = epgCache.metadataOnIo(profileId)
            for (source in sources) {
                val validators = if (!force && current != null && current.programCount > 0 && current.sourceUrl == source) {
                    epgValidators.load(profileId, source)
                } else {
                    null
                }
                val session = epgCache.beginReplaceOnIo(profileId)
                try {
                    val outcome = xmlTvRepository.syncOnIo(source, liveEntries, session.cancellableBy(job), validators)
                    job.ensureActive()
                    if (outcome is XmlTvSyncOutcome.NotModified) {
                        // Rien de nouveau : l'ancien guide est gardé tel quel (transaction annulée).
                        epgCache.abortReplaceOnIo(session)
                        epgCache.touchOnIo(profileId)
                        return@withLock false
                    }
                    if (session.writtenProgramCount <= 0) {
                        throw XtreamException("La source EPG ne contient aucun programme exploitable correspondant aux chaînes.")
                    }
                    epgCache.commitReplaceOnIo(session, source)
                    (outcome as? XmlTvSyncOutcome.Written)?.validators?.let { epgValidators.save(profileId, source, it) }
                    return@withLock true
                } catch (error: Throwable) {
                    epgCache.abortReplaceOnIo(session)
                    // Liste quittée : ne pas enchaîner sur la source suivante (plusieurs minutes de
                    // téléchargement et d'écriture en base pour une liste qui n'est plus affichée).
                    if (error is CancellationException) throw error
                    lastError = error
                }
            }
            throw lastError ?: XtreamException("Aucune source EPG disponible.")
        }
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
     *
     * [dispatcher] : priorité normale quand l'utilisateur attend le catalogue (connexion, première
     * ouverture sans cache). Les fils [BackgroundWork] (priorité « arrière-plan ») ne reçoivent sur
     * TV qu'une petite part du processeur dès que l'écran de chargement s'anime : la connexion
     * durait alors plus de dix minutes.
     */
    private suspend fun fetchAndStoreXtreamCatalog(
        profileId: String,
        credentials: ServerCredentials,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Catalog =
        withContext(dispatcher) {
            val job = coroutineContext.job
            val session = cache.beginReplaceOnIo(profileId)
            val result = try {
                client.loadCatalogOnIo(credentials, session.cancellableBy(job)).also {
                    // Les sections Films/Séries avalent leurs erreurs (runCatching) : une annulation
                    // survenue pendant leur lecture doit tout de même empêcher de valider un
                    // catalogue partiel.
                    job.ensureActive()
                }
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
        val full = imported.catalog
        val summary = buildString {
            append("${imported.parsedEntries} médias")
            append(" · ${full.count(MediaType.Live)} chaînes")
            append(" · ${full.count(MediaType.Movie)} films")
            append(" · ${full.count(MediaType.Series)} séries")
            append(" · ${full.categories.size} catégories")
            if ("tvg-logo" in imported.detectedAttributes) append(" · logos")
            if ("tvg-id" in imported.detectedAttributes) append(" · EPG/TVG")
            if (imported.skippedEntries > 0) append(" · ${imported.skippedEntries} ignorés")
        }
        // Version légère relue depuis la base, comme pour Xtream : publier le catalogue complet
        // gardait des centaines de milliers d'entrées en mémoire, non paginées, jusqu'au redémarrage.
        val catalog = cache.load(id) ?: full
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
        HttpClients.execute(url, mapOf("Accept" to "application/x-mpegURL,text/plain,*/*"), connectTimeoutMs = 15_000, readTimeoutMs = 90_000).use { response ->
            val code = response.code
            if (!response.isSuccessful) throw XtreamException("Le serveur M3U a répondu avec le code $code.")
            val body = response.body ?: throw IOException("Réponse vide.")
            body.byteStream().use { input -> target.outputStream().buffered().use { output -> input.copyTo(output, 128 * 1024) } }
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
        @Volatile private var instance: XtreamRepository? = null

        /**
         * Une seule instance par processus, partagée par l'interface et les tâches planifiées
         * (EPG, enrichissement) : mêmes caches (profils, bibliothèque, index de recommandations)
         * et mêmes connexions SQLite. Une instance par activité et par passage de tâche ouvrait
         * les bases en double, avec des caches distincts et des écritures concurrentes.
         */
        fun get(context: Context): XtreamRepository = instance ?: synchronized(this) {
            instance ?: XtreamRepository(context.applicationContext).also { instance = it }
        }

        const val DEFAULT_CATEGORY_PAGE_SIZE = 500

        // Un verrou par profil : le worker EPG en arrière-plan (EpgSyncWorker) et le ViewModel
        // partagent l'instance unique ([get]) et visent le même fichier SQLite. Sans ce verrou, un
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

/**
 * Lot écrit seulement si la coroutine appelante est encore active. Le parsing est synchrone (la
 * transaction SQLite doit rester sur un seul thread) et ne passe par aucun point de suspension :
 * sans ce contrôle, annuler l'ouverture d'une liste laissait son téléchargement aller au bout.
 * L'exception levée ici remonte sur le même thread jusqu'à l'annulation de la transaction.
 */
internal fun CatalogWriteSink.cancellableBy(job: Job): CatalogWriteSink {
    val sink = this
    return object : CatalogWriteSink {
        override fun writeCategories(categories: List<MediaCategory>) {
            job.ensureActive()
            sink.writeCategories(categories)
        }

        override fun writeEntries(entries: List<MediaEntry>) {
            job.ensureActive()
            sink.writeEntries(entries)
        }
    }
}

internal fun EpgWriteSink.cancellableBy(job: Job): EpgWriteSink {
    val sink = this
    return object : EpgWriteSink {
        override fun writeChannel(channel: EpgChannel) {
            job.ensureActive()
            sink.writeChannel(channel)
        }

        override fun writePrograms(programs: List<EpgProgram>) {
            job.ensureActive()
            sink.writePrograms(programs)
        }
    }
}

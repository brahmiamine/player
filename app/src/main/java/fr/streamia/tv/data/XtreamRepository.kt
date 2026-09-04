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
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.SeriesDetails
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.XtreamUrlBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class XtreamRepository(context: Context) {
    private val appContext = context.applicationContext
    private val client = XtreamClient()
    private val cache = CatalogCache(context)
    private val epgCache = EpgCache(context)
    private val credentialsStore = CredentialsStore(context)
    private val playlistStore = PlaylistStore(context)
    private val libraryStore = UserLibraryStore(context)
    private val appSettingsStore = AppSettingsStore(context)
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

    suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult =
        withContext(Dispatchers.IO) { updateChecker.checkForUpdate(currentVersion) }

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
        limit: Int = DEFAULT_CATEGORY_PAGE_SIZE,
    ): CatalogPage = cache.loadCategoryPage(profileId, type, categoryId, offset, limit)

    /**
     * Charge tout un type (ex. Live) en une requête. Réservé aux sections qu'on choisit d'hydrater
     * entièrement — le Live proactivement au démarrage, ou Films/Séries à l'ouverture de
     * l'organisateur qui a besoin des listes complètes pour réordonner/déplacer des entrées.
     */
    suspend fun loadSection(profileId: String, type: MediaType): List<MediaEntry> = cache.loadType(profileId, type)

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

    /** Xtream est permanent en cache : seul le bouton Actualiser contacte de nouveau le fournisseur. */
    fun shouldRefreshProfile(profileId: String): Boolean {
        val profile = playlistStore.find(profileId) ?: return false
        return when (profile.kind) {
            PlaylistKind.Xtream -> profile.shouldAutoRefresh()
            PlaylistKind.M3u -> profile.shouldAutoRefresh()
        }
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
            val source = if (profile.kind == PlaylistKind.M3u && shouldRefreshProfile(profile.id)) {
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
    }

    /** Contrôle léger des identifiants (sans charger le catalogue), utilisé avant l'enregistrement. */
    suspend fun testConnection(credentials: ServerCredentials): AccountInfo = client.testConnection(credentials)

    suspend fun movieDetails(credentials: ServerCredentials, movie: MediaEntry): MediaDetails =
        client.loadMovieDetails(credentials, movie)

    suspend fun seriesDetails(credentials: ServerCredentials, series: MediaEntry): SeriesDetails =
        client.loadSeriesDetails(credentials, series)

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
    suspend fun refreshEpg(
        profileId: String,
        credentials: ServerCredentials,
        liveEntries: List<MediaEntry>,
        force: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        if (liveEntries.isEmpty()) return@withContext false
        epgSyncMutexFor(profileId).withLock {
            val profile = playlistStore.find(profileId)
            val maxAgeMillis = (profile?.autoRefreshHours ?: 6).coerceIn(1, 168) * 3_600_000L
            val nowMillis = System.currentTimeMillis()
            if (!force && epgCache.metadataOnIo(profileId)?.isFreshAt(nowMillis, maxAgeMillis) == true) {
                return@withLock false
            }

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
        return if (value.contains("://")) value else "http://$value"
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

        // Partagé par toutes les instances de XtreamRepository du process : le worker EPG en
        // arrière-plan (EpgSyncWorker) et le ViewModel créent chacun leur propre instance, mais
        // toutes deux visent le même fichier SQLite pour un profil donné. Sans ce verrou, un
        // premier passage du worker au tout premier lancement de l'app (WorkManager exécute une
        // périodique sans délai initial dès que le réseau est disponible) peut chevaucher la
        // synchronisation au premier accès à l'écran EPG : les deux beginReplace() se marchent
        // dessus, l'un vide la table pendant que l'autre la relit, ce qui fait réapparaître le
        // spinner de chargement après un premier affichage réussi.
        private val epgSyncMutexes = ConcurrentHashMap<String, Mutex>()

        private fun epgSyncMutexFor(profileId: String): Mutex =
            epgSyncMutexes.getOrPut(profileId) { Mutex() }
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

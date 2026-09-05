package fr.streamia.tv.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import fr.streamia.tv.domain.AccountInfo
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Indexed provider catalogue cache.
 *
 * New installs and successful refreshes are stored in SQLite. Existing v4 JSON files are migrated
 * once, off the main thread, so an upgrade keeps the user's last valid provider catalogue without
 * reconnecting to Xtream.
 */
class CatalogCache(context: Context) {
    private val appContext = context.applicationContext
    private val filesDir = appContext.filesDir
    private val database = CatalogDatabase(appContext)
    private val libraryStore = UserLibraryStore(appContext)
    private val playlistStore = PlaylistStore(appContext)
    private val legacyFiles = listOf(File(filesDir, "catalog-v2.json"), File(filesDir, "catalog-v1.json"))

    fun databaseFileSizeBytes(): Long = database.fileSizeBytes()

    suspend fun save(profileId: String, catalog: Catalog) = withContext(Dispatchers.IO) {
        database.replace(profileId, catalog)
        deleteJsonCopies(profileId)
        legacyFiles.forEach(File::delete)
    }

    /**
     * Démarre un remplacement transactionnel du catalogue d'un profil sans exiger que l'appelant
     * ait déjà tout en mémoire : [XtreamClient.loadCatalogOnIo] écrit les catégories puis les
     * entrées par lots au fil du parsing réseau via la session renvoyée. Rien n'est visible pour
     * les lecteurs tant que [commitReplaceOnIo] n'a pas été appelé.
     *
     * Volontairement non-`suspend` : [XtreamRepository.fetchAndStoreXtreamCatalog] appelle
     * begin/commit/abort comme de simples fonctions synchrones à l'intérieur d'un seul
     * `withContext(Dispatchers.IO)`, pour que toute la séquence reste garantie sur un seul thread
     * (exigence d'une transaction SQLite Android) sans dépendre d'un comportement d'optimisation de
     * `withContext` ni risquer qu'une annulation de coroutine interrompe `commit`/`abort` en cours
     * de route.
     */
    internal fun beginReplaceOnIo(profileId: String): CatalogDatabase.ReplaceSession = database.beginReplace(profileId)

    /** Valide la session : le nouveau catalogue devient visible et l'ancien cache JSON éventuel est purgé. */
    internal fun commitReplaceOnIo(session: CatalogDatabase.ReplaceSession, account: AccountInfo?) {
        session.commit(account)
        deleteJsonCopies(session.profileId)
        legacyFiles.forEach(File::delete)
    }

    /** Annule la session : tout ce qui a été écrit est retiré, l'ancien catalogue valide reste en place. */
    internal fun abortReplaceOnIo(session: CatalogDatabase.ReplaceSession) {
        session.abort()
    }

    /**
     * The user layout already lives as lightweight deltas in [UserLibraryStore]. Keeping a second
     * resolved copy of every provider row doubled disk usage and could force a second full parse on
     * startup, so resolved catalogue snapshots are intentionally no longer persisted.
     */
    suspend fun saveResolved(profileId: String, catalog: Catalog, layoutFingerprint: String) =
        withContext(Dispatchers.IO) {
            // Keep parameters in the API while callers migrate; deleting the old file is the work.
            @Suppress("UNUSED_VARIABLE") val ignoredCatalog = catalog
            @Suppress("UNUSED_VARIABLE") val ignoredFingerprint = layoutFingerprint
            resolvedFileFor(profileId).delete()
        }

    suspend fun loadResolved(profileId: String, expectedLayoutFingerprint: String): Catalog? =
        withContext(Dispatchers.IO) {
            @Suppress("UNUSED_VARIABLE") val ignoredFingerprint = expectedLayoutFingerprint
            resolvedFileFor(profileId).delete()
            null
        }

    suspend fun load(profileId: String, extraEntryKeys: Set<String> = emptySet()): Catalog? =
        withContext(Dispatchers.IO) {
            ensureMigrated(profileId)
            val contextKeys = persistedContextKeys(profileId) + extraEntryKeys
            database.loadLightweight(profileId, contextKeys)
        }

    suspend fun loadFull(profileId: String): Catalog? = withContext(Dispatchers.IO) {
        ensureMigrated(profileId)
        database.loadFull(profileId)
    }

    suspend fun loadCategoryPage(
        profileId: String,
        type: MediaType,
        categoryId: String,
        offset: Int,
        limit: Int,
    ): CatalogPage = withContext(Dispatchers.IO) {
        ensureMigrated(profileId)
        if (limit <= 0) return@withContext CatalogPage(emptyList(), offset.coerceAtLeast(0), false)
        val boundedLimit = limit.coerceAtMost(MAX_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        val entries = database.loadCategoryPage(profileId, type, categoryId, safeOffset, boundedLimit)
        CatalogPage(
            entries = entries,
            nextOffset = safeOffset + entries.size,
            hasMore = entries.size == boundedLimit,
        )
    }

    suspend fun loadAdjacent(
        profileId: String,
        current: MediaEntry,
        categoryId: String,
        delta: Int,
    ): MediaEntry? = withContext(Dispatchers.IO) {
        ensureMigrated(profileId)
        database.loadAdjacent(profileId, current, categoryId, delta)
    }

    suspend fun loadType(profileId: String, type: MediaType): List<MediaEntry> = withContext(Dispatchers.IO) {
        ensureMigrated(profileId)
        database.loadType(profileId, type)
    }

    /**
     * Pool borné pour l'IA : lit seulement les contenus VOD récents via l'index SQLite existant.
     * On évite ainsi de matérialiser des dizaines de milliers de films/séries dans le ViewModel.
     */
    suspend fun loadRecommendationCandidates(
        profileId: String,
        type: MediaType,
        limit: Int,
    ): List<MediaEntry> = withContext(Dispatchers.IO) {
        ensureMigrated(profileId)
        database.loadRecommendationCandidates(profileId, type, limit)
    }

    suspend fun search(
        profileId: String,
        query: String,
        type: MediaType? = null,
        limit: Int = 500,
    ): List<MediaEntry> = withContext(Dispatchers.IO) {
        ensureMigrated(profileId)
        val moves = libraryStore.snapshot(profileId).movedEntries
        database.search(profileId, query, type, limit).map { entry ->
            moves[entry.key]?.let { destination -> entry.copy(categoryId = destination) } ?: entry
        }
    }

    suspend fun loadEntriesByKeys(profileId: String, keys: Set<String>): List<MediaEntry> = withContext(Dispatchers.IO) {
        ensureMigrated(profileId)
        database.loadEntriesByKeys(profileId, keys)
    }

    suspend fun clear(profileId: String) = withContext(Dispatchers.IO) {
        database.delete(profileId)
        deleteJsonCopies(profileId)
    }

    private fun persistedContextKeys(profileId: String): Set<String> {
        val library = libraryStore.snapshot(profileId)
        return buildSet {
            addAll(library.favoriteEntries)
            addAll(library.movedEntries.keys)
            library.history.forEach { add(it.entry.key) }
            playlistStore.find(profileId)?.credentialsOrNull()?.let { credentials ->
                val navigation = BrowserNavigationStore(appContext, credentials)
                MediaType.entries.forEach { type -> navigation.entry(type)?.let(::add) }
            }
        }
    }

    private fun ensureMigrated(profileId: String) {
        if (database.hasProfile(profileId)) return
        val source = fileFor(profileId)
        if (!source.exists()) return
        val legacy = readLegacyCatalog(source) ?: return
        database.replace(profileId, legacy)
        deleteJsonCopies(profileId)
        legacyFiles.forEach(File::delete)
    }

    private fun readLegacyCatalog(file: File): Catalog? = runCatching {
        val categories = ArrayList<MediaCategory>()
        val entries = ArrayList<MediaEntry>()
        file.inputStream().reader(StandardCharsets.UTF_8).buffered().use { input ->
            JsonReader(input).use { json ->
                json.beginObject()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "categories" -> readCategories(json, categories)
                        "entries" -> readEntries(json, entries)
                        else -> json.skipValue()
                    }
                }
                json.endObject()
            }
        }
        Catalog(categories, entries)
    }.getOrNull()

    private fun deleteJsonCopies(profileId: String) {
        fileFor(profileId).delete()
        resolvedFileFor(profileId).delete()
    }

    private fun fileFor(profileId: String): File {
        val safeId = profileId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(filesDir, "catalog-v4-$safeId.json")
    }

    private fun resolvedFileFor(profileId: String): File {
        val safeId = profileId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(filesDir, "catalog-v4-resolved-$safeId.json")
    }

    private fun readCategories(json: JsonReader, target: MutableList<MediaCategory>) {
        json.beginArray()
        while (json.hasNext()) {
            var id = ""
            var name = ""
            var type = MediaType.Live
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "id" -> id = json.nextString()
                    "name" -> name = json.nextString()
                    "type" -> type = json.nextString().toMediaType()
                    else -> json.skipValue()
                }
            }
            json.endObject()
            if (id.isNotBlank() && name.isNotBlank()) target += MediaCategory(id, name, type)
        }
        json.endArray()
    }

    private fun readEntries(json: JsonReader, target: MutableList<MediaEntry>) {
        json.beginArray()
        while (json.hasNext()) {
            var id = -1
            var name = ""
            var displayName = ""
            var type = MediaType.Live
            var categoryId = "0"
            var icon: String? = null
            var number = target.size + 1
            var extension = ""
            var tvgId: String? = null
            var plot: String? = null
            var rating: Double? = null
            var playable: Boolean? = null
            var addedAt: Long? = null
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "id" -> id = json.nextInt()
                    "name" -> name = json.nextString()
                    "display_name" -> displayName = json.nextString()
                    "type" -> type = json.nextString().toMediaType()
                    "category_id" -> categoryId = json.nextString()
                    "icon" -> icon = json.nextNullableString()
                    "number" -> number = json.nextInt()
                    "extension" -> extension = json.nextString()
                    "tvg_id" -> tvgId = json.nextNullableString()
                    "plot" -> plot = json.nextNullableString()
                    "rating" -> rating = json.nextNullableDouble()
                    "playable" -> playable = json.nextBoolean()
                    "added_at" -> addedAt = json.nextNullableLong()
                    else -> json.skipValue()
                }
            }
            json.endObject()
            if (id > 0 && name.isNotBlank()) {
                target += MediaEntry(
                    id = id,
                    name = name,
                    displayName = displayName.ifBlank { name },
                    type = type,
                    categoryId = categoryId,
                    iconUrl = icon,
                    number = number,
                    extension = extension.ifBlank { type.defaultExtension },
                    tvgId = tvgId,
                    plot = plot,
                    rating = rating,
                    playable = playable ?: (type != MediaType.Series),
                    addedAtEpochSeconds = addedAt,
                )
            }
        }
        json.endArray()
    }

    private fun String.toMediaType(): MediaType = MediaType.entries.firstOrNull { it.name == this } ?: MediaType.Live
    private fun JsonReader.nextNullableString(): String? = if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()
    private fun JsonReader.nextNullableDouble(): Double? = if (peek() == JsonToken.NULL) { nextNull(); null } else nextDouble()
    private fun JsonReader.nextNullableLong(): Long? = if (peek() == JsonToken.NULL) { nextNull(); null } else nextLong()

    private companion object {
        const val MAX_PAGE_SIZE = 500
    }
}
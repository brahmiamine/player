package fr.streamia.tv.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
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
    private val legacyFiles = listOf(File(filesDir, "catalog-v2.json"), File(filesDir, "catalog-v1.json"))

    suspend fun save(profileId: String, catalog: Catalog) = withContext(Dispatchers.IO) {
        database.replace(profileId, catalog)
        deleteJsonCopies(profileId)
        legacyFiles.forEach(File::delete)
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
            database.loadLightweight(profileId, extraEntryKeys)
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
    ): List<MediaEntry> = withContext(Dispatchers.IO) {
        ensureMigrated(profileId)
        database.loadCategoryPage(profileId, type, categoryId, offset, limit)
    }

    suspend fun loadType(profileId: String, type: MediaType): List<MediaEntry> = withContext(Dispatchers.IO) {
        ensureMigrated(profileId)
        database.loadType(profileId, type)
    }

    suspend fun search(
        profileId: String,
        query: String,
        type: MediaType? = null,
        limit: Int = 500,
    ): List<MediaEntry> = withContext(Dispatchers.IO) {
        ensureMigrated(profileId)
        database.search(profileId, query, type, limit)
    }

    suspend fun loadEntriesByKeys(profileId: String, keys: Set<String>): List<MediaEntry> = withContext(Dispatchers.IO) {
        ensureMigrated(profileId)
        database.loadEntriesByKeys(profileId, keys)
    }

    suspend fun clear(profileId: String) = withContext(Dispatchers.IO) {
        database.delete(profileId)
        deleteJsonCopies(profileId)
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
}

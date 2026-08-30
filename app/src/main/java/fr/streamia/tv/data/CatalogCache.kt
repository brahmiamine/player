package fr.streamia.tv.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/** Cache JSON écrit et lu en flux pour éviter de dupliquer un catalogue massif en mémoire. */
class CatalogCache(context: Context) {
    private val filesDir = context.filesDir
    private val legacyFiles = listOf(
        File(filesDir, "catalog-v2.json"),
        File(filesDir, "catalog-v1.json"),
    )

    suspend fun save(profileId: String, catalog: Catalog) = withContext(Dispatchers.IO) {
        val file = fileFor(profileId)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().writer(StandardCharsets.UTF_8).buffered().use { output ->
            JsonWriter(output).use { json ->
                json.beginObject()
                json.name("saved_at").value(System.currentTimeMillis())
                json.name("categories").beginArray()
                for (category in catalog.categories) {
                    json.beginObject()
                    json.name("id").value(category.id)
                    json.name("name").value(category.name)
                    json.name("type").value(category.type.name)
                    json.endObject()
                }
                json.endArray()
                json.name("entries").beginArray()
                for (entry in catalog.entries) {
                    json.beginObject()
                    json.name("id").value(entry.id.toLong())
                    json.name("name").value(entry.name)
                    json.name("display_name").value(entry.displayName)
                    json.name("type").value(entry.type.name)
                    json.name("category_id").value(entry.categoryId)
                    json.name("icon").nullableValue(entry.iconUrl)
                    json.name("number").value(entry.number.toLong())
                    json.name("extension").value(entry.extension)
                    json.name("tvg_id").nullableValue(entry.tvgId)
                    json.name("plot").nullableValue(entry.plot)
                    json.name("rating").nullableValue(entry.rating)
                    json.name("playable").value(entry.playable)
                    json.endObject()
                }
                json.endArray()
                json.endObject()
            }
        }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
        legacyFiles.forEach(File::delete)
    }

    suspend fun load(profileId: String): Catalog? = withContext(Dispatchers.IO) {
        val file = fileFor(profileId)
        if (!file.exists()) return@withContext null
        runCatching {
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
    }

    suspend fun clear(profileId: String) = withContext(Dispatchers.IO) {
        fileFor(profileId).delete()
    }

    private fun fileFor(profileId: String): File {
        val safeId = profileId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(filesDir, "catalog-v3-$safeId.json")
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
                )
            }
        }
        json.endArray()
    }

    private fun String.toMediaType(): MediaType =
        MediaType.entries.firstOrNull { it.name == this } ?: MediaType.Live

    private fun JsonWriter.nullableValue(value: String?) {
        if (value == null) nullValue() else value(value)
    }

    private fun JsonWriter.nullableValue(value: Double?) {
        if (value == null) nullValue() else value(value)
    }

    private fun JsonReader.nextNullableString(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()

    private fun JsonReader.nextNullableDouble(): Double? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextDouble()
}

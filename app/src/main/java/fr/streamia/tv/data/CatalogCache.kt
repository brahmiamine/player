package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.LiveCategory
import fr.streamia.tv.domain.LiveChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CatalogCache(context: Context) {
    private val file = File(context.filesDir, "catalog-v1.json")

    suspend fun save(catalog: Catalog) = withContext(Dispatchers.IO) {
        val root = JSONObject().apply {
            put("saved_at", System.currentTimeMillis())
            put("categories", JSONArray().apply {
                catalog.categories.forEach { category ->
                    put(JSONObject().apply {
                        put("id", category.id)
                        put("name", category.name)
                    })
                }
            })
            put("channels", JSONArray().apply {
                catalog.channels.forEach { channel ->
                    put(JSONObject().apply {
                        put("id", channel.id)
                        put("name", channel.name)
                        put("category_id", channel.categoryId)
                        put("icon", channel.iconUrl)
                        put("number", channel.number)
                        put("epg_id", channel.epgChannelId)
                    })
                }
            })
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(root.toString())
        if (!temporary.renameTo(file)) {
            file.writeText(root.toString())
            temporary.delete()
        }
    }

    suspend fun load(): Catalog? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching {
            val root = JSONObject(file.readText())
            val categoriesJson = root.getJSONArray("categories")
            val channelsJson = root.getJSONArray("channels")
            val categories = buildList {
                for (index in 0 until categoriesJson.length()) {
                    val item = categoriesJson.getJSONObject(index)
                    add(LiveCategory(item.getString("id"), item.getString("name")))
                }
            }
            val channels = buildList {
                for (index in 0 until channelsJson.length()) {
                    val item = channelsJson.getJSONObject(index)
                    add(
                        LiveChannel(
                            id = item.getInt("id"),
                            name = item.getString("name"),
                            categoryId = item.getString("category_id"),
                            iconUrl = item.optionalString("icon"),
                            number = item.optInt("number", index + 1),
                            epgChannelId = item.optionalString("epg_id"),
                        ),
                    )
                }
            }
            Catalog(categories, channels)
        }.getOrNull()
    }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }

    private fun JSONObject.optionalString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
}

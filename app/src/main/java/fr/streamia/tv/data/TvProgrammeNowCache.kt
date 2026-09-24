package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.tvprogramme.TvProgrammeNowItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class CachedTvProgrammeNowData(
    val fetchedAtEpochMillis: Long,
    val programmes: List<TvProgrammeNowItem>,
)

internal class TvProgrammeNowCache(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun load(): CachedTvProgrammeNowData? = runCatching {
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("programmes")
        CachedTvProgrammeNowData(
            fetchedAtEpochMillis = root.getLong("fetchedAtEpochMillis"),
            programmes = (0 until array.length()).map { index -> array.getJSONObject(index).toProgramme() },
        )
    }.getOrNull()

    fun save(
        programmes: List<TvProgrammeNowItem>,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val root = JSONObject().apply {
            put("fetchedAtEpochMillis", fetchedAtEpochMillis)
            put("programmes", JSONArray(programmes.map { it.toJson() }))
        }
        file.writeText(root.toString())
    }

    private fun TvProgrammeNowItem.toJson(): JSONObject = JSONObject().apply {
        put("channelName", channelName)
        put("startEpochMillis", startEpochMillis)
        put("endEpochMillis", endEpochMillis)
        put("title", title)
        imageUrl?.let { put("imageUrl", it) }
    }

    private fun JSONObject.toProgramme() = TvProgrammeNowItem(
        channelName = getString("channelName"),
        startEpochMillis = getLong("startEpochMillis"),
        endEpochMillis = getLong("endEpochMillis"),
        title = getString("title"),
        imageUrl = optString("imageUrl").takeIf(String::isNotBlank),
    )

    private companion object {
        const val FILE_NAME = "tv-programme-now-cache-v3.json"
    }
}

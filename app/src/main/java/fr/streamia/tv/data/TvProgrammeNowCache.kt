package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.tvprogramme.TvProgrammeNowItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class CachedTvProgrammeNowData(
    val fetchedAtEpochMillis: Long,
    val localDate: String,
    val programmes: List<TvProgrammeNowItem>,
)

internal class TvProgrammeNowCache(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun load(): CachedTvProgrammeNowData? = runCatching {
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("programmes")
        CachedTvProgrammeNowData(
            fetchedAtEpochMillis = root.getLong("fetchedAtEpochMillis"),
            localDate = root.getString("localDate"),
            programmes = (0 until array.length()).map { index -> array.getJSONObject(index).toProgramme() },
        )
    }.getOrNull()

    fun save(
        programmes: List<TvProgrammeNowItem>,
        localDate: String,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val root = JSONObject().apply {
            put("fetchedAtEpochMillis", fetchedAtEpochMillis)
            put("localDate", localDate)
            put("programmes", JSONArray(programmes.map { it.toJson() }))
        }
        file.writeText(root.toString())
    }

    private fun TvProgrammeNowItem.toJson(): JSONObject = JSONObject().apply {
        put("channelName", channelName)
        put("startTime", startTime)
        endTime?.let { put("endTime", it) }
        put("title", title)
        imageUrl?.let { put("imageUrl", it) }
    }

    private fun JSONObject.toProgramme() = TvProgrammeNowItem(
        channelName = getString("channelName"),
        startTime = getString("startTime"),
        endTime = optString("endTime").takeIf(String::isNotBlank),
        title = getString("title"),
        imageUrl = optString("imageUrl").takeIf(String::isNotBlank),
    )

    private companion object {
        const val FILE_NAME = "tv-programme-now-cache-v1.json"
    }
}

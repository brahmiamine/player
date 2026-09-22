package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.tvprogramme.TvProgrammeItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class CachedTvProgrammeData(
    val fetchedAtEpochMillis: Long,
    val localDate: String,
    val programmes: List<TvProgrammeItem>,
)

internal class TvProgrammeCache(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun load(): CachedTvProgrammeData? = runCatching {
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("programmes")
        CachedTvProgrammeData(
            fetchedAtEpochMillis = root.getLong("fetchedAtEpochMillis"),
            localDate = root.getString("localDate"),
            programmes = (0 until array.length()).map { index -> array.getJSONObject(index).toProgramme() },
        )
    }.getOrNull()

    fun save(
        programmes: List<TvProgrammeItem>,
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

    private fun TvProgrammeItem.toJson(): JSONObject = JSONObject().apply {
        put("channelName", channelName)
        put("time", time)
        put("title", title)
        imageUrl?.let { put("imageUrl", it) }
    }

    private fun JSONObject.toProgramme() = TvProgrammeItem(
        channelName = getString("channelName"),
        time = getString("time"),
        title = getString("title"),
        imageUrl = optString("imageUrl").takeIf(String::isNotBlank),
    )

    private companion object {
        const val FILE_NAME = "tv-programme-cache-v1.json"
    }
}

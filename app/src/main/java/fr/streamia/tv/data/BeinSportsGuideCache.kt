package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.beinsports.BeinChannelSchedule
import fr.streamia.tv.beinsports.BeinProgrammeItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class CachedBeinGuideData(
    val fetchedAtEpochMillis: Long,
    val schedules: List<BeinChannelSchedule>,
)

internal class BeinSportsGuideCache(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun load(): CachedBeinGuideData? = runCatching {
        val root = JSONObject(file.readText())
        val channels = root.getJSONArray("channels")
        CachedBeinGuideData(
            fetchedAtEpochMillis = root.getLong("fetchedAtEpochMillis"),
            schedules = (0 until channels.length()).map { index ->
                channels.getJSONObject(index).toSchedule()
            },
        )
    }.getOrNull()

    fun save(
        schedules: List<BeinChannelSchedule>,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val root = JSONObject().apply {
            put("fetchedAtEpochMillis", fetchedAtEpochMillis)
            put("channels", JSONArray(schedules.map { it.toJson() }))
        }
        file.writeText(root.toString())
    }

    private fun BeinChannelSchedule.toJson(): JSONObject = JSONObject().apply {
        put("channelName", channelName)
        put("programmes", JSONArray(programmes.map { it.toJson() }))
    }

    private fun BeinProgrammeItem.toJson(): JSONObject = JSONObject().apply {
        put("channelName", channelName)
        category?.let { put("category", it) }
        put("title", title)
        put("startEpochMillis", startEpochMillis)
        put("endEpochMillis", endEpochMillis)
        put("isLive", isLive)
        imageUrl?.let { put("imageUrl", it) }
    }

    private fun JSONObject.toSchedule(): BeinChannelSchedule {
        val programmesJson = getJSONArray("programmes")
        return BeinChannelSchedule(
            channelName = getString("channelName"),
            programmes = (0 until programmesJson.length()).map { index ->
                programmesJson.getJSONObject(index).toProgramme()
            },
        )
    }

    private fun JSONObject.toProgramme(): BeinProgrammeItem = BeinProgrammeItem(
        channelName = getString("channelName"),
        category = optString("category").takeIf(String::isNotBlank),
        title = getString("title"),
        startEpochMillis = getLong("startEpochMillis"),
        endEpochMillis = getLong("endEpochMillis"),
        isLive = optBoolean("isLive", false),
        imageUrl = optString("imageUrl").takeIf(String::isNotBlank),
    )

    private companion object {
        const val FILE_NAME = "bein-sports-tv-guide-cache-v2.json"
    }
}

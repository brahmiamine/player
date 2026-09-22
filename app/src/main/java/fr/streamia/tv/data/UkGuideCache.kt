package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.ukguide.UkChannelSchedule
import fr.streamia.tv.ukguide.UkProgrammeItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class CachedUkGuideData(
    val fetchedAtEpochMillis: Long,
    val schedules: List<UkChannelSchedule>,
)

internal class UkGuideCache(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun load(): CachedUkGuideData? = runCatching {
        val root = JSONObject(file.readText())
        val channels = root.getJSONArray("channels")
        CachedUkGuideData(
            fetchedAtEpochMillis = root.getLong("fetchedAtEpochMillis"),
            schedules = (0 until channels.length()).map { index -> channels.getJSONObject(index).toSchedule() },
        )
    }.getOrNull()

    fun save(
        schedules: List<UkChannelSchedule>,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val root = JSONObject().apply {
            put("fetchedAtEpochMillis", fetchedAtEpochMillis)
            put("channels", JSONArray(schedules.map { it.toJson() }))
        }
        file.writeText(root.toString())
    }

    private fun UkChannelSchedule.toJson(): JSONObject = JSONObject().apply {
        put("channelName", channelName)
        put("programmes", JSONArray(programmes.map { it.toJson() }))
    }

    private fun UkProgrammeItem.toJson(): JSONObject = JSONObject().apply {
        put("channelName", channelName)
        put("startTime", startTime)
        put("endTime", endTime)
        put("title", title)
        imageUrl?.let { put("imageUrl", it) }
    }

    private fun JSONObject.toSchedule(): UkChannelSchedule {
        val programmesJson = getJSONArray("programmes")
        return UkChannelSchedule(
            channelName = getString("channelName"),
            programmes = (0 until programmesJson.length()).map { index ->
                programmesJson.getJSONObject(index).toProgramme()
            },
        )
    }

    private fun JSONObject.toProgramme(): UkProgrammeItem = UkProgrammeItem(
        channelName = getString("channelName"),
        startTime = getString("startTime"),
        endTime = getString("endTime"),
        title = getString("title"),
        imageUrl = optString("imageUrl").takeIf(String::isNotBlank),
    )

    private companion object {
        const val FILE_NAME = "uk-tv-guide-cache-v1.json"
    }
}

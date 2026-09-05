package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.liveonsat.LiveOnSatChannel
import fr.streamia.tv.liveonsat.LiveOnSatMatch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class CachedLiveOnSatData(
    val fetchedAtEpochMillis: Long,
    val matches: List<LiveOnSatMatch>,
)

/**
 * Cache disque du dernier scrape de liveonsat.com. Un simple fichier JSON plutôt qu'une base
 * SQLite : le volume (quelques centaines de matchs pour la journée) tient sans peine en mémoire et
 * ne nécessite aucune requête indexée — seule une lecture/écriture intégrale a lieu.
 */
internal class LiveOnSatCache(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun load(): CachedLiveOnSatData? = runCatching {
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("matches")
        val matches = (0 until array.length()).map { index -> array.getJSONObject(index).toMatch() }
        CachedLiveOnSatData(root.getLong("fetchedAtEpochMillis"), matches)
    }.getOrNull()

    fun save(matches: List<LiveOnSatMatch>, fetchedAtEpochMillis: Long = System.currentTimeMillis()) {
        val root = JSONObject().apply {
            put("fetchedAtEpochMillis", fetchedAtEpochMillis)
            put("matches", JSONArray(matches.map(LiveOnSatMatch::toJson)))
        }
        file.writeText(root.toString())
    }

    private fun LiveOnSatMatch.toJson(): JSONObject = JSONObject().apply {
        put("competition", competition)
        put("participantA", participantA)
        put("participantB", participantB)
        put("startEpochSeconds", startEpochSeconds)
        put(
            "channels",
            JSONArray(
                channels.map { channel ->
                    JSONObject().apply {
                        put("name", channel.name)
                        put("free", channel.free)
                    }
                },
            ),
        )
    }

    private fun JSONObject.toMatch(): LiveOnSatMatch {
        val channelsArray = getJSONArray("channels")
        val channels = (0 until channelsArray.length()).map { index ->
            val channelJson = channelsArray.getJSONObject(index)
            LiveOnSatChannel(name = channelJson.getString("name"), free = channelJson.optBoolean("free", false))
        }
        return LiveOnSatMatch(
            competition = getString("competition"),
            participantA = getString("participantA"),
            participantB = getString("participantB"),
            startEpochSeconds = getLong("startEpochSeconds"),
            channels = channels,
        )
    }

    private companion object {
        const val FILE_NAME = "liveonsat-cache-v1.json"
    }
}

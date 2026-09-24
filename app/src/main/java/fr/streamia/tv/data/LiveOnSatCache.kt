package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.liveonsat.LiveOnSatChannel
import fr.streamia.tv.liveonsat.LiveOnSatMatch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Chaînes du profil reconnues pour un match (clés d'entrées par diffuseur) et horaires EPG retenus. */
internal data class LiveOnSatResolution(
    val channelKeys: Map<String, List<String>>,
    val epgStartEpochSeconds: Long?,
    val epgEndEpochSeconds: Long?,
)

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
    private val dir = context.applicationContext.filesDir
    private val file = File(dir, FILE_NAME)

    fun load(): CachedLiveOnSatData? = runCatching {
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("matches")
        val matches = (0 until array.length()).map { index -> array.getJSONObject(index).toMatch() }
        CachedLiveOnSatData(root.getLong("fetchedAtEpochMillis"), matches)
    }.getOrNull()

    fun save(matches: List<LiveOnSatMatch>, fetchedAtEpochMillis: Long = System.currentTimeMillis()) {
        val root = JSONObject().apply {
            put("fetchedAtEpochMillis", fetchedAtEpochMillis)
            put("matches", JSONArray(matches.map { it.toJson() }))
        }
        file.writeText(root.toString())
    }

    /**
     * Rapprochement chaînes/EPG d'un profil, dans l'ordre des matchs du cache. Valable seulement pour
     * la même [version] (scrape liveonsat.com + actualisation de la playlist + synchronisation EPG) :
     * le moindre changement de l'une des trois l'invalide.
     */
    fun loadResolution(profileId: String, version: String): List<LiveOnSatResolution>? = runCatching {
        val root = JSONObject(resolutionFile(profileId).readText())
        if (root.getString("version") != version) return null
        val array = root.getJSONArray("matches")
        (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            val channels = json.getJSONObject("channels")
            LiveOnSatResolution(
                channelKeys = channels.keys().asSequence().associateWith { name ->
                    channels.getJSONArray(name).let { keys -> (0 until keys.length()).map(keys::getString) }
                },
                epgStartEpochSeconds = if (json.has("epgStart")) json.getLong("epgStart") else null,
                epgEndEpochSeconds = if (json.has("epgEnd")) json.getLong("epgEnd") else null,
            )
        }
    }.getOrNull()

    fun saveResolution(profileId: String, version: String, resolutions: List<LiveOnSatResolution>) {
        val root = JSONObject().apply {
            put("version", version)
            put(
                "matches",
                JSONArray(
                    resolutions.map { resolution ->
                        JSONObject().apply {
                            put("channels", JSONObject(resolution.channelKeys.mapValues { JSONArray(it.value) }))
                            resolution.epgStartEpochSeconds?.let { put("epgStart", it) }
                            resolution.epgEndEpochSeconds?.let { put("epgEnd", it) }
                        }
                    },
                ),
            )
        }
        resolutionFile(profileId).writeText(root.toString())
    }

    fun clearResolution(profileId: String) {
        resolutionFile(profileId).delete()
    }

    private fun resolutionFile(profileId: String) = File(dir, "liveonsat-resolved-v2-$profileId.json")

    private fun LiveOnSatMatch.toJson(): JSONObject = JSONObject().apply {
        put("competition", competition)
        put("participantA", participantA)
        put("participantB", participantB)
        participantALogoUrl?.let { put("participantALogoUrl", it) }
        participantBLogoUrl?.let { put("participantBLogoUrl", it) }
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
            participantALogoUrl = optString("participantALogoUrl").takeIf(String::isNotBlank),
            participantBLogoUrl = optString("participantBLogoUrl").takeIf(String::isNotBlank),
            startEpochSeconds = getLong("startEpochSeconds"),
            channels = channels,
        )
    }

    private companion object {
        const val FILE_NAME = "liveonsat-cache-v1.json"
    }
}

package fr.streamia.tv.data

import android.content.Context
import androidx.core.content.edit
import fr.streamia.tv.domain.LiveVersionStats
import fr.streamia.tv.domain.ServerCredentials
import java.net.URI
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Mesures réelles de chaque version de chaîne (résolution, débit, fps, coupures, échecs), gardées
 * entre les sessions pour classer les versions sur ce que le lecteur a vu, pas sur leur nom.
 *
 * Les clés sont préfixées par un condensat du compte (jamais les identifiants eux-mêmes) : deux
 * listes peuvent réutiliser les mêmes numéros de flux.
 */
class LiveVersionStatsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(scope: String, entryKeys: Collection<String>): Map<String, LiveVersionStats> =
        entryKeys.mapNotNull { key -> read(scope, key)?.let { key to it } }.toMap()

    fun recordSuccess(scope: String, entryKey: String, startupMs: Long?, nowMs: Long = System.currentTimeMillis()) =
        update(scope, entryKey, nowMs) {
            it.copy(
                startupMs = startupMs ?: it.startupMs,
                lastSuccessAtMs = nowMs,
                consecutiveFailures = 0,
            )
        }

    fun recordMeasurement(
        scope: String,
        entryKey: String,
        width: Int,
        height: Int,
        frameRate: Float?,
        codec: String?,
        bitrate: Int?,
        hdr: String?,
        nowMs: Long = System.currentTimeMillis(),
    ) = update(scope, entryKey, nowMs) {
        it.copy(
            width = width,
            height = height,
            frameRate = frameRate ?: it.frameRate,
            codec = codec ?: it.codec,
            bitrate = bitrate ?: it.bitrate,
            hdr = hdr ?: it.hdr,
            lastSuccessAtMs = nowMs,
            consecutiveFailures = 0,
        )
    }

    /** Temps regardé et coupures depuis le dernier relevé ; les anciennes valeurs s'estompent. */
    fun recordWatch(scope: String, entryKey: String, watchedMs: Long, rebuffers: Int, nowMs: Long = System.currentTimeMillis()) =
        update(scope, entryKey, nowMs) {
            var watched = it.watchedMs + watchedMs
            var count = it.rebufferCount + rebuffers
            if (watched > DECAY_AFTER_MS) {
                watched /= 2
                count /= 2
            }
            it.copy(watchedMs = watched, rebufferCount = count)
        }

    fun recordFailure(scope: String, entryKey: String, nowMs: Long = System.currentTimeMillis()) =
        update(scope, entryKey, nowMs) {
            it.copy(lastFailureAtMs = nowMs, consecutiveFailures = it.consecutiveFailures + 1)
        }

    private fun update(scope: String, entryKey: String, nowMs: Long, change: (LiveVersionStats) -> LiveVersionStats) {
        val updated = change(read(scope, entryKey) ?: LiveVersionStats()).copy(updatedAtMs = nowMs)
        preferences.edit { putString(storageKey(scope, entryKey), updated.toJson().toString()) }
        evictIfNeeded()
    }

    private fun read(scope: String, entryKey: String): LiveVersionStats? =
        preferences.getString(storageKey(scope, entryKey), null)?.let { raw ->
            runCatching { JSONObject(raw).toStats() }.getOrNull()
        }

    private fun evictIfNeeded() {
        val all = preferences.all
        if (all.size <= MAX_ENTRIES) return
        val oldest = all.entries
            .map { (key, value) -> key to ((value as? String)?.let { runCatching { JSONObject(it).optLong("u") }.getOrNull() } ?: 0L) }
            .sortedBy { it.second }
            .take(all.size - MAX_ENTRIES + EVICTION_BATCH)
        preferences.edit { oldest.forEach { remove(it.first) } }
    }

    private fun storageKey(scope: String, entryKey: String) = "$scope|$entryKey"

    private fun LiveVersionStats.toJson() = JSONObject().apply {
        width?.let { put("w", it) }
        height?.let { put("h", it) }
        frameRate?.let { put("f", it.toDouble()) }
        codec?.let { put("c", it) }
        bitrate?.let { put("b", it) }
        hdr?.let { put("hdr", it) }
        startupMs?.let { put("st", it) }
        put("wm", watchedMs)
        put("rb", rebufferCount)
        put("ls", lastSuccessAtMs)
        put("lf", lastFailureAtMs)
        put("cf", consecutiveFailures)
        put("u", updatedAtMs)
    }

    private fun JSONObject.toStats() = LiveVersionStats(
        width = optIntOrNull("w"),
        height = optIntOrNull("h"),
        frameRate = if (has("f")) optDouble("f").toFloat() else null,
        codec = optString("c").ifBlank { null },
        bitrate = optIntOrNull("b"),
        hdr = optString("hdr").ifBlank { null },
        startupMs = if (has("st")) optLong("st") else null,
        watchedMs = optLong("wm"),
        rebufferCount = optInt("rb"),
        lastSuccessAtMs = optLong("ls"),
        lastFailureAtMs = optLong("lf"),
        consecutiveFailures = optInt("cf"),
        updatedAtMs = optLong("u"),
    )

    private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name)) optInt(name) else null

    companion object {
        private const val PREFERENCES_NAME = "streamia-live-versions-v1"
        private const val MAX_ENTRIES = 800
        private const val EVICTION_BATCH = 100
        private const val DECAY_AFTER_MS = 3 * 3_600_000L

        /** Portée d'un compte : condensat du serveur et de l'identifiant, sans le mot de passe. */
        fun scopeFor(credentials: ServerCredentials): String {
            val host = runCatching { URI(credentials.serverUrl).host }.getOrNull()?.lowercase() ?: credentials.serverUrl.lowercase()
            val digest = MessageDigest.getInstance("SHA-256").digest("$host|${credentials.username}".toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

package fr.streamia.tv.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import fr.streamia.tv.domain.MediaType

/**
 * Rangée « Continuer à regarder » de l'écran d'accueil Google TV / Android TV : films et épisodes
 * entamés du profil, rouverts directement à leur position via [resumeUri]. Au mieux : un appareil
 * sans fournisseur TV (ou une erreur du système) n'affecte jamais la lecture.
 */
class WatchNextPublisher(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun publish(profileId: String, history: List<PlaybackHistoryItem>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val wanted = history.asSequence()
                .filter { it.entry.type != MediaType.Live && it.isResumable() }
                .take(MAX_PROGRAMS)
                .associateBy { it.entry.key }
            val known = preferences.all
                .filterKeys { it.startsWith("$profileId|") }
                .mapValues { (_, id) -> id as? Long ?: -1L }
            val resolver = appContext.contentResolver
            val editor = preferences.edit()

            // Retirés de l'historique (terminés, effacés) : on les enlève aussi de l'accueil.
            known.forEach { (prefKey, programId) ->
                if (prefKey.removePrefix("$profileId|") !in wanted) {
                    runCatching { resolver.delete(TvContractCompat.buildWatchNextProgramUri(programId), null, null) }
                    editor.remove(prefKey)
                }
            }
            wanted.values.forEach { item ->
                val prefKey = "$profileId|${item.entry.key}"
                val values = program(profileId, item).toContentValues()
                val existing = known[prefKey]?.takeIf { it >= 0 }
                val updated = existing != null &&
                    resolver.update(TvContractCompat.buildWatchNextProgramUri(existing), values, null, null) > 0
                if (!updated) {
                    resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
                        ?.let { editor.putLong(prefKey, it.lastPathSegment?.toLongOrNull() ?: return@let) }
                }
            }
            editor.apply()
        }
    }

    // Faux positif connu de tvprovider : ces setters publics sont hérités d'un builder de base
    // marqué @RestrictTo dans la bibliothèque (même suppression que les exemples officiels).
    @SuppressLint("RestrictedApi")
    private fun program(profileId: String, item: PlaybackHistoryItem): WatchNextProgram {
        val entry = item.entry
        return WatchNextProgram.Builder()
            .setType(if (entry.type == MediaType.Movie) TvContractCompat.PreviewPrograms.TYPE_MOVIE else TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setTitle(entry.displayName)
            .setDescription(entry.plot)
            .setPosterArtUri(entry.iconUrl?.takeIf(String::isNotBlank)?.let(Uri::parse))
            .setLastPlaybackPositionMillis(item.positionMs.toInt())
            .setDurationMillis(item.durationMs.toInt())
            .setLastEngagementTimeUtcMillis(item.updatedAt)
            .setInternalProviderId(entry.key)
            .setIntentUri(resumeUri(profileId, entry.key))
            .build()
    }

    companion object {
        private const val PREFERENCES_NAME = "streamia-watch-next"
        private const val MAX_PROGRAMS = 10
        const val SCHEME = "streamia"
        const val HOST_RESUME = "resume"

        fun resumeUri(profileId: String, entryKey: String): Uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST_RESUME)
            .appendQueryParameter("profile", profileId)
            .appendQueryParameter("key", entryKey)
            .build()
    }
}

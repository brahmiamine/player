package fr.streamia.tv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import fr.streamia.tv.data.BufferMode
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.XtreamUrlBuilder

fun shouldPrepareLivePlayback(playbackState: Int): Boolean = playbackState == Player.STATE_IDLE

/** Un seul lecteur Live, partagé entre l'aperçu et le plein écran. */
class LivePlaybackSession(context: Context, bufferMode: BufferMode = BufferMode.Auto) {
    val player: ExoPlayer = StreamiaPlayerFactory.create(context.applicationContext, MediaType.Live, bufferMode)
    var entryKey: String? = null
        private set
    var activeUrl: String = ""
        private set

    fun play(entry: MediaEntry, credentials: ServerCredentials) {
        if (entryKey == entry.key && player.mediaItemCount > 0) {
            continuePlayback()
            return
        }
        playUrl(entry, XtreamUrlBuilder(credentials).stream(entry))
    }

    fun playUrl(entry: MediaEntry, url: String) {
        entryKey = entry.key
        activeUrl = url
        // Pas de stop() : remplacer directement l'élément laisse ExoPlayer réutiliser les décodeurs
        // déjà initialisés au lieu de les libérer puis recréer à chaque zap.
        player.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(entry.playbackMetadata()).build())
        player.prepare()
        player.play()
    }

    fun isCurrent(entry: MediaEntry): Boolean = entryKey == entry.key && player.mediaItemCount > 0

    fun recoverAudio(tracks: Tracks) {
        val hasAudio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
        val selectedAudio = tracks.groups.any { group ->
            group.type == C.TRACK_TYPE_AUDIO && (0 until group.length).any(group::isTrackSelected)
        }
        if (hasAudio && !selectedAudio) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()
        }
    }

    /** Coupe immédiatement le réseau tout en permettant une reprise au retour de l'application. */
    fun stop(clearSession: Boolean = false) {
        player.stop()
        if (clearSession) {
            player.clearMediaItems()
            entryKey = null
            activeUrl = ""
        }
    }

    fun continuePlayback() {
        if (entryKey == null || player.mediaItemCount <= 0) return
        if (shouldPrepareLivePlayback(player.playbackState)) {
            player.prepare()
        }
        player.play()
    }

    fun resume() = continuePlayback()

    fun release() = player.release()
}

/**
 * Titre, numéro et logo transmis à la MediaSession : Android TV les affiche dans la carte « en
 * cours de lecture » (menu rapide, Assistant, télécommandes Bluetooth).
 */
fun MediaEntry.playbackMetadata(): MediaMetadata = MediaMetadata.Builder()
    .setTitle(displayName)
    .setDisplayTitle(displayName)
    .setSubtitle(if (type == MediaType.Live) "Chaîne $number" else null)
    .setArtworkUri(iconUrl?.takeIf(String::isNotBlank)?.let(Uri::parse))
    .setDescription(plot)
    .build()

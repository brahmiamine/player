package fr.streamia.tv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
        playUrl(entry.key, XtreamUrlBuilder(credentials).stream(entry))
    }

    fun playUrl(key: String, url: String) {
        entryKey = key
        activeUrl = url
        player.stop()
        player.setMediaItem(MediaItem.fromUri(url))
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

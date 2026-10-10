package fr.streamia.tv.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.VideoSize

/** Vraie 4K : l'image décodée fait au moins 3840 × 2160 (UHD, ou 4096 × 2160), quel que soit le nom de la chaîne. */
fun isTrueUhd(width: Int, height: Int): Boolean = width >= UHD_WIDTH && height >= UHD_HEIGHT

private const val UHD_WIDTH = 3840
private const val UHD_HEIGHT = 2160

/** Délai de confirmation : l'image doit rester en 3840 × 2160 sur la chaîne en cours. */
private const val UHD_CONFIRM_DELAY_MS = 3_000L

/**
 * Repère les chaînes lues en vraie 3840 × 2160 sur le lecteur Direct partagé (aperçu et plein
 * écran) et les signale par [onUhdDetected]. Aucune mesure ni sondage : seulement deux événements
 * d'ExoPlayer (taille d'image, première image), puis une seule vérification différée qui écarte un
 * événement en retard de la chaîne précédente juste après un zap.
 */
class LiveUhdDetector(
    private val session: LivePlaybackSession,
    private val onUhdDetected: (entryKey: String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    private val confirm = Runnable {
        val player = session.player
        val key = session.entryKey ?: return@Runnable
        if (player.playbackState != Player.STATE_READY) return@Runnable
        val size = player.videoSize
        // Chaîne déjà connue : écartée par le ViewModel sans écriture (simple recherche dans un ensemble).
        if (isTrueUhd(size.width, size.height)) onUhdDetected(key)
    }

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (isTrueUhd(videoSize.width, videoSize.height)) schedule()
        }

        // Zap entre deux chaînes 4K : la taille ne change pas, seule la première image est signalée.
        override fun onRenderedFirstFrame() = schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(confirm)
        handler.postDelayed(confirm, UHD_CONFIRM_DELAY_MS)
    }

    fun start() = session.player.addListener(listener)

    fun stop() {
        handler.removeCallbacks(confirm)
        session.player.removeListener(listener)
    }
}

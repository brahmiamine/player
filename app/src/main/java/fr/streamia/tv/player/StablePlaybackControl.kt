package fr.streamia.tv.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy

/** Fenêtre sur laquelle les coupures récentes sont comptées. */
private const val REBUFFER_WINDOW_MS = 5 * 60_000L

/**
 * Marge exigée avant de reprendre après une coupure, selon le nombre de coupures récentes : la
 * marge de base, puis le double à chaque nouvelle coupure, plafonnée. Une connexion qui ne suit
 * plus le débit (4K) produit sinon une série de micro-coupures : reprise avec 1,5 s de marge,
 * vidée aussitôt, nouvelle coupure… Mieux vaut une seule attente un peu plus longue.
 */
internal fun requiredRebufferMarginMs(baseMs: Int, recentRebuffers: Int, capMs: Int): Long {
    if (recentRebuffers <= 1) return baseMs.toLong()
    val doubled = baseMs.toLong() shl (recentRebuffers - 1).coerceAtMost(4)
    return doubled.coerceAtMost(capMs.toLong()).coerceAtLeast(baseMs.toLong())
}

/**
 * [DefaultLoadControl] dont la reprise après coupure s'adapte aux coupures récentes (voir
 * [requiredRebufferMarginMs]). Le premier démarrage (zap, ouverture d'un film) n'est pas touché.
 */
@UnstableApi
internal class AdaptiveRebufferLoadControl(
    private val delegate: DefaultLoadControl,
    private val baseRebufferMarginMs: Int,
    private val maxRebufferMarginMs: Int,
    private val targetBufferBytes: Int,
) : LoadControl {
    private val rebufferTimes = ArrayDeque<Long>()
    private var lastRebufferSeen = C.TIME_UNSET

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val allowed = delegate.shouldStartPlayback(parameters)
        if (!allowed || !parameters.rebuffering) return allowed
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(rebufferTimes) {
            val rebufferAt = parameters.lastRebufferRealtimeMs
            if (rebufferAt != C.TIME_UNSET && rebufferAt != lastRebufferSeen) {
                lastRebufferSeen = rebufferAt
                rebufferTimes.addLast(rebufferAt)
            }
            while (rebufferTimes.isNotEmpty() && now - rebufferTimes.first() > REBUFFER_WINDOW_MS) rebufferTimes.removeFirst()
            val requiredUs = requiredRebufferMarginMs(baseRebufferMarginMs, rebufferTimes.size, maxRebufferMarginMs) * 1000
            // Mémoire tampon pleine (4K à fort débit) : impossible d'aller plus loin, on repart.
            val memoryFull = delegate.getAllocator(parameters.playerId).totalBytesAllocated >= targetBufferBytes
            return parameters.bufferedDurationUs >= requiredUs || memoryFull
        }
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = delegate.shouldContinueLoading(parameters)

    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun onPrepared(playerId: PlayerId) {
        resetRebuffers()
        delegate.onPrepared(playerId)
    }

    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    @Deprecated("Remplacée par la variante à Parameters")
    override fun onTracksSelected(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = @Suppress("DEPRECATION") delegate.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) {
        resetRebuffers()
        delegate.onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    private fun resetRebuffers() = synchronized(rebufferTimes) {
        rebufferTimes.clear()
        lastRebufferSeen = C.TIME_UNSET
    }
}

/**
 * Relances réseau du Direct : une seule tant que la chaîne n'a rien affiché (une chaîne morte doit
 * vite laisser la place à l'URL suivante), plusieurs ensuite. Une connexion coupée en pleine
 * lecture est alors rouverte en arrière-plan pendant que la marge de lecture se vide, au lieu de
 * remonter une erreur qui relançait tout le flux (écran noir, longue reconnexion).
 */
@UnstableApi
internal class LiveLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(ZAP_RETRIES) {
    @Volatile var playing: Boolean = false

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = if (playing) PLAYING_RETRIES else ZAP_RETRIES

    override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val default = super.getRetryDelayMsFor(loadErrorInfo)
        // Erreur définitive (lien refusé, flux illisible) : pas de relance, comme avant.
        if (default == C.TIME_UNSET || !playing) return default
        return (loadErrorInfo.errorCount * 500L).coerceAtMost(2_000L)
    }

    private companion object {
        const val ZAP_RETRIES = 1
        const val PLAYING_RETRIES = 6
    }
}

package fr.streamia.tv.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Lecteurs en train de jouer, pour tout le processus. Les tâches de fond gourmandes en réseau
 * (enrichissement TMDB/Xtream des fiches) se mettent en pause tant qu'une vidéo joue : elles
 * prenaient de la bande passante au flux et du processeur au décodage sur les petits boîtiers.
 */
object PlaybackActivity {
    private val playing = ConcurrentHashMap.newKeySet<Int>()

    val isPlaying: Boolean get() = playing.isNotEmpty()

    internal fun update(playerId: Int, isPlaying: Boolean) {
        if (isPlaying) playing += playerId else playing -= playerId
    }

    /**
     * Attend qu'aucune vidéo ne joue (au plus [maxWaitMs]) avant un gros travail réseau : catalogue
     * complet, guide XMLTV (souvent des dizaines de Mo). Lancés pendant une lecture, ils prenaient
     * la bande passante et la mémoire du flux : coupures en 4K quelques minutes après le démarrage.
     * Le plafond évite qu'une soirée entière devant la TV laisse le catalogue et le guide périmés.
     */
    suspend fun awaitIdle(maxWaitMs: Long = MAX_DEFER_MS, pollMs: Long = POLL_MS) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        while (isPlaying && android.os.SystemClock.elapsedRealtime() - startedAt < maxWaitMs) {
            kotlinx.coroutines.delay(pollMs)
        }
    }

    private const val MAX_DEFER_MS = 45 * 60_000L
    private const val POLL_MS = 15_000L
}

/**
 * Dernière interaction à la télécommande dans l'application. Les tâches de fond lourdes attendent
 * que l'utilisateur ait cessé de naviguer : elles ne doivent jamais prendre le processeur ou le
 * réseau pendant qu'il parcourt les listes.
 */
object UserActivity {
    @Volatile private var lastInteractionAtMs = 0L

    fun onInteraction(nowMs: Long = android.os.SystemClock.elapsedRealtime()) {
        lastInteractionAtMs = nowMs
    }

    fun isRecentlyActive(windowMs: Long = QUIET_WINDOW_MS, nowMs: Long = android.os.SystemClock.elapsedRealtime()): Boolean =
        lastInteractionAtMs > 0L && nowMs - lastInteractionAtMs < windowMs

    private const val QUIET_WINDOW_MS = 2 * 60_000L
}

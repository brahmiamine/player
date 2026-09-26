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
}

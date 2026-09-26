package fr.streamia.tv.data

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Threads de priorité basse pour les longs travaux de fond : téléchargement et écriture du
 * catalogue (des centaines de milliers d'entrées), synchronisation du guide XMLTV. Sur les
 * dispatchers habituels, ces minutes de processeur tournaient à la même priorité que le dessin de
 * l'interface : la navigation saccadait pendant chaque actualisation. En priorité « arrière-plan »,
 * ils prennent tout le processeur libre mais cèdent la place dès que l'interface en a besoin.
 */
internal object BackgroundWork {
    private val threadCount = AtomicInteger()

    /** Longs travaux (actualisation du catalogue, synchronisation XMLTV) : plusieurs minutes. */
    val dispatcher: CoroutineDispatcher = lowPriorityPool(2, "streamia-background")

    /**
     * Travaux de fond courts, jamais attendus par l'utilisateur à l'instant : index des versions
     * du Direct, recommandations, guides web, rapprochement des matchs, enrichissement. Même
     * priorité basse, mais séparés des longs travaux pour ne pas attendre derrière une actualisation.
     */
    // 4 fils : un guide web qui attend le réseau n'empêche pas l'index ou les recommandations d'avancer.
    val light: CoroutineDispatcher = lowPriorityPool(4, "streamia-light")

    private fun lowPriorityPool(threads: Int, name: String): CoroutineDispatcher = Executors.newFixedThreadPool(threads) { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "$name-${threadCount.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

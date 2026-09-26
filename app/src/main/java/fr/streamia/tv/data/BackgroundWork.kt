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

    val dispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(2) { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "streamia-background-${threadCount.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

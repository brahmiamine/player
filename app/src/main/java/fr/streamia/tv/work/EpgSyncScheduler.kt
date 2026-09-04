package fr.streamia.tv.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Planifie [EpgSyncWorker] pour qu'une synchronisation EPG ait une chance de se produire hors
 * ouverture de l'app plutôt qu'au moment où l'utilisateur consulte l'écran EPG. L'intervalle reste
 * plus court que la fenêtre de fraîcheur la plus courte configurable (1h) : la plupart des
 * exécutions ne font qu'une vérification bon marché qui ne télécharge rien.
 */
object EpgSyncScheduler {
    private const val WORK_NAME = "epg-background-sync"
    private const val INTERVAL_HOURS = 1L
    private const val BACKOFF_DELAY_MINUTES = 15L

    /**
     * Idempotent : peut être appelé à chaque démarrage d'activité sans dupliquer le travail.
     * [ExistingPeriodicWorkPolicy.KEEP] conserve la planification existante plutôt que de repousser
     * sa prochaine exécution à chaque lancement de l'app, ce qui viderait l'intérêt du planning.
     */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<EpgSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

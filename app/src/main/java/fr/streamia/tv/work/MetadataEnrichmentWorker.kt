package fr.streamia.tv.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.streamia.tv.data.PlaybackSessionStore
import fr.streamia.tv.data.XtreamRepository
import fr.streamia.tv.domain.MediaType
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Remplit peu à peu les métadonnées (genre, synopsis, casting, réalisateur, TMDB) de TOUS les films
 * et séries via `get_vod_info` / `get_series_info`, au lieu des seules fiches ouvertes : sans elles, le
 * moteur de similarité compare la quasi-totalité du catalogue sur le seul titre.
 *
 * Doux pour le fournisseur : une requête à la fois, une pause entre chaque, un plafond par exécution
 * (le catalogue complet est couvert en quelques jours), et arrêt au premier signe de refus répété.
 */
class MetadataEnrichmentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Passage planifié et passage « à l'ouverture » sont deux travaux distincts : un seul à la fois,
        // pour ne jamais doubler la charge sur le fournisseur.
        if (!running.compareAndSet(false, true)) return Result.success()
        return try {
            enrich()
        } finally {
            running.set(false)
        }
    }

    private suspend fun enrich(): Result {
        val profileId = PlaybackSessionStore(applicationContext).loadActiveProfileId() ?: return Result.success()
        val repository = XtreamRepository(applicationContext)
        val credentials = repository.profile(profileId)?.credentialsOrNull() ?: return Result.success()
        val done = repository.enrichedRecommendationKeys(profileId)
        val library = repository.library(profileId)
        // Priorité : ce que l'utilisateur a regardé ou mis en favori (sources des recommandations),
        // puis les ajouts les plus récents.
        val preferred = library.favoriteEntries + library.history.map { it.entry.key }.toSet()
        val todo = listOf(MediaType.Movie, MediaType.Series)
            .flatMap { repository.loadSection(profileId, it) }
            .filter { it.key !in done }
            .sortedWith(compareByDescending<fr.streamia.tv.domain.MediaEntry> { it.key in preferred }.thenByDescending { it.addedAtEpochSeconds ?: 0L })
            .take(MAX_PER_RUN)

        var consecutiveFailures = 0
        for (entry in todo) {
            if (isStopped) break
            val ok = runCatching { repository.enrichRecommendationDetails(profileId, credentials, entry) }.isSuccess
            consecutiveFailures = if (ok) 0 else consecutiveFailures + 1
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return Result.retry()
            delay(PAUSE_MS)
        }
        // Puis les sagas Wikidata des contenus enrichis (un lot = une requête pour ~100 titres).
        repeat(MAX_SAGA_BATCHES) {
            if (isStopped) return Result.success()
            val processed = runCatching { repository.fetchSagaBatch(profileId, SAGA_BATCH_SIZE) }.getOrElse { error ->
                android.util.Log.w(TAG, "Wikidata indisponible, reprise au prochain passage", error)
                return Result.success()
            }
            if (processed == 0) return Result.success()
            delay(SAGA_PAUSE_MS)
        }
        return Result.success()
    }

    companion object {
        private val running = java.util.concurrent.atomic.AtomicBoolean(false)
        private const val WORK_NAME = "metadata-enrichment"
        private const val TAG = "MetadataEnrichment"
        private const val MAX_PER_RUN = 600
        private const val PAUSE_MS = 300L
        private const val MAX_CONSECUTIVE_FAILURES = 10
        private const val INTERVAL_HOURS = 2L
        // Laisse d'abord passer le démarrage (catalogue, première image vidéo).
        private const val LAUNCH_DELAY_SECONDS = 30L
        private const val SAGA_BATCH_SIZE = 100
        private const val MAX_SAGA_BATCHES = 20
        private const val SAGA_PAUSE_MS = 1_500L

        /** Idempotent, comme [EpgSyncScheduler.schedule]. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MetadataEnrichmentWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            // Un passage à chaque ouverture de l'app en plus du planning : le catalogue se remplit
            // aussi pendant l'utilisation. KEEP : jamais deux passages en parallèle.
            workManager.enqueueUniqueWork(
                "$WORK_NAME-on-launch",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MetadataEnrichmentWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(LAUNCH_DELAY_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}

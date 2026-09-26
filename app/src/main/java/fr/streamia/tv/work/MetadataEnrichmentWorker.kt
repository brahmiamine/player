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
import fr.streamia.tv.data.VodSortOrder
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.player.PlaybackActivity
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
        val repository = XtreamRepository.get(applicationContext)
        val credentials = repository.profile(profileId)?.credentialsOrNull() ?: return Result.success()
        // Une vidéo joue : rien maintenant (bande passante et processeur pour la lecture) ;
        // WorkManager reprogramme le passage, avec une attente croissante.
        if (PlaybackActivity.isPlaying) return Result.retry()
        val done = repository.enrichedRecommendationKeys(profileId)
        val library = repository.library(profileId)
        // Priorité : ce que l'utilisateur a regardé ou mis en favori (sources des recommandations),
        // puis les ajouts les plus récents.
        val preferred = library.favoriteEntries + library.history.map { it.entry.key }.toSet()
        val preferredEntries = repository.entriesByKeys(profileId, preferred)
        // Plafond : au-delà, favoris et historique restent enrichis, mais plus le reste du
        // catalogue. Sans lui, l'index de recommandations (tout en mémoire, reconstruit pendant
        // l'utilisation) et la base grossissaient chaque jour jusqu'à tout le catalogue.
        val recent = if (done.size >= MAX_ENRICHED) emptyList() else recentNotEnriched(repository, profileId, done)
        val todo = (preferredEntries + recent)
            .filter { it.type != MediaType.Live && it.key !in done }
            .distinctBy { it.key }
            .take(MAX_PER_RUN)

        var consecutiveFailures = 0
        for (entry in todo) {
            if (isStopped) return Result.success()
            if (PlaybackActivity.isPlaying) return Result.retry()
            val ok = runCatching { repository.enrichRecommendationDetails(profileId, credentials, entry) }.isSuccess
            consecutiveFailures = if (ok) 0 else consecutiveFailures + 1
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return Result.retry()
            delay(PAUSE_MS)
        }
        // Puis les sagas Wikidata des contenus enrichis (un lot = une requête pour ~100 titres).
        for (batch in 0 until MAX_SAGA_BATCHES) {
            if (isStopped) return Result.success()
            if (PlaybackActivity.isPlaying) return Result.retry()
            val processed = runCatching { repository.fetchSagaBatch(profileId, SAGA_BATCH_SIZE) }.getOrElse { error ->
                android.util.Log.w(TAG, "Wikidata indisponible, reprise au prochain passage", error)
                break
            }
            if (processed == 0) break
            delay(SAGA_PAUSE_MS)
        }
        // Enfin TMDB (résumé anglais, mots-clés, recommandations), une requête par contenu.
        runCatching { repository.fetchTmdbBatch(profileId, TMDB_PER_RUN, TMDB_PAUSE_MS) { isStopped || PlaybackActivity.isPlaying } }
            .onFailure { android.util.Log.w(TAG, "TMDB indisponible, reprise au prochain passage", it) }
        return Result.success()
    }

    /**
     * Ajouts les plus récents pas encore enrichis, lus page par page (index « ajouts récents ») :
     * charger d'un coup « déjà traités + [MAX_PER_RUN] » entrées faisait grossir la lecture à
     * chaque passage, jusqu'à des dizaines de milliers de fiches en mémoire après quelques jours.
     */
    private suspend fun recentNotEnriched(repository: XtreamRepository, profileId: String, done: Set<String>): List<MediaEntry> =
        listOf(MediaType.Movie, MediaType.Series).flatMap { type ->
            val found = ArrayList<MediaEntry>()
            var offset = 0
            while (found.size < MAX_PER_RUN && offset < MAX_ENRICHED + MAX_PER_RUN) {
                val page = repository.loadCategoryPage(profileId, type, Catalog.ALL_CATEGORY_ID, offset, VodSortOrder.RecentlyAdded).entries
                if (page.isEmpty()) break
                offset += page.size
                page.filterTo(found) { it.key !in done }
            }
            found
        }.sortedByDescending { it.addedAtEpochSeconds ?: 0L }

    companion object {
        private val running = java.util.concurrent.atomic.AtomicBoolean(false)
        private const val WORK_NAME = "metadata-enrichment"
        private const val TAG = "MetadataEnrichment"
        private const val MAX_PER_RUN = 600
        /** Fiches enrichies au plus hors favoris/historique (index de recommandations borné). */
        private const val MAX_ENRICHED = 15_000
        private const val PAUSE_MS = 300L
        private const val MAX_CONSECUTIVE_FAILURES = 10
        private const val INTERVAL_HOURS = 2L
        // Laisse d'abord passer le démarrage (catalogue, première image vidéo).
        private const val LAUNCH_DELAY_SECONDS = 30L
        private const val SAGA_BATCH_SIZE = 100
        private const val MAX_SAGA_BATCHES = 20
        private const val SAGA_PAUSE_MS = 1_500L
        private const val TMDB_PER_RUN = 800
        private const val TMDB_PAUSE_MS = 150L

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

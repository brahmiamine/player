package fr.streamia.tv.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.streamia.tv.data.AiNightly
import fr.streamia.tv.data.BackgroundWork
import fr.streamia.tv.data.PlaybackSessionStore
import fr.streamia.tv.data.XtreamRepository
import fr.streamia.tv.player.PlaybackActivity
import fr.streamia.tv.player.UserActivity
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Pré-calcul quotidien de l'assistant IA (voir [AiNightly]). Il attend que la TV soit au repos : rien pendant une
 * lecture ou une navigation récente. L'assistant coupé, le travail ne fait strictement rien.
 */
class AiNightlyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = XtreamRepository.get(applicationContext)
        val settings = repository.appSettings()
        if (!settings.aiEnabled || !settings.aiNightly) return Result.success()
        val profileId = PlaybackSessionStore(applicationContext).loadActiveProfileId() ?: return Result.success()
        if (isBusy()) return Result.retry()
        return runCatching {
            withContext(BackgroundWork.light) { AiNightly.run(repository, profileId) { isStopped || isBusy() } }
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    private fun isBusy(): Boolean = PlaybackActivity.isPlaying || UserActivity.isRecentlyActive()

    companion object {
        private const val WORK_NAME = "ai-nightly"
        private const val INTERVAL_HOURS = 24L

        /** Idempotent. Le travail lit les réglages à chaque passage : désactiver l'assistant ou le pré-calcul suffit à l'arrêter. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AiNightlyWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

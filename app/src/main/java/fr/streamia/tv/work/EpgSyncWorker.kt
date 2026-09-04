package fr.streamia.tv.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import fr.streamia.tv.data.PlaybackSessionStore
import fr.streamia.tv.data.XtreamRepository
import fr.streamia.tv.domain.MediaType

/**
 * Garde l'EPG du profil actif à jour même app fermée, pour que son ouverture (ou celle de l'écran
 * EPG) n'ait jamais à attendre un téléchargement. [XtreamRepository.refreshEpg] applique déjà sa
 * propre fenêtre de fraîcheur (`autoRefreshHours` du profil) : la plupart des exécutions ne font
 * donc qu'une lecture SQLite bon marché et ressortent sans rien télécharger — ce worker ne fait
 * que donner à cette vérification une chance de tourner avant que l'utilisateur rouvre l'app.
 */
class EpgSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val profileId = PlaybackSessionStore(applicationContext).loadActiveProfileId() ?: return Result.success()
        val repository = XtreamRepository(applicationContext)
        val profile = repository.profile(profileId) ?: return Result.success()
        val credentials = profile.credentialsOrNull() ?: return Result.success()
        val liveEntries = repository.loadSection(profileId, MediaType.Live)
        if (liveEntries.isEmpty()) return Result.success()

        return runCatching {
            repository.refreshEpg(profileId, credentials, liveEntries, force = false)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure() },
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

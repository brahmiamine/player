package fr.streamia.tv.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mises à jour de l'application : dernière version publiée, APK téléchargé gardé en attente
 * (survit à un arrêt de l'app) et installation, silencieuse quand le système le permet.
 */
class AppUpdateRepository(private val appContext: Context) {
    private val updateChecker = UpdateChecker()

    suspend fun checkForUpdate(currentBuild: Int): UpdateCheckResult =
        withContext(Dispatchers.IO) { updateChecker.checkForUpdate(currentBuild) }

    private val updateApk get() = File(appContext.cacheDir, "updates/streamia-tv.apk")
    private val pendingUpdateStore = PendingUpdateStore(appContext)

    /** Télécharge l'APK de [release] et le garde comme mise à jour en attente (survit à un arrêt de l'app). */
    suspend fun downloadUpdate(release: ReleaseInfo, onProgress: (Float?) -> Unit) {
        withContext(Dispatchers.IO) {
            pendingUpdateStore.clear()
            updateChecker.downloadApk(release, updateApk, onProgress)
            pendingUpdateStore.save(release)
        }
    }

    /**
     * Mise à jour déjà téléchargée et encore plus récente que la version installée. Installée
     * entre-temps (ou fichier disparu) : oubliée, et l'APK supprimé.
     */
    fun pendingUpdate(currentBuild: Int): ReleaseInfo? {
        val release = pendingUpdateStore.load()
        val build = release?.let { parseBuildNumber(it.version) }
        if (release == null || build == null || build <= currentBuild || !updateApk.exists()) {
            clearPendingUpdate()
            return null
        }
        return release
    }

    fun clearPendingUpdate() {
        pendingUpdateStore.clear()
        updateApk.delete()
    }

    /** Vrai une seule fois après que Streamia a ouvert le réglage « applis inconnues ». */
    fun consumeAwaitingInstallPermission(): Boolean =
        pendingUpdateStore.isAwaitingPermission().also { if (it) pendingUpdateStore.markAwaitingPermission(false) }

    fun openInstallPermissionSettings() {
        pendingUpdateStore.markAwaitingPermission(true)
        UpdateInstaller.openUnknownSourcesSettings(appContext)
    }

    /**
     * Installe l'APK déjà téléchargé. Sans autorisation, ouvre le réglage si [openSettingsIfNeeded]
     * (premier essai lancé par l'utilisateur) ; l'installation reprend au retour dans l'app, même
     * si Android l'a arrêtée entre-temps (voir [PendingUpdateStore]).
     */
    suspend fun installDownloadedUpdate(openSettingsIfNeeded: Boolean = false): UpdateInstallStart {
        if (UpdateInstaller.blockedByAdmin(appContext)) return UpdateInstallStart.BlockedByAdmin
        if (!UpdateInstaller.canInstall(appContext)) {
            if (!openSettingsIfNeeded) return UpdateInstallStart.PermissionStillMissing
            openInstallPermissionSettings()
            return UpdateInstallStart.PermissionRequested
        }
        withContext(Dispatchers.IO) { UpdateInstaller.install(appContext, updateApk) }
        return UpdateInstallStart.Started
    }

    val canInstallUpdateSilently: Boolean get() = UpdateInstaller.canInstallSilently
}

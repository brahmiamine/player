package fr.streamia.tv.ui

import fr.streamia.tv.data.UpdateInstallEvents
import androidx.lifecycle.viewModelScope
import fr.streamia.tv.BuildConfig
import fr.streamia.tv.data.ReleaseInfo
import fr.streamia.tv.data.UpdateInstallStart
import fr.streamia.tv.data.UpdateCheckResult
import fr.streamia.tv.data.UpdateInstallEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Mises à jour de l'application : vérification, téléchargement et installation depuis les Releases GitHub, et suivi de la session d'installation Android.
 */
internal class UpdateController(host: StreamiaStateHolder) : StreamiaController(host) {
    /** Suit les sessions d'installation Android (fenêtre affichée, annulée, échec). */
    fun start() {
        viewModelScope.launch { UpdateInstallEvents.events.collect(::onUpdateInstallEvent) }
    }

    /**
     * Vérifie, télécharge (avec progression) puis installe. Une mise à jour déjà téléchargée pour
     * la même version n'est pas retéléchargée.
     */
    fun checkForUpdate() {
        if (_uiState.value.updateChecking) return
        _uiState.update { it.copy(updateChecking = true, updateCheck = null) }
        viewModelScope.launch {
            try {
                val result = repository.checkForUpdate(BuildConfig.VERSION_CODE)
                _uiState.update { it.copy(updateCheck = result) }
                if (result !is UpdateCheckResult.UpdateAvailable) return@launch
                val release = result.release
                val alreadyDownloaded = repository.pendingUpdate(BuildConfig.VERSION_CODE)?.version == release.version
                if (!alreadyDownloaded) {
                    val downloaded = runCatching {
                        repository.downloadUpdate(release) { progress ->
                            _uiState.update { it.copy(updateCheck = UpdateCheckResult.Downloading(release, progress)) }
                        }
                    }
                    downloaded.exceptionOrNull()?.let { error ->
                        if (error is CancellationException) throw error
                        _uiState.update {
                            it.copy(updateCheck = UpdateCheckResult.Error("Téléchargement impossible : " + (error.message ?: "erreur inconnue.")))
                        }
                        return@launch
                    }
                }
                installUpdate(release, openSettingsIfNeeded = true)
            } finally {
                _uiState.update { it.copy(updateChecking = false) }
            }
        }
    }

    /** Bouton « Installer » / « Réessayer l'installation » : l'APK est déjà téléchargé. */
    fun installPendingUpdate() {
        val release = repository.pendingUpdate(BuildConfig.VERSION_CODE) ?: return checkForUpdate()
        viewModelScope.launch { installUpdate(release, openSettingsIfNeeded = true) }
    }

    /** Bouton « Autoriser l'installation » : ouvre le réglage, l'installation reprend au retour. */
    fun openUpdateInstallPermission() {
        val release = repository.pendingUpdate(BuildConfig.VERSION_CODE) ?: return
        repository.openInstallPermissionSettings()
        _uiState.update { it.copy(updateCheck = UpdateCheckResult.AwaitingInstallPermission(release)) }
    }

    private suspend fun installUpdate(release: ReleaseInfo, openSettingsIfNeeded: Boolean) {
        runCatching { repository.installDownloadedUpdate(openSettingsIfNeeded) }
            .onSuccess { start -> onUpdateInstallStart(start, release) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(updateCheck = UpdateCheckResult.Error("Installation impossible : " + (error.message ?: "erreur inconnue."), release))
                }
            }
    }

    /**
     * Retour dans l'app (MainActivity.onResume), y compris après qu'Android l'a arrêtée parce que
     * l'autorisation « Installer des applis inconnues » vient de changer (Android 11+) : l'APK et
     * l'attente d'autorisation sont gardés sur disque, l'installation reprend donc toute seule.
     */
    fun resumePendingUpdateInstall() {
        if (_uiState.value.updateChecking) return
        val current = _uiState.value.updateCheck
        if (current is UpdateCheckResult.Installing || current is UpdateCheckResult.Downloading) return
        val release = repository.pendingUpdate(BuildConfig.VERSION_CODE)
        if (release == null) {
            if (current is UpdateCheckResult.AwaitingInstallPermission || current is UpdateCheckResult.Downloaded) {
                _uiState.update { it.copy(updateCheck = null) }
            }
            return
        }
        if (repository.consumeAwaitingInstallPermission()) {
            viewModelScope.launch { installUpdate(release, openSettingsIfNeeded = false) }
        } else if (current == null || current is UpdateCheckResult.AwaitingInstallPermission) {
            // Mise à jour prête (installation annulée ou pas encore autorisée) : proposée dans Paramètres.
            _uiState.update { it.copy(updateCheck = UpdateCheckResult.Downloaded(release)) }
        }
    }

    private fun onUpdateInstallStart(start: UpdateInstallStart, release: ReleaseInfo) {
        val next = when (start) {
            UpdateInstallStart.Started -> UpdateCheckResult.Installing(release, silent = repository.canInstallUpdateSilently)
            // Réglage ouvert : l'installation reprendra au retour dans l'app (onResume).
            UpdateInstallStart.PermissionRequested -> UpdateCheckResult.AwaitingInstallPermission(release)
            UpdateInstallStart.PermissionStillMissing -> UpdateCheckResult.AwaitingInstallPermission(release)
            UpdateInstallStart.BlockedByAdmin -> UpdateCheckResult.Error(
                "L'installation d'applications est bloquée par l'administrateur de ce profil (icône mallette : " +
                    "profil professionnel). Installez la mise à jour depuis le profil principal.",
                release,
            )
        }
        _uiState.update { it.copy(updateCheck = next) }
    }

    /** Statut de la session d'installation (fenêtre Android affichée, annulée, échec). */
    private fun onUpdateInstallEvent(event: UpdateInstallEvent) {
        val release = when (val current = _uiState.value.updateCheck) {
            is UpdateCheckResult.Installing -> current.release
            else -> repository.pendingUpdate(BuildConfig.VERSION_CODE)
        } ?: return
        val next = when (event) {
            UpdateInstallEvent.ConfirmationShown -> UpdateCheckResult.Installing(release, silent = false)
            UpdateInstallEvent.Aborted -> UpdateCheckResult.Downloaded(release)
            UpdateInstallEvent.Succeeded -> null.also { repository.clearPendingUpdate() }
            is UpdateInstallEvent.Failed -> UpdateCheckResult.Error(event.message, release)
        }
        _uiState.update { it.copy(updateCheck = next) }
    }

    fun dismissUpdateCheck() { _uiState.update { it.copy(updateCheck = null) } }
}

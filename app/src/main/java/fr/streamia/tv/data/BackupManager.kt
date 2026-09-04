package fr.streamia.tv.data

import android.content.Context
import org.json.JSONObject

/**
 * Exporte/importe les préférences (réglages de lecture, favoris, catégories masquées/verrouillées,
 * ordre, historique) en JSON. Ne touche jamais [PlaylistStore]/[CredentialsStore] : aucun
 * identifiant de connexion ne transite donc par un fichier de sauvegarde — après restauration, les
 * playlists doivent être ré-ajoutées manuellement si l'app a été réinstallée.
 */
class BackupManager(context: Context) {
    private val appSettingsStore = AppSettingsStore(context)
    private val libraryStore = UserLibraryStore(context)

    fun export(profileId: String?): String {
        val root = JSONObject()
        root.put("backupVersion", BACKUP_VERSION)
        root.put("exportedAtEpochMs", System.currentTimeMillis())
        root.put("appSettings", appSettingsStore.load().toBackupJson())
        if (profileId != null) root.put("library", libraryStore.exportRaw(profileId))
        return root.toString(2)
    }

    /** @return un message de résumé si la restauration a réussi, sinon lève l'exception rencontrée. */
    fun import(profileId: String?, json: String): String {
        val root = JSONObject(json)
        var restoredLibrary = false
        root.optJSONObject("appSettings")?.let {
            appSettingsStore.save(appSettingsFromBackupJson(it, fallback = appSettingsStore.load()))
        }
        if (profileId != null) {
            root.optJSONObject("library")?.let {
                libraryStore.importRaw(profileId, it)
                restoredLibrary = true
            }
        }
        return if (restoredLibrary) {
            "Réglages et préférences du profil restaurés."
        } else {
            "Réglages restaurés (aucune donnée de profil dans ce fichier)."
        }
    }

    private companion object {
        const val BACKUP_VERSION = 1
    }
}

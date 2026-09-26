package fr.streamia.tv.data

import android.content.Context
import androidx.core.content.edit

/**
 * Mise à jour téléchargée mais pas encore installée, gardée sur disque. Android arrête Streamia
 * dès que l'autorisation « Installer des applis inconnues » change (Android 11+) : gardée en
 * mémoire seulement, elle était perdue au retour du réglage et l'installation ne reprenait jamais.
 */
internal class PendingUpdateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("streamia-update", Context.MODE_PRIVATE)

    fun save(release: ReleaseInfo) {
        preferences.edit {
            putString(KEY_VERSION, release.version)
            putString(KEY_HTML_URL, release.htmlUrl)
            putString(KEY_APK_URL, release.apkUrl)
        }
    }

    fun load(): ReleaseInfo? {
        val version = preferences.getString(KEY_VERSION, null) ?: return null
        return ReleaseInfo(
            version = version,
            htmlUrl = preferences.getString(KEY_HTML_URL, null).orEmpty(),
            notes = version,
            apkUrl = preferences.getString(KEY_APK_URL, null),
        )
    }

    /** Réglage « applis inconnues » ouvert par Streamia : l'installation reprend seule au retour. */
    fun markAwaitingPermission(awaiting: Boolean) {
        preferences.edit { putBoolean(KEY_AWAITING_PERMISSION, awaiting) }
    }

    fun isAwaitingPermission(): Boolean = preferences.getBoolean(KEY_AWAITING_PERMISSION, false)

    fun clear() {
        preferences.edit { clear() }
    }

    private companion object {
        const val KEY_VERSION = "version"
        const val KEY_HTML_URL = "html_url"
        const val KEY_APK_URL = "apk_url"
        const val KEY_AWAITING_PERMISSION = "awaiting_permission"
    }
}

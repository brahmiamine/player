package fr.streamia.tv.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.widget.Toast
import java.io.File

/**
 * Installe l'APK de mise à jour par une session [PackageInstaller]. Un ACTION_VIEW lancé depuis le
 * contexte application n'était attribué à aucune app (pas d'appelant connu de l'installeur sur
 * Android < 12) : l'installeur exigeait l'autorisation « sources inconnues » pour une source
 * anonyme et renvoyait sans fin vers le réglage, même Streamia autorisée. La session, elle, est
 * rattachée à Streamia et utilise l'autorisation déjà accordée.
 */
internal object UpdateInstaller {
    /** « Installer des applis inconnues » accordé à Streamia (réglage par app depuis Android 8). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /**
     * Installation d'applis inconnues interdite par l'administrateur du profil — typiquement un
     * profil professionnel (icône mallette sur l'app). L'interrupteur peut alors paraître activé
     * sans effet : renvoyer vers le réglage ne ferait que boucler.
     */
    fun blockedByAdmin(context: Context): Boolean {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        return userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY))
    }

    fun openUnknownSourcesSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val sessionId = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(sessionId).use { session ->
            session.openWrite("streamia-tv.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutable : le système y ajoute le statut et, si besoin, l'écran de confirmation.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(context, sessionId, Intent(context, UpdateInstallReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }
}

/** Résultat de la session : affiche l'écran de confirmation Android, ou l'échec. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // L'application est remplacée par la nouvelle version.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // Annulé par l'utilisateur.
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "code $status"
                Toast.makeText(context, "Installation de la mise à jour impossible : $detail", Toast.LENGTH_LONG).show()
            }
        }
    }
}

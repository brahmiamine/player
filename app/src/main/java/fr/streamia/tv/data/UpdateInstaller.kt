package fr.streamia.tv.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * Installe l'APK de mise à jour par une session [PackageInstaller]. Un ACTION_VIEW lancé depuis le
 * contexte application n'était attribué à aucune app (pas d'appelant connu de l'installeur sur
 * Android < 12) : l'installeur exigeait l'autorisation « sources inconnues » pour une source
 * anonyme et renvoyait sans fin vers le réglage, même Streamia autorisée. La session, elle, est
 * rattachée à Streamia et utilise l'autorisation déjà accordée.
 *
 * Android 12+ : une app qui se met à jour elle-même, avec l'autorisation « Installer des applis
 * inconnues » et la permission UPDATE_PACKAGES_WITHOUT_USER_ACTION, peut s'installer sans fenêtre
 * de confirmation ([PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED]). Sinon Android
 * demande toujours la confirmation, affichée par [UpdateInstallReceiver].
 */
internal object UpdateInstaller {
    /** « Installer des applis inconnues » accordé à Streamia (réglage par app depuis Android 8). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Android 12+ : la mise à jour de Streamia par elle-même peut se faire sans confirmation. */
    val canInstallSilently: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

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
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            // Certains boîtiers n'ont pas l'écran par app : liste générale des sources inconnues.
            .recoverCatching {
                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
    }

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            // Android 14+ : Streamia devient propriétaire de ses mises à jour, les suivantes restent
            // silencieuses même si l'APK d'origine a été installé par une autre app (Downloader…).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setRequestUpdateOwnership(true)
        }
        val sessionId = installer.createSession(params)
        try {
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
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }
}

/** Étapes de la session d'installation, remontées à l'écran Paramètres. */
sealed interface UpdateInstallEvent {
    /** Fenêtre de confirmation Android ouverte. */
    data object ConfirmationShown : UpdateInstallEvent
    data object Aborted : UpdateInstallEvent
    data object Succeeded : UpdateInstallEvent
    data class Failed(val message: String) : UpdateInstallEvent
}

internal object UpdateInstallEvents {
    private val _events = MutableSharedFlow<UpdateInstallEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UpdateInstallEvent> = _events.asSharedFlow()

    fun emit(event: UpdateInstallEvent) {
        _events.tryEmit(event)
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
                val shown = confirm != null && runCatching {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                UpdateInstallEvents.emit(
                    if (shown) UpdateInstallEvent.ConfirmationShown
                    else UpdateInstallEvent.Failed("Android n'a pas pu afficher la fenêtre d'installation. Réessayez depuis Paramètres."),
                )
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateInstallEvents.emit(UpdateInstallEvent.Succeeded)
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateInstallEvents.emit(UpdateInstallEvent.Aborted)
            else -> UpdateInstallEvents.emit(
                UpdateInstallEvent.Failed(installFailureMessage(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))),
            )
        }
    }
}

internal fun installFailureMessage(status: Int, detail: String?): String = when (status) {
    PackageInstaller.STATUS_FAILURE_BLOCKED ->
        "Android a bloqué l'installation (restriction de l'appareil ou du profil)."
    PackageInstaller.STATUS_FAILURE_CONFLICT ->
        "Cette version ne peut pas remplacer celle installée (signature différente). Désinstallez Streamia puis installez la nouvelle version."
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
        "Cette version n'est pas compatible avec ce boîtier."
    PackageInstaller.STATUS_FAILURE_INVALID ->
        "Le fichier téléchargé est invalide. Relancez la mise à jour."
    PackageInstaller.STATUS_FAILURE_STORAGE ->
        "Espace de stockage insuffisant pour installer la mise à jour."
    else -> "Installation impossible" + (detail?.takeIf(String::isNotBlank)?.let { " : $it" } ?: ".")
}

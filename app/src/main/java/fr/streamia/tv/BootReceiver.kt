package fr.streamia.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fr.streamia.tv.data.AppSettingsStore

/** Ouvre l'app au démarrage de l'appareil si « Lancer au démarrage de la TV » est activé. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppSettingsStore(context).load().launchOnBoot) return
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

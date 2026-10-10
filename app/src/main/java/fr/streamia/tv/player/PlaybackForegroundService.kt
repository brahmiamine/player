package fr.streamia.tv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fr.streamia.tv.MainActivity
import fr.streamia.tv.R

/**
 * Service de premier plan tenu pendant qu'un lecteur est ouvert sur mobile : sans lui, Android gèle
 * Streamia dès que l'écran est verrouillé ou l'appli quittée, et le son s'arrête. Il ne lit rien lui-même
 * (le lecteur reste dans l'écran de lecture) : il garde seulement le processus actif.
 */
class PlaybackForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Lecture en cours", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_logo_mark)
            .setContentTitle(intent?.getStringExtra(EXTRA_TITLE) ?: "Streamia")
            .setContentText("Lecture en cours")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, PlaybackForegroundService::class.java).putExtra(EXTRA_TITLE, title),
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackForegroundService::class.java))
        }
    }
}

/** Vrai tant que la fenêtre de l'appli est en picture-in-picture (renseigné par MainActivity). */
object PipState {
    val active = androidx.compose.runtime.mutableStateOf(false)
}

package fr.streamia.tv

import android.content.Intent
import android.os.Bundle
import android.os.StrictMode
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import fr.streamia.tv.data.XtreamRepository
import fr.streamia.tv.logging.CrashReporter
import fr.streamia.tv.ui.StreamiaTvRoot
import fr.streamia.tv.ui.trimArtworkCache
import fr.streamia.tv.ui.StreamiaViewModel
import fr.streamia.tv.ui.StreamiaViewModelFactory
import fr.streamia.tv.ui.mobile.DeviceKind
import fr.streamia.tv.ui.mobile.detectDeviceKind
import fr.streamia.tv.work.EpgSyncScheduler
import fr.streamia.tv.work.MetadataEnrichmentWorker

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: StreamiaViewModel
    private var jankReporter: fr.streamia.tv.logging.JankReporter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Debug : chaque accès disque ou réseau sur le thread principal est signalé dans logcat
        // (tag StrictMode), pour ne pas réintroduire de gel de l'interface.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().detectCustomSlowCalls().penaltyLog().build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build(),
            )
        }

        // TV : toujours paysage. Mobile : portrait au démarrage ; l'interface passe en paysage pour le lecteur.
        requestedOrientation = if (detectDeviceKind(this) == DeviceKind.Tv) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        CrashReporter.initialize(applicationContext)
        // WorkManager crée et ouvre sa base à la première utilisation : hors du thread principal,
        // pour ne pas retarder le premier affichage.
        lifecycleScope.launch(Dispatchers.Default) {
            EpgSyncScheduler.schedule(applicationContext)
            MetadataEnrichmentWorker.schedule(applicationContext)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        // TV : barres système toujours masquées. Mobile : barres visibles (masquées seulement par le
        // lecteur plein écran, voir StreamiaApp), le contenu respecte leurs marges.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (detectDeviceKind(this@MainActivity) == DeviceKind.Tv) hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        viewModel = ViewModelProvider(
            this,
            StreamiaViewModelFactory(XtreamRepository.get(applicationContext)),
        )[StreamiaViewModel::class.java]

        // Lancement depuis une carte « Continuer à regarder » de Google TV : la reprise passe avant
        // la restauration de session habituelle (StreamiaTvRoot l'ignore dès qu'un profil est actif).
        viewModel.openResumeLink(intent?.data)
        setContent { StreamiaTvRoot(viewModel) }

        // Saccades mesurées par écran en usage réel (journal Crashlytics).
        jankReporter = runCatching { fr.streamia.tv.logging.JankReporter.attach(window) }.getOrNull()
        lifecycleScope.launch {
            viewModel.uiState.collect { state -> jankReporter?.onScreen(state.screen::class.simpleName ?: "screen") }
        }
    }

    // Chaque appui télécommande : les tâches de fond lourdes attendent que la navigation se calme.
    override fun onUserInteraction() {
        super.onUserInteraction()
        fr.streamia.tv.player.UserActivity.onInteraction()
    }

    // Mobile : quitter l'appli (bouton Accueil) pendant la lecture la réduit en picture-in-picture.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!::viewModel.isInitialized || detectDeviceKind(this) != DeviceKind.Mobile) return
        if (viewModel.uiState.value.screen !is fr.streamia.tv.ui.StreamiaScreen.Player) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        runCatching {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build(),
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        fr.streamia.tv.player.PipState.active.value = isInPictureInPictureMode
    }

    override fun onPause() {
        super.onPause()
        jankReporter?.setTracking(false)
    }

    override fun onResume() {
        super.onResume()
        jankReporter?.setTracking(true)
        // Retour du réglage « Installer des applis inconnues » : l'installation de la mise à jour reprend.
        if (::viewModel.isInitialized) viewModel.resumePendingUpdateInstall()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        trimArtworkCache(level)
        if (::viewModel.isInitialized) viewModel.onTrimMemory(level)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.openResumeLink(intent.data)
    }
}

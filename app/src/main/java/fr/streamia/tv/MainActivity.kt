package fr.streamia.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import fr.streamia.tv.work.EpgSyncScheduler
import fr.streamia.tv.work.MetadataEnrichmentWorker

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: StreamiaViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        CrashReporter.initialize(applicationContext)
        EpgSyncScheduler.schedule(applicationContext)
        MetadataEnrichmentWorker.schedule(applicationContext)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
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
    }

    override fun onResume() {
        super.onResume()
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

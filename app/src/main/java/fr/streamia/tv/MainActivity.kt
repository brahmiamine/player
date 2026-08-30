package fr.streamia.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import fr.streamia.tv.data.XtreamRepository
import fr.streamia.tv.ui.StreamiaApp
import fr.streamia.tv.ui.StreamiaViewModel
import fr.streamia.tv.ui.StreamiaViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val viewModel = ViewModelProvider(
            this,
            StreamiaViewModelFactory(XtreamRepository(applicationContext)),
        )[StreamiaViewModel::class.java]

        setContent { StreamiaApp(viewModel) }
    }
}

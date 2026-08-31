package fr.streamia.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import fr.streamia.tv.data.XtreamRepository
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.PlayerOverlayController
import fr.streamia.tv.ui.StreamiaScreen
import fr.streamia.tv.ui.StreamiaTvRoot
import fr.streamia.tv.ui.StreamiaViewModel
import fr.streamia.tv.ui.StreamiaViewModelFactory

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: StreamiaViewModel
    private var consumePickerKeyUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        viewModel = ViewModelProvider(
            this,
            StreamiaViewModelFactory(XtreamRepository(applicationContext)),
        )[StreamiaViewModel::class.java]

        setContent { StreamiaTvRoot(viewModel) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::viewModel.isInitialized || !event.isTvOkKey()) return super.dispatchKeyEvent(event)

        if (event.action == KeyEvent.ACTION_UP && consumePickerKeyUp) {
            consumePickerKeyUp = false
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val player = viewModel.uiState.value.screen as? StreamiaScreen.Player
            val canOpenLivePicker = player?.entry?.type == MediaType.Live &&
                !PlayerOverlayController.isLivePickerOpen()
            if (canOpenLivePicker) {
                PlayerOverlayController.openLivePicker()
                consumePickerKeyUp = true
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun KeyEvent.isTvOkKey(): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        -> true

        else -> false
    }
}

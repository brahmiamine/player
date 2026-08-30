package fr.streamia.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.StreamiaTheme

@Composable
fun StreamiaApp(viewModel: StreamiaViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    StreamiaTheme {
        when {
            state.booting -> BootScreen()
            state.screen is StreamiaScreen.Login -> LoginScreen(
                busy = state.busy,
                message = state.message,
                onSignIn = viewModel::signIn,
                onDismissMessage = viewModel::dismissMessage,
            )
            state.screen is StreamiaScreen.Browser && state.catalog != null -> BrowserScreen(
                catalog = state.catalog!!,
                offline = state.offline,
                busy = state.busy,
                message = state.message,
                onChannelSelected = viewModel::openChannel,
                onRefresh = viewModel::refresh,
                onLogout = viewModel::logout,
                onDismissMessage = viewModel::dismissMessage,
            )
            state.screen is StreamiaScreen.Player && state.catalog != null && state.credentials != null -> PlayerScreen(
                catalog = state.catalog!!,
                credentials = state.credentials!!,
                channel = (state.screen as StreamiaScreen.Player).channel,
                onBack = viewModel::closePlayer,
                onZap = viewModel::zap,
                onChannelSelected = viewModel::openChannel,
            )
            else -> BootScreen()
        }
    }
}

@Composable
private fun BootScreen() {
    Box(Modifier.fillMaxSize().background(Night), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StreamiaLogo()
            Spacer(Modifier.height(22.dp))
            Text("Préparation de votre télévision…", color = MutedInk, fontSize = 17.sp)
        }
    }
}

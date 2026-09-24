package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.TypeBody
import fr.streamia.tv.ui.theme.TypeLabel
import fr.streamia.tv.ui.theme.TypeSectionTitle

/** Confirmation d'une action qui fait quitter l'écran courant. Focus sur « Annuler » par sécurité. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.76f)), contentAlignment = Alignment.Center) {
        GlassSurface(modifier = Modifier.width(560.dp)) {
            Column(Modifier.padding(26.dp)) {
                Text(title, color = Ink, fontSize = TypeSectionTitle, fontWeight = HeadingWeight)
                Spacer(Modifier.height(8.dp))
                Text(message, color = MutedInk, fontSize = TypeBody)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusableSurface(onClick = onDismiss, modifier = Modifier.width(200.dp).height(52.dp).focusRequester(cancelFocus)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Annuler", color = Ink, fontSize = TypeLabel, fontWeight = FontWeight.Bold)
                        }
                    }
                    FocusableSurface(onClick = onConfirm, accent = true, modifier = Modifier.width(200.dp).height(52.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(confirmLabel, color = Ink, fontSize = TypeLabel, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

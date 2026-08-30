package fr.streamia.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.WarmSignal

@Composable
fun LoginScreen(
    busy: Boolean,
    message: String?,
    onSignIn: (String, String, String) -> Unit,
    onImportM3u: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val serverFocus = remember { FocusRequester() }
    val canSubmit = server.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !busy

    LaunchedEffect(Unit) { serverFocus.requestFocus() }

    Row(
        Modifier
            .fillMaxSize()
            .background(Night),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.43f)
                .background(DeepSurface)
                .padding(horizontal = 62.dp, vertical = 54.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            StreamiaLogo()
            Column {
                Text(
                    "Vos chaînes, sans détour.",
                    color = Ink,
                    fontSize = 38.sp,
                    lineHeight = 43.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    "Connectez votre propre abonnement Xtream ou importez un fichier M3U. Aucun contenu n'est inclus.",
                    color = MutedInk,
                    fontSize = 19.sp,
                    lineHeight = 27.sp,
                    modifier = Modifier.fillMaxWidth(0.92f),
                )
            }
            Text(
                "Pensé pour Android TV · Navigation 100 % télécommande",
                color = MutedInk,
                fontSize = 15.sp,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.57f)
                .padding(horizontal = 84.dp, vertical = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text(
                    "Connexion au service",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Ink,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Saisissez vos accès Xtream. Le protocole HTTPS est ajouté automatiquement s'il manque.",
                    color = MutedInk,
                    fontSize = 17.sp,
                )
                Spacer(Modifier.height(8.dp))
                TvTextField(
                    value = server,
                    onValueChange = { server = it; onDismissMessage() },
                    label = "Adresse du serveur",
                    supportingText = "Exemple : https://serveur.example:8080",
                    modifier = Modifier.fillMaxWidth().focusRequester(serverFocus),
                )
                TvTextField(
                    value = username,
                    onValueChange = { username = it; onDismissMessage() },
                    label = "Identifiant",
                    modifier = Modifier.fillMaxWidth(),
                )
                TvTextField(
                    value = password,
                    onValueChange = { password = it; onDismissMessage() },
                    label = "Mot de passe",
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (server.trim().startsWith("http://", ignoreCase = true)) {
                    Text(
                        "Attention : ce serveur utilise une connexion non chiffrée (HTTP).",
                        color = WarmSignal,
                        fontSize = 15.sp,
                    )
                }
                if (message != null) {
                    Text(message, color = MaterialTheme.colorScheme.error, fontSize = 16.sp)
                }

                FocusableSurface(
                    onClick = { onSignIn(server, username, password) },
                    enabled = canSubmit,
                    contentDescription = "Se connecter à Xtream",
                    modifier = Modifier.fillMaxWidth().height(66.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (busy) "Connexion en cours…" else "Ouvrir Streamia TV",
                            color = if (canSubmit || busy) Ink else MutedInk,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (!busy) {
                            Spacer(Modifier.width(12.dp))
                            Text("▶", color = FocusBlueBright, fontSize = 18.sp)
                        }
                    }
                }
                FocusableSurface(
                    onClick = onImportM3u,
                    enabled = !busy,
                    contentDescription = "Importer un fichier M3U local",
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Importer un fichier M3U", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    "Le fichier est analysé localement. Les URL contenant vos accès ne sont ni journalisées ni publiées.",
                    color = MutedInk,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

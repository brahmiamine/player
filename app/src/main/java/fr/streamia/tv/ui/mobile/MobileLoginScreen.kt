package fr.streamia.tv.ui.mobile

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.R
import fr.streamia.tv.data.PlaylistKind
import fr.streamia.tv.data.PlaylistProfile
import fr.streamia.tv.ui.AccentPill
import fr.streamia.tv.ui.StreamiaIcon
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.TvTextField
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.Danger
import fr.streamia.tv.ui.theme.FocusBlue
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusCard
import fr.streamia.tv.ui.theme.RadiusPill
import fr.streamia.tv.ui.theme.WarmSignal

private enum class MobileLoginMode { Manager, Xtream, M3u }

private val M3uMimeTypes = arrayOf("audio/x-mpegurl", "application/x-mpegURL", "application/vnd.apple.mpegurl", "text/plain", "*/*")

/**
 * Mes listes (gestionnaire de playlists) au doigt : cartes de listes, ajout Xtream ou M3U (fichier
 * ou URL), test de connexion, modification et suppression avec confirmation. Mêmes actions que
 * l'écran TV.
 */
@Composable
fun MobileLoginScreen(
    profiles: List<PlaylistProfile>,
    busy: Boolean,
    testingConnection: Boolean,
    testSucceeded: Boolean,
    message: String?,
    onOpenProfile: (String) -> Unit,
    onSignIn: (String?, String, String, String, String) -> Unit,
    onTestConnection: (String, String, String) -> Unit,
    onImportM3u: (Uri, String?, String) -> Unit,
    onImportM3uUrl: (String?, String, String, String, Int) -> Unit,
    onSaveM3uSettings: (String, String, String, Int) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onDismissMessage: () -> Unit,
    onReturnToList: (() -> Unit)? = null,
) {
    var mode by remember { mutableStateOf(MobileLoginMode.Manager) }
    var editing by remember { mutableStateOf<PlaylistProfile?>(null) }
    var deleteCandidate by remember { mutableStateOf<PlaylistProfile?>(null) }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var m3uUrl by remember { mutableStateOf("") }
    var xmlTvUrl by remember { mutableStateOf("") }
    var refreshHours by remember { mutableStateOf("6") }
    var pendingId by remember { mutableStateOf<String?>(null) }
    var pendingName by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportM3u(uri, pendingId, pendingName)
    }

    fun showManager() {
        mode = MobileLoginMode.Manager
        editing = null
        deleteCandidate = null
        onDismissMessage()
    }
    BackHandler(enabled = mode != MobileLoginMode.Manager) { showManager() }
    BackHandler(enabled = mode == MobileLoginMode.Manager && onReturnToList != null) { onReturnToList?.invoke() }

    fun showXtream(profile: PlaylistProfile? = null) {
        editing = profile
        mode = MobileLoginMode.Xtream
        name = profile?.name.orEmpty()
        server = profile?.serverUrl.orEmpty()
        username = profile?.username.orEmpty()
        password = profile?.password.orEmpty()
        deleteCandidate = null
        onDismissMessage()
    }

    fun showM3u(profile: PlaylistProfile? = null) {
        editing = profile
        mode = MobileLoginMode.M3u
        name = profile?.name.orEmpty()
        m3uUrl = profile?.m3uUrl.orEmpty()
        xmlTvUrl = profile?.xmlTvUrl.orEmpty()
        refreshHours = (profile?.autoRefreshHours ?: 6).toString()
        deleteCandidate = null
        onDismissMessage()
    }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(start = MobileGutter, end = MobileGutter, top = 12.dp, bottom = 24.dp)) {
        if (mode == MobileLoginMode.Manager) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(painterResource(R.drawable.streamia_logo_mark), "Logo Streamia", Modifier.size(38.dp), contentScale = ContentScale.Fit)
                Text("Streamia", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(24.dp))
            Text("Mes listes", color = Ink, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (profiles.isEmpty()) "Aucune liste enregistrée. Ajoutez votre première source."
                else "Comptes Xtream et playlists M3U étendues. Touchez une liste pour l'ouvrir.",
                color = MutedInk,
                fontSize = 14.sp,
            )
            message?.let { Spacer(Modifier.height(10.dp)); MobileMessage(it, onDismissMessage) }
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                profiles.forEach { profile ->
                    MobileCard(Modifier.fillMaxWidth().height(72.dp), onClick = { if (!busy) onOpenProfile(profile.id) }, radius = 24.dp) {
                        Row(
                            Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(FocusBlue), contentAlignment = Alignment.Center) {
                                StreamiaIcon(if (profile.kind == PlaylistKind.Xtream) StreamiaIconGlyph.Live else StreamiaIconGlyph.Guide, tint = AccentPinkText, size = 22.dp)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        profile.kind == PlaylistKind.Xtream -> "Xtream"
                                        profile.isRemoteM3u -> "M3U URL · auto ${profile.autoRefreshHours} h"
                                        else -> "M3U fichier"
                                    },
                                    color = AccentPinkText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Box(
                                Modifier.size(MobileMinTouch).clickable(enabled = !busy) {
                                    if (profile.kind == PlaylistKind.Xtream) showXtream(profile) else showM3u(profile)
                                },
                                contentAlignment = Alignment.Center,
                            ) { StreamiaIcon(StreamiaIconGlyph.Settings, tint = MutedInk, size = 20.dp) }
                            Box(
                                Modifier.size(MobileMinTouch).clickable(enabled = !busy) { deleteCandidate = profile; onDismissMessage() },
                                contentAlignment = Alignment.Center,
                            ) { StreamiaIcon(StreamiaIconGlyph.Delete, tint = MutedInk, size = 20.dp) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("＋ Ajouter Xtream", enabled = !busy, onClick = { showXtream() })
            Spacer(Modifier.height(10.dp))
            SecondaryButton("＋ Ajouter M3U / URL", enabled = !busy, onClick = { showM3u() })
            Spacer(Modifier.height(18.dp))
            Text(
                "Identifiants chiffrés localement (Android Keystore). N'utilisez que des flux que vous êtes autorisé à regarder.",
                color = MutedInk,
                fontSize = 11.sp,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MobileIconButton(StreamiaIconGlyph.ChevronLeft, onClick = ::showManager)
                Text(
                    if (mode == MobileLoginMode.Xtream) (if (editing == null) "Nouveau compte Xtream" else "Modifier la liste") else (if (editing == null) "Nouvelle playlist M3U" else "Modifier la liste"),
                    color = Ink,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvTextField(name, { name = it; onDismissMessage() }, "Nom de la liste", Modifier.fillMaxWidth())
                if (mode == MobileLoginMode.Xtream) {
                    TvTextField(server, { server = it; onDismissMessage() }, "Serveur (http://hôte:port)", Modifier.fillMaxWidth())
                    TvTextField(username, { username = it; onDismissMessage() }, "Identifiant", Modifier.fillMaxWidth())
                    TvTextField(password, { password = it; onDismissMessage() }, "Mot de passe", Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                } else {
                    TvTextField(m3uUrl, { m3uUrl = it; onDismissMessage() }, "URL de la playlist (facultatif si fichier)", Modifier.fillMaxWidth())
                    TvTextField(xmlTvUrl, { xmlTvUrl = it; onDismissMessage() }, "URL XMLTV (facultatif)", Modifier.fillMaxWidth())
                    TvTextField(refreshHours, { refreshHours = it.filter(Char::isDigit).take(3); onDismissMessage() }, "Actualisation automatique (heures)", Modifier.fillMaxWidth())
                }
            }
            message?.let { Spacer(Modifier.height(12.dp)); MobileMessage(it, onDismissMessage) }
            if (mode == MobileLoginMode.Xtream) {
                if (testSucceeded) {
                    Spacer(Modifier.height(10.dp))
                    Text("Connexion réussie.", color = AccentPinkText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(
                        if (testingConnection) "Test en cours…" else "Tester",
                        enabled = !busy && !testingConnection && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                        onClick = { onTestConnection(server, username, password) },
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        if (busy) "Connexion…" else "Enregistrer",
                        enabled = !busy && !testingConnection && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                        onClick = { onSignIn(editing?.id, name, server, username, password) },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                val hours = refreshHours.toIntOrNull()?.coerceIn(1, 168) ?: 6
                Spacer(Modifier.height(16.dp))
                SecondaryButton("Choisir un fichier .m3u", enabled = !busy, onClick = {
                    pendingId = editing?.id
                    pendingName = name
                    picker.launch(M3uMimeTypes)
                })
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    if (busy) "Import…" else "Importer l'URL",
                    enabled = !busy && m3uUrl.isNotBlank(),
                    onClick = { onImportM3uUrl(editing?.id, name, m3uUrl, xmlTvUrl, hours) },
                )
                editing?.let { profile ->
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Enregistrer les réglages", enabled = !busy, onClick = { onSaveM3uSettings(profile.id, m3uUrl, xmlTvUrl, hours) })
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Renommer", enabled = !busy && name.isNotBlank(), onClick = { onRenameProfile(profile.id, name); showManager() })
                }
            }
        }
    }

    deleteCandidate?.let { profile ->
        MobileBottomSheet("Supprimer « ${profile.name} » ?", subtitle = "Cette liste et son cache seront supprimés de l'appareil.", onDismiss = { deleteCandidate = null }) {
            MobileSheetAction("Supprimer", StreamiaIconGlyph.Delete, tint = Danger, onClick = { deleteCandidate = null; onDeleteProfile(profile.id) })
            MobileSheetAction("Annuler", StreamiaIconGlyph.Close, tint = MutedInk, onClick = { deleteCandidate = null })
        }
    }
}

@Composable
internal fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AccentPill(modifier.fillMaxWidth().height(52.dp).clickable(enabled = enabled, onClick = onClick)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Ink.copy(alpha = if (enabled) 1f else 0.5f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
internal fun SecondaryButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(RadiusPill))
            .background(Color.White.copy(alpha = 0.09f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Ink.copy(alpha = if (enabled) 1f else 0.5f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}

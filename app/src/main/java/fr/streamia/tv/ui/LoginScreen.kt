package fr.streamia.tv.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import fr.streamia.tv.data.PlaylistKind
import fr.streamia.tv.data.PlaylistProfile
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.WarmSignal

private enum class LoginMode { Manager, Xtream, M3u }

@Composable
fun LoginScreen(
    profiles: List<PlaylistProfile>,
    busy: Boolean,
    message: String?,
    onOpenProfile: (String) -> Unit,
    onSignIn: (String?, String, String, String, String) -> Unit,
    onImportM3u: (Uri, String?, String) -> Unit,
    onImportM3uUrl: (String?, String, String, String, Int) -> Unit,
    onSaveM3uSettings: (String, String, String, Int) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    var mode by remember { mutableStateOf(LoginMode.Manager) }
    var editingProfile by remember { mutableStateOf<PlaylistProfile?>(null) }
    var deleteCandidate by remember { mutableStateOf<PlaylistProfile?>(null) }
    var profileName by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var m3uUrl by remember { mutableStateOf("") }
    var xmlTvUrl by remember { mutableStateOf("") }
    var refreshHours by remember { mutableStateOf("6") }
    var pendingM3uId by remember { mutableStateOf<String?>(null) }
    var pendingM3uName by remember { mutableStateOf("") }
    val primaryFocus = remember { FocusRequester() }

    val m3uPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportM3u(uri, pendingM3uId, pendingM3uName)
    }

    fun showManager() {
        mode = LoginMode.Manager
        editingProfile = null
        deleteCandidate = null
        onDismissMessage()
    }

    fun showXtream(profile: PlaylistProfile? = null) {
        editingProfile = profile
        mode = LoginMode.Xtream
        profileName = profile?.name.orEmpty()
        server = profile?.serverUrl.orEmpty()
        username = profile?.username.orEmpty()
        password = profile?.password.orEmpty()
        deleteCandidate = null
        onDismissMessage()
    }

    fun showM3u(profile: PlaylistProfile? = null) {
        editingProfile = profile
        mode = LoginMode.M3u
        profileName = profile?.name.orEmpty()
        m3uUrl = profile?.m3uUrl.orEmpty()
        xmlTvUrl = profile?.xmlTvUrl.orEmpty()
        refreshHours = (profile?.autoRefreshHours ?: 6).toString()
        deleteCandidate = null
        onDismissMessage()
    }

    LaunchedEffect(mode, profiles.size) { runCatching { primaryFocus.requestFocus() } }

    Row(Modifier.fillMaxSize().background(Night)) {
        Column(
            modifier = Modifier.fillMaxHeight().weight(0.32f).background(DeepSurface).padding(horizontal = 42.dp, vertical = 34.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            StreamiaLogo()
            Column {
                Text("Vos chaînes,\nvos listes.", color = Ink, fontSize = 33.sp, lineHeight = 39.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                Text(
                    "Xtream, fichier M3U ou URL M3U distante. XMLTV externe et actualisation automatique sont pris en charge.",
                    color = MutedInk,
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                )
            }
            Text("Données locales · Identifiants chiffrés · HTTP/HTTPS · Télécommande", color = MutedInk, fontSize = 12.sp, lineHeight = 18.sp)
        }

        Box(
            modifier = Modifier.fillMaxHeight().weight(0.68f).padding(horizontal = 42.dp, vertical = 26.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            when (mode) {
                LoginMode.Manager -> PlaylistManager(
                    profiles = profiles,
                    busy = busy,
                    message = message,
                    deleteCandidate = deleteCandidate,
                    primaryFocus = primaryFocus,
                    onOpenProfile = onOpenProfile,
                    onAddXtream = { showXtream() },
                    onAddM3u = { showM3u() },
                    onEdit = { profile -> if (profile.kind == PlaylistKind.Xtream) showXtream(profile) else showM3u(profile) },
                    onAskDelete = { deleteCandidate = it; onDismissMessage() },
                    onCancelDelete = { deleteCandidate = null },
                    onConfirmDelete = { profile -> deleteCandidate = null; onDeleteProfile(profile.id) },
                )

                LoginMode.Xtream -> XtreamForm(
                    editingProfile = editingProfile,
                    profileName = profileName,
                    server = server,
                    username = username,
                    password = password,
                    busy = busy,
                    message = message,
                    primaryFocus = primaryFocus,
                    onNameChange = { profileName = it; onDismissMessage() },
                    onServerChange = { server = it; onDismissMessage() },
                    onUsernameChange = { username = it; onDismissMessage() },
                    onPasswordChange = { password = it; onDismissMessage() },
                    onSave = { onSignIn(editingProfile?.id, profileName, server, username, password) },
                    onBack = ::showManager,
                )

                LoginMode.M3u -> M3uForm(
                    editingProfile = editingProfile,
                    profileName = profileName,
                    m3uUrl = m3uUrl,
                    xmlTvUrl = xmlTvUrl,
                    refreshHours = refreshHours,
                    busy = busy,
                    message = message,
                    primaryFocus = primaryFocus,
                    onNameChange = { profileName = it; onDismissMessage() },
                    onM3uUrlChange = { m3uUrl = it; onDismissMessage() },
                    onXmlTvUrlChange = { xmlTvUrl = it; onDismissMessage() },
                    onRefreshHoursChange = { refreshHours = it.filter(Char::isDigit).take(3); onDismissMessage() },
                    onPickFile = {
                        pendingM3uId = editingProfile?.id
                        pendingM3uName = profileName
                        m3uPicker.launch(M3U_MIME_TYPES)
                    },
                    onSaveRemote = {
                        onImportM3uUrl(
                            editingProfile?.id,
                            profileName,
                            m3uUrl,
                            xmlTvUrl,
                            refreshHours.toIntOrNull()?.coerceIn(1, 168) ?: 6,
                        )
                    },
                    onSaveSettings = {
                        editingProfile?.let {
                            onSaveM3uSettings(
                                it.id,
                                m3uUrl,
                                xmlTvUrl,
                                refreshHours.toIntOrNull()?.coerceIn(1, 168) ?: 6,
                            )
                        }
                    },
                    onRename = {
                        editingProfile?.let { profile ->
                            onRenameProfile(profile.id, profileName)
                            showManager()
                        }
                    },
                    onBack = ::showManager,
                )
            }
        }
    }
}

@Composable
private fun PlaylistManager(
    profiles: List<PlaylistProfile>,
    busy: Boolean,
    message: String?,
    deleteCandidate: PlaylistProfile?,
    primaryFocus: FocusRequester,
    onOpenProfile: (String) -> Unit,
    onAddXtream: () -> Unit,
    onAddM3u: () -> Unit,
    onEdit: (PlaylistProfile) -> Unit,
    onAskDelete: (PlaylistProfile) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: (PlaylistProfile) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Text("Mes listes", style = MaterialTheme.typography.headlineMedium, color = Ink, fontWeight = FontWeight.SemiBold)
        Text(
            if (profiles.isEmpty()) "Aucune liste enregistrée. Ajoutez votre première source."
            else "${profiles.size} liste${if (profiles.size > 1) "s" else ""} enregistrée${if (profiles.size > 1) "s" else ""}.",
            color = MutedInk,
            fontSize = 15.sp,
        )
        if (message != null) Text(message, color = if (message.contains("supprim", true)) FocusBlueBright else MaterialTheme.colorScheme.error, fontSize = 14.sp)

        if (deleteCandidate != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Supprimer « ${deleteCandidate.name} » ?", color = WarmSignal, fontSize = 16.sp, modifier = Modifier.weight(1f))
                SmallAction("Annuler", !busy, onCancelDelete, Modifier.width(110.dp).height(48.dp))
                SmallAction("Supprimer", !busy, { onConfirmDelete(deleteCandidate) }, Modifier.width(120.dp).height(48.dp))
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles, key = PlaylistProfile::id) { profile ->
                Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FocusableSurface(onClick = { onOpenProfile(profile.id) }, enabled = !busy, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.padding(horizontal = 17.dp)) {
                            Text(profile.name, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                when {
                                    profile.kind == PlaylistKind.Xtream -> "XTREAM"
                                    profile.isRemoteM3u -> "M3U URL · auto ${profile.autoRefreshHours}h"
                                    else -> "M3U FICHIER"
                                },
                                color = FocusBlueBright,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    SmallAction("Modifier", !busy, { onEdit(profile) }, Modifier.width(108.dp).fillMaxHeight())
                    SmallAction("Supprimer", !busy, { onAskDelete(profile) }, Modifier.width(110.dp).fillMaxHeight())
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            FocusableSurface(
                onClick = onAddXtream,
                enabled = !busy,
                modifier = Modifier.weight(1f).height(58.dp).focusRequester(primaryFocus),
            ) { Text("＋ Ajouter Xtream", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp)) }
            FocusableSurface(onClick = onAddM3u, enabled = !busy, modifier = Modifier.weight(1f).height(58.dp)) {
                Text("＋ Ajouter M3U / URL", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }
    }
}

@Composable
private fun XtreamForm(
    editingProfile: PlaylistProfile?,
    profileName: String,
    server: String,
    username: String,
    password: String,
    busy: Boolean,
    message: String?,
    primaryFocus: FocusRequester,
    onNameChange: (String) -> Unit,
    onServerChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val canSubmit = server.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !busy
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { Text(if (editingProfile == null) "Ajouter une liste Xtream" else "Modifier la liste Xtream", style = MaterialTheme.typography.headlineMedium, color = Ink, fontWeight = FontWeight.SemiBold) }
            item { Text("HTTP et HTTPS sont acceptés. L'adresse est utilisée exactement comme fournie.", color = MutedInk, fontSize = 14.sp) }
            item { TvTextField(profileName, onNameChange, "Nom de la liste (facultatif)", Modifier.fillMaxWidth().focusRequester(primaryFocus)) }
            item { TvTextField(server, onServerChange, "Adresse du serveur", Modifier.fillMaxWidth(), supportingText = "Exemple : http://serveur.example:8080") }
            item { TvTextField(username, onUsernameChange, "Identifiant", Modifier.fillMaxWidth()) }
            item { TvTextField(password, onPasswordChange, "Mot de passe", Modifier.fillMaxWidth(), PasswordVisualTransformation()) }
            if (message != null) item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            SmallAction("Retour", !busy, onBack, Modifier.weight(0.34f).height(60.dp))
            FocusableSurface(onClick = onSave, enabled = canSubmit, modifier = Modifier.weight(0.66f).height(60.dp)) {
                Text(if (busy) "Connexion…" else "Enregistrer et ouvrir", color = if (canSubmit || busy) Ink else MutedInk, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }
    }
}

@Composable
private fun M3uForm(
    editingProfile: PlaylistProfile?,
    profileName: String,
    m3uUrl: String,
    xmlTvUrl: String,
    refreshHours: String,
    busy: Boolean,
    message: String?,
    primaryFocus: FocusRequester,
    onNameChange: (String) -> Unit,
    onM3uUrlChange: (String) -> Unit,
    onXmlTvUrlChange: (String) -> Unit,
    onRefreshHoursChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onSaveRemote: () -> Unit,
    onSaveSettings: () -> Unit,
    onRename: () -> Unit,
    onBack: () -> Unit,
) {
    val canSaveRemote = m3uUrl.isNotBlank() && !busy
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { Text(if (editingProfile == null) "Ajouter une liste M3U" else "Modifier la liste M3U", style = MaterialTheme.typography.headlineMedium, color = Ink, fontWeight = FontWeight.SemiBold) }
            item { Text("Vous pouvez choisir un fichier local ou saisir une URL M3U distante. Une URL XMLTV est facultative ; sinon Streamia essaie l'EPG du fournisseur Xtream.", color = MutedInk, fontSize = 14.sp, lineHeight = 20.sp) }
            item { TvTextField(profileName, onNameChange, "Nom de la liste (facultatif)", Modifier.fillMaxWidth().focusRequester(primaryFocus)) }
            item { TvTextField(m3uUrl, onM3uUrlChange, "URL M3U distante (facultatif)", Modifier.fillMaxWidth(), supportingText = "http:// ou https:// · actualisation automatique") }
            item { TvTextField(xmlTvUrl, onXmlTvUrlChange, "URL XMLTV / EPG (facultatif)", Modifier.fillMaxWidth()) }
            item { TvTextField(refreshHours, onRefreshHoursChange, "Actualisation automatique (heures)", Modifier.fillMaxWidth(), supportingText = "1 à 168 heures · valeur recommandée : 6") }
            if (message != null) item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusableSurface(onClick = onPickFile, enabled = !busy, modifier = Modifier.weight(1f).height(58.dp)) {
                        Text(if (editingProfile?.m3uUri == null) "Choisir un fichier local" else "Remplacer le fichier local", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    FocusableSurface(onClick = onSaveRemote, enabled = canSaveRemote, modifier = Modifier.weight(1f).height(58.dp)) {
                        Text(if (busy) "Import…" else "Enregistrer URL et ouvrir", color = if (canSaveRemote || busy) Ink else MutedInk, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
            if (editingProfile != null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallAction("Enregistrer EPG / fréquence", !busy, onSaveSettings, Modifier.weight(1f).height(54.dp))
                        SmallAction("Enregistrer le nom", !busy && profileName.isNotBlank(), onRename, Modifier.weight(1f).height(54.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SmallAction("Retour aux listes", !busy, onBack, Modifier.fillMaxWidth().height(56.dp))
    }
}

@Composable
private fun SmallAction(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(label, color = if (enabled) Ink else MutedInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

private val M3U_MIME_TYPES = arrayOf(
    "audio/x-mpegurl",
    "application/x-mpegURL",
    "application/vnd.apple.mpegurl",
    "text/plain",
    "*/*",
)

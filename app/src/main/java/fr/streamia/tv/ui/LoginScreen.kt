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
        profileName = ""
        server = ""
        username = ""
        password = ""
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
        deleteCandidate = null
        onDismissMessage()
    }

    LaunchedEffect(mode, profiles.size) {
        runCatching { primaryFocus.requestFocus() }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(Night),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.34f)
                .background(DeepSurface)
                .padding(horizontal = 46.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            StreamiaLogo()
            Column {
                Text(
                    "Vos chaînes,\nvos listes.",
                    color = Ink,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Ajoutez plusieurs abonnements Xtream ou fichiers M3U, puis ouvrez, modifiez ou supprimez chaque liste depuis cet écran.",
                    color = MutedInk,
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                )
            }
            Text(
                "Données locales · Identifiants Xtream chiffrés · Télécommande",
                color = MutedInk,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.66f)
                .padding(horizontal = 46.dp, vertical = 28.dp),
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
                    onEdit = { profile ->
                        if (profile.kind == PlaylistKind.Xtream) showXtream(profile) else showM3u(profile)
                    },
                    onAskDelete = { deleteCandidate = it; onDismissMessage() },
                    onCancelDelete = { deleteCandidate = null },
                    onConfirmDelete = { profile ->
                        deleteCandidate = null
                        onDeleteProfile(profile.id)
                    },
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
                    busy = busy,
                    message = message,
                    primaryFocus = primaryFocus,
                    onNameChange = { profileName = it; onDismissMessage() },
                    onPickFile = {
                        pendingM3uId = editingProfile?.id
                        pendingM3uName = profileName
                        m3uPicker.launch(M3U_MIME_TYPES)
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mes listes", style = MaterialTheme.typography.headlineMedium, color = Ink, fontWeight = FontWeight.SemiBold)
        Text(
            if (profiles.isEmpty()) "Aucune liste enregistrée. Ajoutez votre première source."
            else "${profiles.size} liste${if (profiles.size > 1) "s" else ""} enregistrée${if (profiles.size > 1) "s" else ""}.",
            color = MutedInk,
            fontSize = 16.sp,
        )

        if (message != null) {
            Text(
                message,
                color = if (message.contains("supprim", ignoreCase = true)) FocusBlueBright else MaterialTheme.colorScheme.error,
                fontSize = 15.sp,
            )
        }

        if (deleteCandidate != null) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Supprimer « ${deleteCandidate.name} » ?", color = WarmSignal, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallAction("Annuler", enabled = !busy, onClick = onCancelDelete, modifier = Modifier.weight(1f).height(52.dp))
                    SmallAction("Supprimer", enabled = !busy, onClick = { onConfirmDelete(deleteCandidate) }, modifier = Modifier.weight(1f).height(52.dp))
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(profiles, key = PlaylistProfile::id) { profile ->
                PlaylistRow(
                    profile = profile,
                    busy = busy,
                    onOpen = { onOpenProfile(profile.id) },
                    onEdit = { onEdit(profile) },
                    onDelete = { onAskDelete(profile) },
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusableSurface(
                onClick = onAddXtream,
                enabled = !busy,
                contentDescription = "Ajouter une liste Xtream",
                modifier = Modifier.weight(1f).height(58.dp).focusRequester(primaryFocus),
            ) {
                Text("＋ Ajouter Xtream", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp))
            }
            FocusableSurface(
                onClick = onAddM3u,
                enabled = !busy,
                contentDescription = "Ajouter une liste M3U",
                modifier = Modifier.weight(1f).height(58.dp),
            ) {
                Text("＋ Ajouter M3U", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    profile: PlaylistProfile,
    busy: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FocusableSurface(
            onClick = onOpen,
            enabled = !busy,
            contentDescription = "Ouvrir ${profile.name}",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Text(profile.name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    if (profile.kind == PlaylistKind.Xtream) "XTREAM" else "M3U",
                    color = FocusBlueBright,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        SmallAction("Modifier", enabled = !busy, onClick = onEdit, modifier = Modifier.width(108.dp).fillMaxHeight())
        SmallAction("Supprimer", enabled = !busy, onClick = onDelete, modifier = Modifier.width(110.dp).fillMaxHeight())
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
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            item {
                Text(
                    if (editingProfile == null) "Ajouter une liste Xtream" else "Modifier la liste Xtream",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Ink,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                Text(
                    "Le nom est libre. L'adresse, l'identifiant et le mot de passe sont nécessaires pour ouvrir la liste.",
                    color = MutedInk,
                    fontSize = 15.sp,
                )
            }
            item {
                TvTextField(
                    value = profileName,
                    onValueChange = onNameChange,
                    label = "Nom de la liste (facultatif)",
                    modifier = Modifier.fillMaxWidth().focusRequester(primaryFocus),
                )
            }
            item {
                TvTextField(
                    value = server,
                    onValueChange = onServerChange,
                    label = "Adresse du serveur",
                    supportingText = "Exemple : https://serveur.example:8080",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                TvTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = "Identifiant",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                TvTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = "Mot de passe",
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (server.trim().startsWith("http://", ignoreCase = true)) {
                item {
                    Text(
                        "Attention : ce serveur utilise une connexion non chiffrée (HTTP).",
                        color = WarmSignal,
                        fontSize = 14.sp,
                    )
                }
            }
            if (message != null) {
                item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 15.sp) }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallAction(
                "Retour",
                enabled = !busy,
                onClick = onBack,
                modifier = Modifier.weight(0.34f).height(60.dp),
            )
            FocusableSurface(
                onClick = onSave,
                enabled = canSubmit,
                contentDescription = "Enregistrer et ouvrir la liste Xtream",
                modifier = Modifier.weight(0.66f).height(60.dp),
            ) {
                Text(
                    if (busy) "Connexion en cours…" else "Enregistrer et ouvrir",
                    color = if (canSubmit || busy) Ink else MutedInk,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun M3uForm(
    editingProfile: PlaylistProfile?,
    profileName: String,
    busy: Boolean,
    message: String?,
    primaryFocus: FocusRequester,
    onNameChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onRename: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    if (editingProfile == null) "Ajouter une liste M3U" else "Modifier la liste M3U",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Ink,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                Text(
                    "Le fichier est lu localement et son accès est conservé pour pouvoir rouvrir cette liste après redémarrage.",
                    color = MutedInk,
                    fontSize = 15.sp,
                )
            }
            item {
                TvTextField(
                    value = profileName,
                    onValueChange = onNameChange,
                    label = "Nom de la liste (facultatif)",
                    modifier = Modifier.fillMaxWidth().focusRequester(primaryFocus),
                )
            }
            if (message != null) {
                item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 15.sp) }
            }
            item {
                FocusableSurface(
                    onClick = onPickFile,
                    enabled = !busy,
                    contentDescription = if (editingProfile == null) "Choisir un fichier M3U" else "Remplacer le fichier M3U",
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) {
                    Text(
                        if (editingProfile == null) "Choisir un fichier M3U" else "Remplacer le fichier M3U",
                        color = Ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
            }
            if (editingProfile != null) {
                item {
                    SmallAction(
                        "Enregistrer uniquement le nouveau nom",
                        enabled = !busy && profileName.isNotBlank(),
                        onClick = onRename,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        SmallAction(
            "Retour aux listes",
            enabled = !busy,
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(58.dp),
        )
    }
}

@Composable
private fun SmallAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(
            label,
            color = if (enabled) Ink else MutedInk,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}

private val M3U_MIME_TYPES = arrayOf(
    "audio/x-mpegurl",
    "application/x-mpegURL",
    "application/vnd.apple.mpegurl",
    "text/plain",
    "*/*",
)

package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.UpdateCheckResult
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ToolsScreen(
    busy: Boolean,
    liveHistoryCount: Int,
    movieHistoryCount: Int,
    seriesHistoryCount: Int,
    currentVersion: String,
    updateChecking: Boolean,
    updateCheck: UpdateCheckResult?,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onOrganizer: () -> Unit,
    onRefresh: () -> Unit,
    onClearLiveHistory: () -> Unit,
    onClearMovieHistory: () -> Unit,
    onClearSeriesHistory: () -> Unit,
    onClearAllHistory: () -> Unit,
    onChangePlaylist: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onDismissUpdateCheck: () -> Unit,
    onExportBackup: suspend () -> String,
    onImportBackup: suspend (String) -> String,
    onAbout: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val firstFocus = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backupMessage by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = onExportBackup()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("Impossible d'écrire à cet emplacement.")
            }.onSuccess { backupMessage = "Sauvegarde enregistrée." }
                .onFailure { error -> backupMessage = "Échec de la sauvegarde : ${error.message ?: "erreur inconnue"}." }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalStateException("Fichier illisible.")
                onImportBackup(json)
            }.onSuccess { message -> backupMessage = message }
                .onFailure { error -> backupMessage = "Échec de la restauration : ${error.message ?: "fichier invalide"}." }
        }
    }

    Column(Modifier.fillMaxSize().background(Night).padding(horizontal = 42.dp, vertical = 28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StreamiaLogo(compact = true)
            Spacer(Modifier.weight(1f))
            Text("Outils et gestion", color = Ink, fontSize = 27.sp, fontWeight = HeadingWeight)
        }
        Spacer(Modifier.height(18.dp))
        Text("OK ouvre l'outil sélectionné. Retour revient aux paramètres.", color = MutedInk, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))

        // weight(1f) plutôt que fillMaxSize() : cette grille de tuiles n'est plus le dernier élément
        // de la colonne — le bandeau de résultat de mise à jour, sous condition, doit garder sa place
        // sous elle plutôt que de se retrouver sans hauteur disponible (fillMaxSize aurait tout pris).
        // Lignes à hauteur fixe (150dp, comme ParentalControlScreen) + défilement plutôt que
        // weight(1f) par ligne : sur un écran TV plus bas, répartir la hauteur disponible en 5
        // lignes pouvait laisser moins de place par ligne que ce dont une tuile a besoin, et le
        // surplus se retrouvait découpé silencieusement par FocusableSurface (voir SettingsScreen).
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(StreamiaIconGlyph.Search, "Recherche", "Chaînes, films et séries", onSearch, Modifier.focusRequester(firstFocus).weight(1f))
                SettingsTile(StreamiaIconGlyph.Guide, "Guide TV", "EPG et grille des chaînes", onEpg, Modifier.weight(1f))
                SettingsTile(StreamiaIconGlyph.Reorder, "Organiser", "Catégories et contenus", onOrganizer, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(StreamiaIconGlyph.Refresh, if (busy) "Actualisation…" else "Actualiser", "Recharge la liste et le catalogue", onRefresh, Modifier.weight(1f), enabled = !busy)
                SettingsTile(StreamiaIconGlyph.Swap, "Changer de liste", "Gestionnaire de playlists", onChangePlaylist, Modifier.weight(1f))
                SettingsTile(StreamiaIconGlyph.Delete, "Historique Direct", "$liveHistoryCount élément(s)", onClearLiveHistory, Modifier.weight(1f), enabled = liveHistoryCount > 0)
            }
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(StreamiaIconGlyph.Delete, "Historique Films", "$movieHistoryCount élément(s)", onClearMovieHistory, Modifier.weight(1f), enabled = movieHistoryCount > 0)
                SettingsTile(StreamiaIconGlyph.Delete, "Historique Séries", "$seriesHistoryCount élément(s)", onClearSeriesHistory, Modifier.weight(1f), enabled = seriesHistoryCount > 0)
                SettingsTile(
                    StreamiaIconGlyph.Delete,
                    "Effacer tout l'historique",
                    "${liveHistoryCount + movieHistoryCount + seriesHistoryCount} élément(s)",
                    onClearAllHistory,
                    Modifier.weight(1f),
                    enabled = liveHistoryCount + movieHistoryCount + seriesHistoryCount > 0,
                )
            }
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(
                    StreamiaIconGlyph.Refresh,
                    if (updateChecking) "Vérification…" else "Vérifier les mises à jour",
                    updateSubtitle(currentVersion, updateCheck),
                    onCheckForUpdate,
                    Modifier.weight(1f),
                    enabled = !updateChecking,
                )
                Spacer(Modifier.weight(2f))
            }
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(
                    StreamiaIconGlyph.Swap,
                    "Sauvegarder les réglages",
                    "Préférences et réglages de lecture, hors identifiants de connexion",
                    { exportLauncher.launch(backupFileName()) },
                    Modifier.weight(1f),
                )
                SettingsTile(
                    StreamiaIconGlyph.Swap,
                    "Restaurer les réglages",
                    "Depuis un fichier de sauvegarde exporté précédemment",
                    { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    Modifier.weight(1f),
                )
                SettingsTile(
                    StreamiaIconGlyph.Settings,
                    "À propos",
                    "Version, appareil, taille du cache",
                    onAbout,
                    Modifier.weight(1f),
                )
            }
        }

        if (updateCheck != null) {
            Spacer(Modifier.height(12.dp))
            FocusableSurface(onClick = onDismissUpdateCheck, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text(updateResultTitle(updateCheck), color = Ink, fontSize = 14.sp, fontWeight = HeadingWeight)
                    Text(updateResultSubtitle(updateCheck), color = MutedInk, fontSize = 12.sp, maxLines = 2)
                }
            }
        }

        backupMessage?.let { message ->
            Spacer(Modifier.height(12.dp))
            FocusableSurface(onClick = { backupMessage = null }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(
                    "$message  ·  OK pour fermer",
                    color = Ink,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        }
    }
}

private fun backupFileName(): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.FRANCE).format(Date())
    return "streamia-sauvegarde-$stamp.json"
}

private fun updateSubtitle(currentVersion: String, result: UpdateCheckResult?): String = when (result) {
    is UpdateCheckResult.UpdateAvailable -> "Nouvelle version ${result.release.version} disponible"
    is UpdateCheckResult.UpToDate -> "À jour · version $currentVersion"
    is UpdateCheckResult.NoTaggedRelease -> "Aucune version publiée pour l'instant"
    is UpdateCheckResult.Error -> result.message
    null -> "Version actuelle : $currentVersion"
}

private fun updateResultTitle(result: UpdateCheckResult): String = when (result) {
    is UpdateCheckResult.UpdateAvailable -> "Nouvelle version disponible : ${result.release.version}"
    is UpdateCheckResult.UpToDate -> "Vous avez la dernière version"
    is UpdateCheckResult.NoTaggedRelease -> "Aucune version publiée"
    is UpdateCheckResult.Error -> "Vérification impossible"
}

private fun updateResultSubtitle(result: UpdateCheckResult): String = when (result) {
    is UpdateCheckResult.UpdateAvailable -> result.release.notes.ifBlank { "Ouvrez ${result.release.htmlUrl} pour télécharger." }
        .take(180)
    is UpdateCheckResult.UpToDate -> "OK pour fermer."
    is UpdateCheckResult.NoTaggedRelease -> "Le mainteneur n'a pas encore publié de release taguée sur GitHub."
    is UpdateCheckResult.Error -> result.message
}

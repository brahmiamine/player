package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night

@Composable
fun SettingsScreen(
    busy: Boolean,
    historyCount: Int,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onOrganizer: () -> Unit,
    onRefresh: () -> Unit,
    onClearHistory: () -> Unit,
    onChangePlaylist: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val firstFocus = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Night).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StreamiaLogo(compact = true)
            Spacer(Modifier.weight(1f))
            Text("Paramètres et outils", color = Ink, fontSize = 27.sp, fontWeight = HeadingWeight)
        }
        Spacer(Modifier.height(26.dp))
        Text(
            "Utilisez OK pour ouvrir. Retour revient à l'accueil.",
            color = MutedInk,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsTile(StreamiaIconGlyph.Search, "Recherche", "Rechercher dans toutes les chaînes, films et séries", onSearch, Modifier.focusRequester(firstFocus).weight(1f))
                SettingsTile(StreamiaIconGlyph.Guide, "Guide TV", "Programmes EPG et grille des chaînes", onEpg, Modifier.weight(1f))
                SettingsTile(StreamiaIconGlyph.Reorder, "Organiser", "Réordonner les catégories et déplacer des contenus", onOrganizer, Modifier.weight(1f))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsTile(StreamiaIconGlyph.Refresh, if (busy) "Actualisation…" else "Actualiser", "Recharge la playlist et son catalogue", onRefresh, Modifier.weight(1f), enabled = !busy)
                SettingsTile(StreamiaIconGlyph.Delete, "Effacer l'historique", "$historyCount élément(s) dans l'historique", onClearHistory, Modifier.weight(1f), enabled = historyCount > 0)
                SettingsTile(StreamiaIconGlyph.Swap, "Changer de liste", "Retourner au gestionnaire de playlists", onChangePlaylist, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SettingsTile(
    glyph: StreamiaIconGlyph,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
) {
    FocusableSurface(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            StreamiaIcon(glyph, size = 34.dp)
            Spacer(Modifier.height(12.dp))
            Text(title, color = Ink, fontSize = 20.sp, fontWeight = HeadingWeight)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = MutedInk, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

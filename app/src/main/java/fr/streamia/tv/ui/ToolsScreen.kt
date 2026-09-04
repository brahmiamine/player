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
fun ToolsScreen(
    busy: Boolean,
    liveHistoryCount: Int,
    movieHistoryCount: Int,
    seriesHistoryCount: Int,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onOrganizer: () -> Unit,
    onRefresh: () -> Unit,
    onClearLiveHistory: () -> Unit,
    onClearMovieHistory: () -> Unit,
    onClearSeriesHistory: () -> Unit,
    onClearAllHistory: () -> Unit,
    onChangePlaylist: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val firstFocus = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Night).padding(horizontal = 42.dp, vertical = 28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StreamiaLogo(compact = true)
            Spacer(Modifier.weight(1f))
            Text("Outils et gestion", color = Ink, fontSize = 27.sp, fontWeight = HeadingWeight)
        }
        Spacer(Modifier.height(18.dp))
        Text("OK ouvre l'outil sélectionné. Retour revient aux paramètres.", color = MutedInk, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(StreamiaIconGlyph.Search, "Recherche", "Chaînes, films et séries", onSearch, Modifier.focusRequester(firstFocus).weight(1f))
                SettingsTile(StreamiaIconGlyph.Guide, "Guide TV", "EPG et grille des chaînes", onEpg, Modifier.weight(1f))
                SettingsTile(StreamiaIconGlyph.Reorder, "Organiser", "Catégories et contenus", onOrganizer, Modifier.weight(1f))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(StreamiaIconGlyph.Refresh, if (busy) "Actualisation…" else "Actualiser", "Recharge la liste et le catalogue", onRefresh, Modifier.weight(1f), enabled = !busy)
                SettingsTile(StreamiaIconGlyph.Swap, "Changer de liste", "Gestionnaire de playlists", onChangePlaylist, Modifier.weight(1f))
                SettingsTile(StreamiaIconGlyph.Delete, "Historique Direct", "$liveHistoryCount élément(s)", onClearLiveHistory, Modifier.weight(1f), enabled = liveHistoryCount > 0)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
        }
    }
}

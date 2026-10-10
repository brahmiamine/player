package fr.streamia.tv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.tv.material3.Text
import fr.streamia.tv.ui.StreamiaIcon
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.FocusBlue
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk

private data class MoreRow(val label: String, val subtitle: String, val glyph: StreamiaIconGlyph, val onClick: () -> Unit)

/** Onglet « Plus » : accès à la recherche, au guide, aux matchs, à Organiser, aux réglages et au changement de liste. */
@Composable
fun MobileMoreScreen(
    playlistName: String?,
    versionName: String,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onLiveMatches: () -> Unit,
    onOrganizer: () -> Unit,
    onSettings: () -> Unit,
    onChangePlaylist: () -> Unit,
    aiActive: Boolean = false,
    onAssistant: (fr.streamia.tv.ui.AssistantMode) -> Unit = {},
) {
    val rows = (if (aiActive) fr.streamia.tv.ui.AssistantMode.entries.map { mode ->
        MoreRow("✦ ${mode.title}", "Assistant IA", StreamiaIconGlyph.Search) { onAssistant(mode) }
    } else emptyList()) + listOf(
        MoreRow("Recherche", "Tout le catalogue, filtrable", StreamiaIconGlyph.Search, onSearch),
        MoreRow("Guide TV", "Grille EPG, jour par jour", StreamiaIconGlyph.Guide, onEpg),
        MoreRow("Matchs du jour", "Scores et chaînes", StreamiaIconGlyph.Trophy, onLiveMatches),
        MoreRow("Organiser", "Réordonner, masquer, verrouiller", StreamiaIconGlyph.Reorder, onOrganizer),
        MoreRow("Paramètres", "Accueil, lecture, parental…", StreamiaIconGlyph.Settings, onSettings),
        MoreRow("Changer de liste", "Gestionnaire de playlists", StreamiaIconGlyph.Swap, onChangePlaylist),
    )
    Column(Modifier.fillMaxSize()) {
        MobileHeader("Plus")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = MobileGutter, end = MobileGutter, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.label }) { row ->
                MobileCard(Modifier.fillMaxWidth().height(68.dp), onClick = row.onClick) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(FocusBlue),
                            contentAlignment = Alignment.Center,
                        ) { StreamiaIcon(row.glyph, tint = AccentPink, size = 22.dp) }
                        Column(Modifier.weight(1f)) {
                            Text(row.label, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                            Text(row.subtitle, color = MutedInk, fontSize = 12.sp, maxLines = 1)
                        }
                        StreamiaIcon(StreamiaIconGlyph.ArrowForward, tint = MutedInk, size = 18.dp)
                    }
                }
            }
            item {
                Text(
                    "Liste active : ${playlistName ?: "—"} · Streamia v$versionName",
                    color = MutedInk,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp),
                )
            }
        }
    }
}

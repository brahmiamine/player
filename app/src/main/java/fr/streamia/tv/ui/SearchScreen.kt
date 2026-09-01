package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.TypeBody
import fr.streamia.tv.ui.theme.TypeLabel
import fr.streamia.tv.ui.theme.TypeScreenTitle
import fr.streamia.tv.ui.theme.TypeSectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun SearchScreen(
    catalog: Catalog,
    favoriteEntries: Set<String>,
    onOpenEntry: (MediaEntry) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<MediaType?>(null) }
    val needle = query.trim().lowercase()
    val entries by produceState(emptyList<MediaEntry>(), catalog, needle, type) {
        if (needle.isBlank()) {
            value = emptyList()
            return@produceState
        }
        delay(220)
        value = withContext(Dispatchers.Default) { catalog.search(needle, type, limit = 600) }
    }

    Column(Modifier.fillMaxSize().background(Night).padding(28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FocusableSurface(onClick = onBack, modifier = Modifier.width(120.dp).height(52.dp)) {
                Text("← Retour", color = Ink, fontSize = TypeLabel, modifier = Modifier.padding(horizontal = 14.dp))
            }
            Spacer(Modifier.width(18.dp))
            Text("Recherche globale", color = Ink, fontSize = TypeScreenTitle, fontWeight = HeadingWeight)
            Spacer(Modifier.weight(1f))
            Text("${entries.size} résultats", color = MutedInk, fontSize = TypeLabel)
        }
        Spacer(Modifier.height(18.dp))
        TvTextField(
            value = query,
            onValueChange = { query = it },
            label = "Rechercher une chaîne, un film ou une série",
            supportingText = "Le clavier Android TV peut être utilisé. Recherche instantanée dans tout le catalogue.",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            SearchFilter("Tout", type == null) { type = null }
            MediaType.entries.forEach { mediaType ->
                SearchFilter(mediaType.displayName, type == mediaType) { type = mediaType }
            }
        }
        Spacer(Modifier.height(18.dp))

        if (needle.isBlank()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Tapez quelques lettres pour rechercher dans tout Streamia.", color = MutedInk, fontSize = TypeSectionTitle)
                Spacer(Modifier.height(8.dp))
                Text("Live · Films · Séries", color = FocusBlueBright, fontSize = TypeLabel)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SectionLabel("Contenus (${entries.size})")
                Spacer(Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = MediaEntry::key) { entry ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FocusableSurface(
                                onClick = { onOpenEntry(entry) },
                                modifier = Modifier.weight(1f).height(76.dp),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ChannelLogo(entry.iconUrl, entry.displayName, Modifier.size(52.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.displayName, color = Ink, fontSize = TypeBody, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${entry.type.displayName} · ${if (entry.type == MediaType.Live) "CH ${entry.number}" else entry.extension.uppercase()}",
                                            color = MutedInk,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                            FocusableSurface(
                                onClick = { onToggleEntryFavorite(entry) },
                                selected = entry.key in favoriteEntries,
                                modifier = Modifier.width(64.dp).height(76.dp),
                                contentDescription = if (entry.key in favoriteEntries) {
                                    "Retirer ${entry.displayName} des favoris"
                                } else {
                                    "Ajouter ${entry.displayName} aux favoris"
                                },
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    StreamiaIcon(
                                        if (entry.key in favoriteEntries) StreamiaIconGlyph.Star else StreamiaIconGlyph.StarOutline,
                                        size = 22.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusableSurface(onClick = onClick, selected = selected, modifier = Modifier.width(122.dp).height(46.dp)) {
        Text(label, color = Ink, fontSize = TypeLabel, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

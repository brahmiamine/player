package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.SeriesDetails
import fr.streamia.tv.domain.SeriesEpisode
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import kotlinx.coroutines.yield

@Composable
fun SeriesScreen(
    series: MediaEntry,
    details: SeriesDetails?,
    busy: Boolean,
    message: String?,
    onEpisodeSelected: (SeriesEpisode) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var selectedSeason by remember(series.key, details) {
        mutableIntStateOf(details?.seasons?.firstOrNull() ?: 1)
    }
    val episodes = remember(details, selectedSeason) { details?.episodesIn(selectedSeason).orEmpty() }
    val firstFocus = remember(selectedSeason) { FocusRequester() }

    LaunchedEffect(episodes.size) {
        if (episodes.isNotEmpty()) {
            yield()
            runCatching { firstFocus.requestFocus() }
        }
    }

    Row(Modifier.fillMaxSize().background(Night).padding(34.dp)) {
        Column(Modifier.width(360.dp).fillMaxHeight()) {
            FocusableSurface(onClick = onBack, modifier = Modifier.width(130.dp).height(48.dp)) {
                Text("← Retour", color = Ink, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 15.dp))
            }
            Spacer(Modifier.height(24.dp))
            ChannelLogo(series.iconUrl, series.name, Modifier.size(220.dp))
            Spacer(Modifier.height(18.dp))
            Text(series.name, color = Ink, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
            if (series.rating != null) {
                Spacer(Modifier.height(8.dp))
                Text("★ ${"%.1f".format(series.rating)}", color = FocusBlueBright, fontSize = 16.sp)
            }
            if (!series.plot.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(series.plot, color = MutedInk, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(34.dp))
        when {
            busy -> Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("Chargement des saisons et épisodes…", color = MutedInk, fontSize = 20.sp)
            }
            details == null -> Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                FocusableSurface(onClick = onRetry, modifier = Modifier.width(520.dp).height(110.dp)) {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Text(message ?: "Les épisodes ne sont pas disponibles.", color = Ink, fontSize = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("OK pour réessayer", color = FocusBlueBright, fontSize = 14.sp)
                    }
                }
            }
            details.episodes.isEmpty() -> Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("Aucun épisode fourni par ce serveur.", color = MutedInk, fontSize = 20.sp)
            }
            else -> Column(Modifier.weight(1f).fillMaxHeight()) {
                Text("Saisons", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.width(190.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    items(details.seasons, key = { it }) { season ->
                        FocusableSurface(
                            onClick = { selectedSeason = season },
                            selected = selectedSeason == season,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Text("Saison $season", color = Ink, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
        if (details != null && details.episodes.isNotEmpty()) {
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text("Saison $selectedSeason", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Text("${episodes.size} épisodes", color = MutedInk, fontSize = 15.sp)
                }
                Spacer(Modifier.height(14.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(280.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(episodes, key = { it.id }) { episode ->
                        FocusableSurface(
                            onClick = { onEpisodeSelected(episode) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .then(if (episode.id == episodes.first().id) Modifier.focusRequester(firstFocus) else Modifier),
                        ) {
                            Column(Modifier.padding(15.dp)) {
                                Text(
                                    "Épisode ${episode.number}",
                                    color = FocusBlueBright,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    episode.title,
                                    color = Ink,
                                    fontSize = 17.sp,
                                    lineHeight = 21.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!episode.duration.isNullOrBlank()) {
                                    Spacer(Modifier.height(5.dp))
                                    Text(episode.duration, color = MutedInk, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

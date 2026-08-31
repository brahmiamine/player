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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onEpisodeSelected: (SeriesEpisode) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var selectedSeason by remember(series.key, details) { mutableIntStateOf(details?.seasons?.firstOrNull() ?: 1) }
    val episodes = remember(details, selectedSeason) { details?.episodesIn(selectedSeason).orEmpty() }
    val firstFocus = remember(selectedSeason) { FocusRequester() }

    LaunchedEffect(selectedSeason, episodes.size) {
        if (episodes.isNotEmpty()) {
            yield()
            runCatching { firstFocus.requestFocus() }
        }
    }

    Row(Modifier.fillMaxSize().background(Night).padding(30.dp)) {
        Column(Modifier.width(365.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusableSurface(onClick = onBack, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("← Retour", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 15.dp))
                }
                FocusableSurface(onClick = onToggleFavorite, selected = favorite, modifier = Modifier.width(68.dp).height(48.dp)) {
                    Text(if (favorite) "★" else "☆", color = FocusBlueBright, fontSize = 23.sp, modifier = Modifier.padding(start = 20.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            ChannelLogo(details?.details?.posterUrl ?: series.iconUrl, series.name, Modifier.size(230.dp))
            Spacer(Modifier.height(16.dp))
            Text(series.name, color = Ink, fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold)
            val info = details?.details
            val meta = listOfNotNull(
                info?.rating?.let { "★ ${"%.1f".format(it)}" } ?: series.rating?.let { "★ ${"%.1f".format(it)}" },
                info?.releaseDate,
                info?.genre,
                details?.seasons?.size?.let { "$it saisons" },
                details?.episodes?.size?.let { "$it épisodes" },
            )
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(meta.joinToString(" · "), color = FocusBlueBright, fontSize = 13.sp, lineHeight = 19.sp)
            }
            val plot = info?.plot ?: series.plot
            if (!plot.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(plot, color = MutedInk, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 12, overflow = TextOverflow.Ellipsis)
            }
            SeriesInfoLine("Réalisateur", info?.director)
            SeriesInfoLine("Distribution", info?.cast)
            SeriesInfoLine("Pays", info?.country)
            SeriesInfoLine("Bande-annonce", info?.youtubeTrailer)
            Spacer(Modifier.height(28.dp))
        }
        Spacer(Modifier.width(28.dp))

        when {
            busy -> Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("Chargement des informations, saisons et épisodes…", color = MutedInk, fontSize = 19.sp)
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
            else -> {
                Column(Modifier.width(185.dp).fillMaxHeight()) {
                    Text("Saisons", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(11.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(details.seasons, key = { it }) { season ->
                            FocusableSurface(
                                onClick = { selectedSeason = season },
                                selected = selectedSeason == season,
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                            ) {
                                Text("Saison $season", color = Ink, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 15.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Text("Saison $selectedSeason", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Text("${episodes.size} épisodes", color = MutedInk, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(285.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(episodes, key = { it.id }) { episode ->
                            FocusableSurface(
                                onClick = { onEpisodeSelected(episode) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(128.dp)
                                    .then(if (episode.id == episodes.first().id) Modifier.focusRequester(firstFocus) else Modifier),
                            ) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    MediaArtwork(
                                        episode.iconUrl ?: details.details?.posterUrl ?: series.iconUrl,
                                        episode.title,
                                        Modifier.width(150.dp).aspectRatio(16f / 9f),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Text("S${episode.season.toString().padStart(2, '0')}E${episode.number.toString().padStart(2, '0')}", color = FocusBlueBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.weight(1f))
                                        episode.rating?.let { Text("★ ${"%.1f".format(it)}", color = FocusBlueBright, fontSize = 11.sp) }
                                    }
                                    Spacer(Modifier.height(5.dp))
                                    Text(episode.title, color = Ink, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(5.dp))
                                    Text(listOfNotNull(episode.duration, episode.releaseDate).joinToString(" · "), color = MutedInk, fontSize = 11.sp, maxLines = 1)
                                    }
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
private fun SeriesInfoLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Spacer(Modifier.height(8.dp))
    Text(label, color = MutedInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Text(value, color = Ink, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
}

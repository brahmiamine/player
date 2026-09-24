package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import fr.streamia.tv.data.PlaybackHistoryItem
import fr.streamia.tv.data.isResumable
import fr.streamia.tv.domain.MediaType
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.graphics.Color
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.SeriesDetails
import fr.streamia.tv.domain.SeriesEpisode
import fr.streamia.tv.recommendation.RecommendedMedia
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.TypeBody
import fr.streamia.tv.ui.theme.TypeBodyLineHeight
import fr.streamia.tv.ui.theme.TypeHero
import fr.streamia.tv.ui.theme.TypeHeroLineHeight
import fr.streamia.tv.ui.theme.TypeLabel
import fr.streamia.tv.ui.theme.TypeSectionTitle
import kotlinx.coroutines.yield

@Composable
fun SeriesScreen(
    series: MediaEntry,
    details: SeriesDetails?,
    busy: Boolean,
    message: String?,
    favorite: Boolean,
    watched: Boolean,
    similarMedia: List<RecommendedMedia> = emptyList(),
    otherVersions: List<RecommendedMedia> = emptyList(),
    /** Historique de lecture des épisodes, du plus récent au plus ancien. */
    episodeHistory: List<PlaybackHistoryItem> = emptyList(),
    onToggleFavorite: () -> Unit,
    onToggleWatched: () -> Unit,
    onEpisodeSelected: (SeriesEpisode) -> Unit,
    onOpenSimilar: (MediaEntry) -> Unit = {},
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // Progression par épisode (clé = id d'épisode : les épisodes lus sont enregistrés sous cet id).
    val historyByEpisode = remember(episodeHistory) {
        episodeHistory.filter { it.entry.type == MediaType.Series }.associateBy { it.entry.id }
    }
    // Dernier épisode lu de cette série : la fiche s'ouvre sur sa saison et lui donne le focus, au
    // lieu de repartir de S01E01 à chaque retour de lecture.
    val lastEpisode = remember(details, episodeHistory) {
        val byId = details?.episodes?.associateBy { it.id }.orEmpty()
        episodeHistory.firstNotNullOfOrNull { byId[it.entry.id].takeIf { _ -> it.entry.type == MediaType.Series } }
    }
    // Reprendre l'épisode entamé, sinon enchaîner sur le suivant d'un épisode terminé.
    val continueEpisode = remember(details, lastEpisode, historyByEpisode) {
        val last = lastEpisode ?: return@remember null
        if (historyByEpisode[last.id]?.isResumable() == true) last
        else details?.episodes?.let { all -> all.getOrNull(all.indexOf(last) + 1) }
    }
    var selectedSeason by remember(series.key, details) {
        mutableIntStateOf(continueEpisode?.season ?: lastEpisode?.season ?: details?.seasons?.firstOrNull() ?: 1)
    }
    val episodes = remember(details, selectedSeason) { details?.episodesIn(selectedSeason).orEmpty() }
    val episodeFocus = remember(selectedSeason) { FocusRequester() }
    val episodeGridState = rememberLazyGridState()
    val focusEpisodeId = episodes.firstOrNull { it.id == continueEpisode?.id }?.id ?: episodes.firstOrNull()?.id

    LaunchedEffect(selectedSeason, episodes.size) {
        val index = episodes.indexOfFirst { it.id == focusEpisodeId }
        if (index >= 0) {
            episodeGridState.scrollToItem(index)
            yield()
            runCatching { episodeFocus.requestFocus() }
        }
    }

    Row(Modifier.fillMaxSize().padding(30.dp)) {
        Column(Modifier.width(620.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusableSurface(onClick = onBack, modifier = Modifier.width(130.dp).height(48.dp)) {
                    Text("← Retour", color = Ink, fontSize = TypeLabel, modifier = Modifier.padding(horizontal = 15.dp))
                }
                continueEpisode?.let { episode ->
                    val code = "S${episode.season.toString().padStart(2, '0')}E${episode.number.toString().padStart(2, '0')}"
                    FocusableSurface(onClick = { onEpisodeSelected(episode) }, accent = true, modifier = Modifier.height(48.dp)) {
                        Text(
                            if (historyByEpisode[episode.id]?.isResumable() == true) "Reprendre $code" else "Lire $code",
                            color = Ink,
                            fontSize = TypeLabel,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 15.dp),
                        )
                    }
                }
                FocusableSurface(
                    onClick = onToggleFavorite,
                    selected = favorite,
                    modifier = Modifier.width(68.dp).height(48.dp),
                    contentDescription = if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        StreamiaIcon(if (favorite) StreamiaIconGlyph.Star else StreamiaIconGlyph.StarOutline, size = 28.dp)
                    }
                }
                FocusableSurface(
                    onClick = onToggleWatched,
                    selected = watched,
                    modifier = Modifier.width(68.dp).height(48.dp),
                    contentDescription = if (watched) "Marquer comme non vue" else "Marquer comme vue",
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        StreamiaIcon(if (watched) StreamiaIconGlyph.CheckboxOn else StreamiaIconGlyph.CheckboxOff, size = 18.dp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            ChannelLogo(details?.details?.posterUrl ?: series.iconUrl, series.name, Modifier.size(230.dp))
            Spacer(Modifier.height(16.dp))
            Text(series.name, color = Ink, fontSize = TypeHero, lineHeight = TypeHeroLineHeight, fontWeight = HeadingWeight)
            val info = details?.details
            val meta = listOfNotNull(
                (info?.rating ?: series.rating)?.let(::formatRating),
                info?.releaseDate,
                info?.genre,
                details?.seasons?.size?.let { "$it saisons" },
                details?.episodes?.size?.let { "$it épisodes" },
                "Vue".takeIf { watched },
            )
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(meta.joinToString(" · "), color = FocusBlueBright, fontSize = TypeLabel, fontWeight = FontWeight.SemiBold)
            }
            val plot = info?.plot ?: series.plot
            if (!plot.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(plot, color = MutedInk, fontSize = TypeBody, lineHeight = TypeBodyLineHeight, maxLines = 12, overflow = TextOverflow.Ellipsis)
            }
            SeriesInfoLine("Réalisateur", info?.director)
            SeriesInfoLine("Distribution", info?.cast)
            SeriesInfoLine("Pays", info?.country)
            SeriesInfoLine("Bande-annonce", info?.youtubeTrailer)
            SimilarMediaRow(
                title = "Séries similaires",
                items = similarMedia,
                onOpenSimilar = onOpenSimilar,
                modifier = Modifier.padding(top = 18.dp),
            )
            SimilarMediaRow(
                title = "Autres versions",
                items = otherVersions,
                onOpenSimilar = onOpenSimilar,
                modifier = Modifier.padding(top = 18.dp),
            )
            Spacer(Modifier.height(28.dp))
        }
        Spacer(Modifier.width(28.dp))

        when {
            busy -> Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("Chargement des informations, saisons et épisodes…", color = MutedInk, fontSize = TypeSectionTitle)
            }
            details == null -> Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                FocusableSurface(onClick = onRetry, modifier = Modifier.width(520.dp).height(110.dp)) {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Text(message ?: "Les épisodes ne sont pas disponibles.", color = Ink, fontSize = TypeSectionTitle)
                        Spacer(Modifier.height(6.dp))
                        Text("OK pour réessayer", color = FocusBlueBright, fontSize = TypeLabel)
                    }
                }
            }
            details.episodes.isEmpty() -> Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("Aucun épisode fourni par ce serveur.", color = MutedInk, fontSize = TypeSectionTitle)
            }
            else -> {
                Column(Modifier.width(96.dp).fillMaxHeight()) {
                    SectionLabel("Saisons")
                    Spacer(Modifier.height(11.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(details.seasons, key = { it }) { season ->
                            FocusableSurface(
                                onClick = { selectedSeason = season },
                                selected = selectedSeason == season,
                                accent = selectedSeason == season,
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                            ) {
                                Text("Saison $season", color = Ink, fontSize = TypeLabel, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        SectionLabel("Saison $selectedSeason")
                        Spacer(Modifier.width(12.dp))
                        Text("${episodes.size} épisodes", color = MutedInk, fontSize = TypeLabel)
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyVerticalGrid(
                        state = episodeGridState,
                        columns = GridCells.Adaptive(285.dp),
                        // Marge pour la carte focalisée (agrandie + bordure) : sans elle, son contour était rogné.
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(episodes, key = { it.id }) { episode ->
                            FocusableSurface(
                                onClick = { onEpisodeSelected(episode) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(128.dp)
                                    .then(if (episode.id == focusEpisodeId) Modifier.focusRequester(episodeFocus) else Modifier),
                            ) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.width(150.dp).aspectRatio(16f / 9f)) {
                                        MediaArtwork(
                                            episode.iconUrl ?: details.details?.posterUrl ?: series.iconUrl,
                                            episode.title,
                                            Modifier.fillMaxSize(),
                                        )
                                        // Avancement de l'épisode, comme sur les affiches des films.
                                        val progress = historyByEpisode[episode.id]?.progress ?: 0f
                                        if (progress > 0.02f) {
                                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.45f))) {
                                                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(FocusBlueBright))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Text("S${episode.season.toString().padStart(2, '0')}E${episode.number.toString().padStart(2, '0')}", color = FocusBlueBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.weight(1f))
                                        episode.rating?.let(::formatRating)?.let {
                                            Text(it, color = FocusBlueBright, fontSize = 12.sp)
                                        }
                                    }
                                    Spacer(Modifier.height(5.dp))
                                    Text(episode.title, color = Ink, fontSize = TypeBody, lineHeight = TypeBodyLineHeight, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(5.dp))
                                    Text(listOfNotNull(episode.duration, episode.releaseDate).joinToString(" · "), color = MutedInk, fontSize = 12.sp, maxLines = 1)
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
    Text(label, color = MutedInk, fontSize = TypeLabel, fontWeight = FontWeight.Bold)
    Text(value, color = Ink, fontSize = TypeLabel, lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
}

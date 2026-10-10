package fr.streamia.tv.ui.mobile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.PlaybackHistoryItem
import fr.streamia.tv.data.isResumable
import fr.streamia.tv.data.AiReview
import fr.streamia.tv.domain.MediaDetails
import fr.streamia.tv.ui.AiLoadingIndicator
import fr.streamia.tv.ui.AiRecapCard
import fr.streamia.tv.ui.AiReviewCard
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.SeriesDetails
import fr.streamia.tv.domain.SeriesEpisode
import fr.streamia.tv.domain.TrailerLink
import fr.streamia.tv.recommendation.RecommendedMedia
import fr.streamia.tv.ui.ChannelLogo
import fr.streamia.tv.ui.MediaArtwork
import fr.streamia.tv.ui.StreamiaIcon
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.formatDuration
import fr.streamia.tv.ui.formatRating
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusCard

/**
 * Fiche film ou série au doigt : visuel, titre et méta, Lire/Reprendre, depuis le début,
 * bande-annonce, vu et favori, synopsis, informations, saisons et épisodes (séries), autres
 * versions et titres similaires.
 */
@Composable
fun MobileDetailsScreen(
    entry: MediaEntry,
    movieDetails: MediaDetails?,
    seriesDetails: SeriesDetails?,
    busy: Boolean,
    message: String?,
    favorite: Boolean,
    watched: Boolean,
    resumePositionMs: Long,
    translatedPlot: String?,
    similarMedia: List<RecommendedMedia>,
    otherVersions: List<RecommendedMedia>,
    episodeHistory: List<PlaybackHistoryItem>,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onEpisodeSelected: (SeriesEpisode) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWatched: () -> Unit,
    onOpenSimilar: (MediaEntry) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    aiPlotLoading: Boolean = false,
    aiSimilarLoading: Boolean = false,
    aiReview: AiReview? = null,
    aiReviewLoading: Boolean = false,
    aiRecap: String? = null,
    aiRecapLoading: Boolean = false,
    aiRecapAvailable: Boolean = false,
    aiRecapError: String? = null,
    onRequestRecap: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val isSeries = entry.type == MediaType.Series
    val details = if (isSeries) seriesDetails?.details else movieDetails
    val historyByEpisode = remember(episodeHistory) {
        episodeHistory.filter { it.entry.type == MediaType.Series }.associateBy { it.entry.id }
    }
    val lastEpisode = remember(seriesDetails, episodeHistory) {
        val byId = seriesDetails?.episodes?.associateBy { it.id }.orEmpty()
        episodeHistory.firstNotNullOfOrNull { byId[it.entry.id].takeIf { _ -> it.entry.type == MediaType.Series } }
    }
    val continueEpisode = remember(seriesDetails, lastEpisode, historyByEpisode) {
        val last = lastEpisode ?: return@remember null
        if (historyByEpisode[last.id]?.isResumable() == true) last
        else seriesDetails?.episodes?.let { all -> all.getOrNull(all.indexOf(last) + 1) }
    }
    var selectedSeason by remember(entry.key, seriesDetails) {
        mutableIntStateOf(continueEpisode?.season ?: lastEpisode?.season ?: seriesDetails?.seasons?.firstOrNull() ?: 1)
    }
    val episodes = remember(seriesDetails, selectedSeason) { seriesDetails?.episodesIn(selectedSeason).orEmpty() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MobileIconButton(StreamiaIconGlyph.ChevronLeft, onClick = onBack)
            Text(if (isSeries) "Série" else "Film", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 4.dp))
            MobileIconButton(
                if (favorite) StreamiaIconGlyph.Star else StreamiaIconGlyph.StarOutline,
                onClick = onToggleFavorite,
            )
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Box(
                Modifier
                    .padding(start = MobileGutter, end = MobileGutter, top = 4.dp)
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(RadiusCard))
                    .background(Color.White.copy(alpha = 0.08f)),
            ) {
                MediaArtwork(details?.backdropUrl ?: details?.posterUrl ?: entry.iconUrl, entry.displayName, Modifier.fillMaxSize())
            }
            Column(Modifier.padding(MobileGutter)) {
                Text(entry.displayName, color = Ink, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                val sectionInfo = if (isSeries) seriesDetails?.let { "${it.seasons.size} saisons" } else details?.duration
                val meta = listOfNotNull(
                    (details?.rating ?: entry.rating)?.let(::formatRating),
                    details?.releaseDate,
                    sectionInfo,
                    details?.genre,
                    "Vu".takeIf { watched },
                )
                if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), color = AccentPinkText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (busy && details == null && seriesDetails == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Chargement des informations…", color = MutedInk, fontSize = 14.sp)
                }

                Spacer(Modifier.height(16.dp))
                val playLabel = when {
                    isSeries && continueEpisode != null -> {
                        val code = "S${continueEpisode.season.toString().padStart(2, '0')}E${continueEpisode.number.toString().padStart(2, '0')}"
                        if (historyByEpisode[continueEpisode.id]?.isResumable() == true) "Reprendre $code" else "Lire $code"
                    }
                    isSeries -> "Lire"
                    resumePositionMs > 0 -> "Reprendre à ${formatDuration(resumePositionMs)}"
                    else -> "Lire"
                }
                PrimaryButton(
                    playLabel,
                    enabled = !busy && (!isSeries || continueEpisode != null),
                    onClick = { if (isSeries) continueEpisode?.let(onEpisodeSelected) else onPlay() },
                )
                if (!isSeries && resumePositionMs > 0) {
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Lire depuis le début", enabled = !busy, onClick = onPlayFromStart)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrailerButton(details?.youtubeTrailer, Modifier.weight(1f))
                    ActionSquare(if (watched) StreamiaIconGlyph.Eye else StreamiaIconGlyph.EyeOff, onToggleWatched)
                    ActionSquare(if (favorite) StreamiaIconGlyph.Star else StreamiaIconGlyph.StarOutline, onToggleFavorite)
                }

                val plot = translatedPlot ?: details?.plot ?: entry.plot
                if (!plot.isNullOrBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Text(plot, color = Ink, fontSize = 15.sp, lineHeight = 22.sp)
                }
                AiLoadingIndicator("Traduction de la description par l'IA…", aiPlotLoading, Modifier.padding(top = 8.dp))
                AiReviewCard(aiReview, aiReviewLoading, Modifier.padding(top = 12.dp))
                if (isSeries) AiRecapCard(aiRecap, aiRecapLoading, aiRecapAvailable, aiRecapError, onRequestRecap, Modifier.padding(top = 12.dp))
                val lines = listOf(
                    "Réalisateur" to details?.director,
                    "Distribution" to details?.cast,
                    "Pays" to details?.country,
                    "TMDB" to details?.tmdbId,
                ).filter { !it.second.isNullOrBlank() }
                if (lines.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    MobileCard(Modifier.fillMaxWidth(), radius = 24.dp) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            lines.forEach { (label, value) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(label, color = MutedInk, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
                                    Text(value.orEmpty(), color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = MutedInk, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (isSeries && seriesDetails == null && !busy) {
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton("Réessayer", enabled = true, onClick = onRetry)
                    }
                }
            }

            if (isSeries && seriesDetails != null) {
                MobileSectionLabel("Saisons")
                Spacer(Modifier.height(10.dp))
                MobileChipRow {
                    seriesDetails.seasons.forEach { season ->
                        MobileChip("Saison $season", selected = season == selectedSeason, onClick = { selectedSeason = season })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.padding(horizontal = MobileGutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    episodes.forEach { episode ->
                        val progress = historyByEpisode[episode.id]?.progress ?: 0f
                        MobileCard(Modifier.fillMaxWidth(), onClick = { onEpisodeSelected(episode) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(Modifier.width(84.dp).height(48.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f))) {
                                    MediaArtwork(episode.iconUrl ?: details?.posterUrl ?: entry.iconUrl, episode.title, Modifier.fillMaxSize())
                                    if (progress > 0f) ProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "S${episode.season.toString().padStart(2, '0')}E${episode.number.toString().padStart(2, '0')}",
                                        color = AccentPinkText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(episode.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val sub = listOfNotNull(episode.duration, episode.releaseDate).joinToString(" · ")
                                    if (sub.isNotBlank()) Text(sub, color = MutedInk, fontSize = 12.sp, maxLines = 1)
                                }
                                if (progress >= 0.9f) StreamiaIcon(StreamiaIconGlyph.CheckboxOn, size = 20.dp)
                            }
                        }
                    }
                }
            }

            if (otherVersions.isNotEmpty()) SimilarRow("Autres versions", otherVersions, onOpenSimilar)
            AiLoadingIndicator("Classement des similaires par l'IA…", aiSimilarLoading, Modifier.padding(start = MobileGutter, top = 16.dp))
            if (similarMedia.isNotEmpty()) SimilarRow(if (isSeries) "Séries similaires" else "Films similaires", similarMedia, onOpenSimilar)
        }
    }
}

@Composable
private fun ActionSquare(glyph: StreamiaIconGlyph, onClick: () -> Unit) {
    Box(
        Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.09f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { StreamiaIcon(glyph, size = 22.dp) }
}

@Composable
private fun TrailerButton(trailer: String?, modifier: Modifier = Modifier) {
    val candidates = remember(trailer) { TrailerLink.candidates(trailer) }
    if (candidates.isEmpty()) {
        Spacer(modifier)
        return
    }
    val context = LocalContext.current
    var failed by remember(trailer) { mutableStateOf(false) }
    Column(modifier) {
        SecondaryButton("Bande-annonce", enabled = true, onClick = { failed = !openTrailer(context, candidates) })
        if (failed) Text("Installez YouTube pour lire cette bande-annonce.", color = MutedInk, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun openTrailer(context: Context, candidates: List<String>): Boolean = candidates.any { uri ->
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

@Composable
private fun SimilarRow(title: String, items: List<RecommendedMedia>, onOpen: (MediaEntry) -> Unit) {
    Column(Modifier.padding(top = 24.dp)) {
        MobileSectionLabel(title)
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = MobileGutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.entry.key }) { item ->
                MobileCard(Modifier.width(108.dp), onClick = { onOpen(item.entry) }) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(156.dp)) { MediaArtwork(item.entry.iconUrl, item.entry.displayName, Modifier.fillMaxSize()) }
                        Text(
                            item.entry.displayName,
                            color = Ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

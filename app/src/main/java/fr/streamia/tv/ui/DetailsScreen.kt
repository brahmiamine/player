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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.MediaDetails
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night

@Composable
fun MovieDetailsScreen(
    movie: MediaEntry,
    details: MediaDetails?,
    busy: Boolean,
    message: String?,
    favorite: Boolean,
    resumePositionMs: Long,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Row(Modifier.fillMaxSize().background(Night).padding(34.dp)) {
        Column(Modifier.width(330.dp).fillMaxHeight()) {
            FocusableSurface(onClick = onBack, modifier = Modifier.width(130.dp).height(50.dp)) {
                Text("← Retour", color = Ink, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 15.dp))
            }
            Spacer(Modifier.height(22.dp))
            ChannelLogo(details?.posterUrl ?: movie.iconUrl, movie.displayName, Modifier.size(300.dp))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusableSurface(onClick = onPlay, enabled = !busy, modifier = Modifier.weight(1f).height(58.dp)) {
                    Text(
                        if (resumePositionMs > 0) "▶ Reprendre" else "▶ Lire",
                        color = Ink,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                FocusableSurface(onClick = onToggleFavorite, selected = favorite, modifier = Modifier.width(72.dp).height(58.dp)) {
                    Text(if (favorite) "★" else "☆", color = FocusBlueBright, fontSize = 25.sp, modifier = Modifier.padding(start = 21.dp))
                }
            }
        }
        Spacer(Modifier.width(34.dp))
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(movie.displayName, color = Ink, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
            if (busy && details == null) Text("Chargement des informations…", color = MutedInk, fontSize = 16.sp)
            val rating = details?.rating ?: movie.rating
            val meta = listOfNotNull(
                rating?.let { "★ ${"%.1f".format(it)}" },
                details?.releaseDate,
                details?.duration,
                details?.genre,
                movie.extension.uppercase(),
            )
            if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), color = FocusBlueBright, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (!details?.plot.isNullOrBlank() || !movie.plot.isNullOrBlank()) {
                Text(details?.plot ?: movie.plot.orEmpty(), color = Ink, fontSize = 17.sp, lineHeight = 25.sp)
            }
            DetailLine("Réalisateur", details?.director)
            DetailLine("Distribution", details?.cast)
            DetailLine("Pays", details?.country)
            DetailLine("TMDB", details?.tmdbId)
            DetailLine("Bande-annonce", details?.youtubeTrailer)
            if (resumePositionMs > 0) {
                Text("Une position de lecture sauvegardée est disponible. La lecture reprendra automatiquement.", color = MutedInk, fontSize = 14.sp)
            }
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message, color = MutedInk, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = MutedInk, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(150.dp))
        Text(value, color = Ink, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
    }
}

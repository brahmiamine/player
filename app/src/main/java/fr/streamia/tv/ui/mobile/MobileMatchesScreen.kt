package fr.streamia.tv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch
import fr.streamia.tv.liveonsat.isLiveAt
import fr.streamia.tv.liveonsat.isVisibleAt
import fr.streamia.tv.ui.ChannelLogo
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.liveOnSatMatchKey
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Matchs du jour au doigt : une carte par rencontre (compétition, heure ou EN DIRECT, équipes),
 * puis les chaînes qui la diffusent ; toucher une chaîne la lance. Tirer vers le bas actualise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MobileMatchesScreen(
    matches: List<ResolvedLiveOnSatMatch>,
    loading: Boolean,
    resolvingChannels: Boolean,
    error: String?,
    fetchedAtEpochMillis: Long?,
    onOpenChannel: (MediaEntry, String) -> Unit,
    onRefresh: () -> Unit,
    onRefreshIfStale: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowSeconds = System.currentTimeMillis() / 1000
                onRefreshIfStale()
                delay(30_000L)
            }
        }
    }
    val sorted = remember(matches, nowSeconds) {
        matches.sortedWith(
            compareBy<ResolvedLiveOnSatMatch> { resolved ->
                when {
                    resolved.isLiveAt(nowSeconds) -> 0
                    resolved.isVisibleAt(nowSeconds) -> 1
                    else -> 2
                }
            }.thenBy { it.match.startEpochSeconds },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MobileIconButton(StreamiaIconGlyph.ArrowBack, onClick = onBack)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Matchs du jour", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                fetchedAtEpochMillis?.let { Text("Mis à jour à ${clock(it)}", color = MutedInk, fontSize = 11.sp) }
            }
        }
        error?.let { MobileMessage(it, onDismiss = {}) }
        MobilePullToRefresh(refreshing = loading, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
            when {
                sorted.isEmpty() && loading -> MobileEmptyState("Chargement des matchs…")
                sorted.isEmpty() -> MobileEmptyState("Aucun match trouvé pour aujourd'hui.", "Actualiser", onRefresh)
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = MobileGutter, end = MobileGutter, top = 6.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(sorted, key = { liveOnSatMatchKey(it) }) { resolved ->
                        val match = resolved.match
                        val live = resolved.isLiveAt(nowSeconds)
                        val upcoming = !live && resolved.isVisibleAt(nowSeconds)
                        MobileCard(Modifier.fillMaxWidth(), radius = 24.dp) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(match.competition, color = MutedInk, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (live) {
                                        Box(Modifier.clip(RoundedCornerShape(50)).background(AccentPink).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                            Text("EN DIRECT", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        Text(
                                            (if (upcoming) "" else "Terminé · ") + clock(match.startEpochSeconds * 1000),
                                            color = if (upcoming) Ink else MutedInk,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Team(match.participantA, match.participantALogoUrl, Modifier.weight(1f))
                                    Text("vs", color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
                                    Team(match.participantB, match.participantBLogoUrl, Modifier.weight(1f))
                                }
                                Spacer(Modifier.height(14.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    match.channels.forEach { broadcaster ->
                                        val channel = resolved.matchedChannels[broadcaster.name]?.firstOrNull()
                                        if (channel != null) {
                                            Row(
                                                Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .background(Color.White.copy(alpha = 0.1f))
                                                    .clickable { onOpenChannel(channel, liveOnSatMatchKey(resolved)) }
                                                    .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                ChannelLogo(channel.iconUrl, channel.displayName, Modifier.size(30.dp), imagePadding = 2)
                                                Text(broadcaster.name, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        } else {
                                            Text(
                                                broadcaster.name,
                                                color = MutedInk,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 12.dp, vertical = 10.dp),
                                            )
                                        }
                                    }
                                    if (resolvingChannels && resolved.matchedChannels.isEmpty()) {
                                        Text("Recherche des chaînes…", color = AccentPinkText, fontSize = 12.sp)
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
private fun Team(name: String, logo: String?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        ChannelLogo(logo, name, Modifier.size(44.dp), imagePadding = 2)
        Spacer(Modifier.height(6.dp))
        Text(name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

private fun clock(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(ClockFormatter)

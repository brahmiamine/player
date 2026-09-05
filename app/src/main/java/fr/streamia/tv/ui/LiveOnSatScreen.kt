package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch
import fr.streamia.tv.liveonsat.isLiveAt
import fr.streamia.tv.liveonsat.isVisibleAt
import fr.streamia.tv.ui.theme.Danger
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RaisedSurface
import fr.streamia.tv.ui.theme.TypeBody
import fr.streamia.tv.ui.theme.TypeLabel
import fr.streamia.tv.ui.theme.TypeScreenTitle
import fr.streamia.tv.ui.theme.TypeSectionTitle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Liste complète des matchs du jour (liveonsat.com), avec pour chaque diffuseur listé la chaîne du
 * profil courant qui le diffuse, quand elle a été reconnue par
 * [fr.streamia.tv.liveonsat.ChannelMatcher] — un diffuseur sans correspondance reste affiché, en
 * lecture seule, plutôt que d'être masqué : la liste reste celle du site source.
 */
@Composable
fun LiveOnSatScreen(
    matches: List<ResolvedLiveOnSatMatch>,
    loading: Boolean,
    error: String?,
    fetchedAtEpochMillis: Long?,
    restoreMatchKey: String? = null,
    restoreChannelKey: String? = null,
    onOpenChannel: (MediaEntry, String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var nowEpochSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_REFRESH_MS)
            nowEpochSeconds = System.currentTimeMillis() / 1000
        }
    }
    val visibleMatches = remember(matches, nowEpochSeconds) {
        matches
            .filter { it.isVisibleAt(nowEpochSeconds) }
            .sortedBy { it.match.startEpochSeconds }
    }
    val matchListState = rememberLazyListState()
    val restoreChannelFocus = remember { FocusRequester() }
    LaunchedEffect(restoreMatchKey, restoreChannelKey, visibleMatches) {
        val targetIndex = visibleMatches.indexOfFirst { liveOnSatMatchKey(it) == restoreMatchKey }
        if (targetIndex >= 0) {
            matchListState.scrollToItem(targetIndex)
            if (restoreChannelKey != null) {
                delay(RESTORE_MATCH_FOCUS_DELAY_MS)
                runCatching { restoreChannelFocus.requestFocus() }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Night).padding(28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FocusableSurface(onClick = onBack, modifier = Modifier.width(120.dp).height(52.dp)) {
                Text("← Retour", color = Ink, fontSize = TypeLabel, modifier = Modifier.padding(horizontal = 14.dp))
            }
            Spacer(Modifier.width(18.dp))
            Text("Matchs du jour", color = Ink, fontSize = TypeScreenTitle, fontWeight = HeadingWeight)
            Spacer(Modifier.weight(1f))
            fetchedAtEpochMillis?.let {
                Text("Mis à jour à ${formatClockTime(it)}", color = MutedInk, fontSize = TypeLabel)
                Spacer(Modifier.width(14.dp))
            }
            FocusableSurface(onClick = onRefresh, enabled = !loading, modifier = Modifier.width(140.dp).height(52.dp)) {
                Text(
                    if (loading) "Actualisation…" else "Actualiser",
                    color = Ink,
                    fontSize = TypeLabel,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Source : liveonsat.com — chaînes détectées automatiquement dans votre liste, à vérifier avant de zapper.",
            color = MutedInk,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(16.dp))

        when {
            loading && matches.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Chargement des matchs…", color = MutedInk, fontSize = TypeSectionTitle)
            }
            error != null && matches.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FocusableSurface(onClick = onRefresh, modifier = Modifier.width(440.dp).height(110.dp)) {
                    Column(Modifier.padding(horizontal = 22.dp)) {
                        Text(error, color = Ink, fontSize = TypeBody)
                        Spacer(Modifier.height(6.dp))
                        Text("OK pour réessayer", color = FocusBlueBright, fontSize = TypeLabel)
                    }
                }
            }
            visibleMatches.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Aucun match trouvé pour aujourd'hui.", color = MutedInk, fontSize = TypeSectionTitle)
            }
            else -> {
                if (error != null) {
                    Text(error, color = Danger, fontSize = TypeLabel, modifier = Modifier.padding(bottom = 10.dp))
                }
                LazyColumn(state = matchListState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(
                        visibleMatches,
                        key = ::liveOnSatMatchKey,
                    ) { resolved ->
                        LiveOnSatMatchCard(
                            resolved = resolved,
                            nowEpochSeconds = nowEpochSeconds,
                            restoreChannelKey = restoreChannelKey.takeIf { liveOnSatMatchKey(resolved) == restoreMatchKey },
                            restoreChannelFocus = restoreChannelFocus,
                            onOpenChannel = { entry -> onOpenChannel(entry, liveOnSatMatchKey(resolved)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveOnSatMatchCard(
    resolved: ResolvedLiveOnSatMatch,
    nowEpochSeconds: Long,
    restoreChannelKey: String?,
    restoreChannelFocus: FocusRequester,
    onOpenChannel: (MediaEntry) -> Unit,
) {
    val match = resolved.match
    val isLive = resolved.isLiveAt(nowEpochSeconds)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DeepSurface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isLive) {
                Text("● EN DIRECT", color = Danger, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(formatMatchTime(match.startEpochSeconds), color = FocusBlueBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                match.competition,
                color = MutedInk,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.height(7.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(match.participantALogoUrl, match.participantA, Modifier.width(38.dp).height(38.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "${match.participantA} – ${match.participantB}",
                color = Ink,
                fontSize = TypeSectionTitle,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            ChannelLogo(match.participantBLogoUrl, match.participantB, Modifier.width(38.dp).height(38.dp))
        }
        if (match.channels.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                match.channels.forEach { channel ->
                    val entry = resolved.matchedChannels[channel.name]
                    ChannelChip(
                        name = channel.name,
                        entry = entry,
                        restoreFocusRequester = if (entry?.key == restoreChannelKey) restoreChannelFocus else null,
                        onOpenChannel = onOpenChannel,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelChip(
    name: String,
    entry: MediaEntry?,
    restoreFocusRequester: FocusRequester?,
    onOpenChannel: (MediaEntry) -> Unit,
) {
    if (entry != null) {
        FocusableSurface(
            onClick = { onOpenChannel(entry) },
            modifier = Modifier.then(
                if (restoreFocusRequester != null) Modifier.focusRequester(restoreFocusRequester) else Modifier,
            ),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelLogo(entry.iconUrl, entry.displayName, Modifier.width(24.dp).height(24.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    name,
                    color = Ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    } else {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(RaisedSurface.copy(alpha = 0.35f)),
        ) {
            Text(
                name,
                color = MutedInk,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private fun formatMatchTime(startEpochSeconds: Long): String =
    Instant.ofEpochSecond(startEpochSeconds)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))

private fun formatClockTime(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))

private fun liveOnSatMatchKey(resolved: ResolvedLiveOnSatMatch): String =
    with(resolved.match) { "$competition|$participantA|$participantB|$startEpochSeconds" }

/** Assez fréquent pour que « EN DIRECT » et le filtrage des matchs périmés restent justes sur un écran laissé ouvert. */
private const val CLOCK_REFRESH_MS = 30_000L
private const val RESTORE_MATCH_FOCUS_DELAY_MS = 60L

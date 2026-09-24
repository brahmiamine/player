package fr.streamia.tv.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch
import fr.streamia.tv.liveonsat.isLiveAt
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk

/**
 * Une carte = un match en direct × une chaîne de la liste qui le diffuse (reconnue par le matcher).
 * [channel] null : chaînes encore en cours de rapprochement, la carte montre un squelette à la place.
 */
internal data class LiveMatchCard(
    val key: String,
    val match: ResolvedLiveOnSatMatch,
    val channel: MediaEntry?,
)

/**
 * Matchs en direct de « Matchs du jour » dont au moins un diffuseur a été associé à une chaîne de la
 * liste. Une seule chaîne par diffuseur (la première : HD/FHD d'une même chaîne feraient doublon).
 * Pendant le rapprochement ([resolvingChannels]), un match sans chaîne encore trouvée garde une carte
 * avec la chaîne en squelette : les rencontres s'affichent d'abord, leurs chaînes dès qu'elles sont prêtes.
 */
internal fun liveMatchCards(
    matches: List<ResolvedLiveOnSatMatch>,
    nowEpochSeconds: Long,
    resolvingChannels: Boolean = false,
): List<LiveMatchCard> =
    matches
        .filter { it.isLiveAt(nowEpochSeconds) }
        .sortedBy { it.match.startEpochSeconds }
        .flatMap { resolved ->
            if (resolvingChannels && resolved.matchedChannels.isEmpty()) {
                return@flatMap listOf(LiveMatchCard("${liveOnSatMatchKey(resolved)}#pending", resolved, null))
            }
            resolved.match.channels.mapNotNull { broadcaster ->
                resolved.matchedChannels[broadcaster.name]?.firstOrNull()
            }
                .distinctBy { it.key }
                .map { channel -> LiveMatchCard("${liveOnSatMatchKey(resolved)}#${channel.key}", resolved, channel) }
        }

@Composable
internal fun LiveMatchesRow(
    cards: List<LiveMatchCard>,
    restoreItemKey: String?,
    onOpen: (LiveMatchCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowState = rememberLazyListState()
    val restoreFocus = remember { FocusRequester() }
    val restoreIndex = rememberRowFocusRestore(rowState, restoreItemKey, cards.map(LiveMatchCard::key), restoreFocus)
    Column(modifier.fillMaxWidth()) {
        SectionLabel("Matchs en direct", fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        LazyRow(
            state = rowState,
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(cards, key = { _, card -> card.key }) { index, card ->
                val cardModifier = if (index == restoreIndex) Modifier.focusRequester(restoreFocus) else Modifier
                FocusableSurface(onClick = { onOpen(card) }, modifier = cardModifier.width(260.dp).height(170.dp)) {
                    LiveMatchCardContent(card)
                }
            }
        }
    }
}

@Composable
private fun LiveMatchCardContent(card: LiveMatchCard) {
    val match = card.match.match
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(match.competition, color = MutedInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            LiveBadge()
        }
        Spacer(Modifier.height(8.dp))
        TeamRow(match.participantA, match.participantALogoUrl)
        Spacer(Modifier.height(6.dp))
        TeamRow(match.participantB, match.participantBLogoUrl)
        Spacer(Modifier.weight(1f))
        val channel = card.channel
        if (channel == null) {
            ChannelLineSkeleton(logo = 36.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel.iconUrl, channel.displayName, Modifier.size(36.dp), imagePadding = 2)
                Spacer(Modifier.width(8.dp))
                Text(channel.displayName, color = MutedInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TeamRow(name: String, logo: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChannelLogo(logo, name, Modifier.size(24.dp), imagePadding = 1)
        Spacer(Modifier.width(8.dp))
        Text(name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

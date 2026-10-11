package fr.streamia.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
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

/**
 * Retire des matchs les chaînes masquées par l'utilisateur et, contrôle parental actif et pas
 * déverrouillé, celles des catégories verrouillées : le rapprochement liveonsat parcourt toute la
 * playlist, et sans ce filtre une chaîne verrouillée se lançait depuis un match sans code.
 */
internal fun List<ResolvedLiveOnSatMatch>.withoutHiddenChannels(
    catalog: Catalog?,
    library: UserLibrarySnapshot,
    parentalLocked: Boolean,
): List<ResolvedLiveOnSatMatch> {
    val excludedKeys = if (parentalLocked) library.hiddenCategories + library.lockedCategories else library.hiddenCategories
    val excludedCategoryIds = catalog?.categoriesFor(MediaType.Live).orEmpty()
        .filter { it.key in excludedKeys }
        .mapTo(mutableSetOf()) { it.id }
    if (excludedCategoryIds.isEmpty() && library.hiddenEntries.isEmpty()) return this
    return map { resolved ->
        val visible = resolved.matchedChannels
            .mapValues { (_, channels) -> channels.filterNot { it.key in library.hiddenEntries || it.categoryId in excludedCategoryIds } }
            .filterValues { it.isNotEmpty() }
        if (visible == resolved.matchedChannels) resolved else resolved.copy(matchedChannels = visible)
    }
}

/** Bleu nuit : distingue les matchs à regarder des scores, sur l'accueil. */
private val LiveCardBackground = Color(0xCC16213F)

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
                FocusableSurface(onClick = { onOpen(card) }, modifier = cardModifier.width(320.dp).height(176.dp), idleBackground = LiveCardBackground) {
                    LiveMatchCardContent(card)
                }
            }
        }
    }
}

private val liveKickoffFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun startedSince(startEpochSeconds: Long): String {
    val minutes = ((System.currentTimeMillis() / 1_000 - startEpochSeconds) / 60).toInt()
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        else -> "il y a ${minutes / 60} h ${"%02d".format(minutes % 60)}"
    }
}

@Composable
private fun LiveMatchCardContent(card: LiveMatchCard) {
    val match = card.match.match
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(match.competition, color = MutedInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            LiveBadge()
        }
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            TeamColumn(match.participantA, match.participantALogoUrl, Modifier.weight(1f))
            Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(liveKickoffFormat.format(Instant.ofEpochSecond(match.startEpochSeconds)), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(startedSince(match.startEpochSeconds), color = MutedInk, fontSize = 11.sp, maxLines = 1)
            }
            TeamColumn(match.participantB, match.participantBLogoUrl, Modifier.weight(1f))
        }
        val channel = card.channel
        if (channel == null) {
            ChannelLineSkeleton(logo = 28.dp)
        } else {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x66000000)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelLogo(channel.iconUrl, channel.displayName, Modifier.size(28.dp), imagePadding = 2)
                Spacer(Modifier.width(8.dp))
                Text(channel.displayName, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TeamColumn(name: String, logo: String?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        ChannelLogo(logo, name, Modifier.size(38.dp), imagePadding = 1)
        Spacer(Modifier.height(4.dp))
        Text(name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

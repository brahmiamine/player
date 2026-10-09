package fr.streamia.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.recommendation.RecommendationRow
import fr.streamia.tv.recommendation.RecommendedMedia
import fr.streamia.tv.ui.theme.Danger
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import kotlinx.coroutines.delay

// Rangées et cartes de contenu de l'accueil (reprise, favoris, recommandations).

internal val CardRowSpacing = 22.dp

@Composable
internal fun HomeCardRow(
    title: String,
    entries: List<Pair<MediaEntry, Float?>>,
    firstFocusRequester: FocusRequester?,
    restoreItemKey: String?,
    onEntryClick: (MediaEntry) -> Unit,
    compact: Boolean = false,
) {
    val rowState = rememberLazyListState()
    val restoreFocus = remember { FocusRequester() }
    val restoreIndex = rememberRowFocusRestore(rowState, restoreItemKey, entries.map { (entry, _) -> entry.key }, restoreFocus)

    Column(Modifier.fillMaxWidth()) {
        SectionLabel(title, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        LazyRow(state = rowState, modifier = Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(entries, key = { _, (entry, _) -> entry.key }) { index, (entry, progress) ->
                val cardModifier = when {
                    index == restoreIndex -> Modifier.focusRequester(restoreFocus)
                    index == 0 && firstFocusRequester != null -> Modifier.focusRequester(firstFocusRequester)
                    else -> Modifier
                }
                HomeMediaCard(
                    entry = entry,
                    progress = progress,
                    onClick = { onEntryClick(entry) },
                    modifier = cardModifier,
                    compact = compact,
                )
            }
        }
    }
}

/** Pastille distincte du libellé texte de la carte : signale le direct sans dépendre du texte. */
@Composable
internal fun LiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Danger)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text("LIVE", color = Night, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Retour sur l'accueil vers une carte de cette rangée : la fait défiler à l'écran puis lui rend le
 * focus. Carte disparue entre-temps (match terminé, rangée recalculée) : la première carte le reçoit,
 * pour que la télécommande ne reste jamais sans focus. Renvoie l'index de la carte à refocaliser, ou -1.
 */
@Composable
internal fun rememberRowFocusRestore(
    rowState: LazyListState,
    restoreItemKey: String?,
    keys: List<String>,
    focus: FocusRequester,
): Int {
    val index = if (restoreItemKey == null || keys.isEmpty()) -1 else keys.indexOf(restoreItemKey).coerceAtLeast(0)
    LaunchedEffect(restoreItemKey, index) {
        if (index < 0) return@LaunchedEffect
        rowState.scrollToItem(index)
        delay(RESTORE_FOCUS_DELAY_MS)
        runCatching { focus.requestFocus() }
    }
    return index
}

@Composable
internal fun HomeRecommendationRow(
    row: RecommendationRow,
    firstFocusRequester: FocusRequester?,
    restoreItemKey: String?,
    onOpenRecommendation: (MediaEntry) -> Unit,
) {
    val rowState = rememberLazyListState()
    val restoreFocus = remember { FocusRequester() }
    val restoreIndex = rememberRowFocusRestore(rowState, restoreItemKey, row.items.map { it.entry.key }, restoreFocus)

    Column(Modifier.fillMaxWidth()) {
        SectionLabel(row.title, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        LazyRow(state = rowState, modifier = Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(row.items, key = { _, recommended -> recommended.entry.key }) { index, recommended ->
                val cardModifier = when {
                    index == restoreIndex -> Modifier.focusRequester(restoreFocus)
                    index == 0 && firstFocusRequester != null -> Modifier.focusRequester(firstFocusRequester)
                    else -> Modifier
                }
                HomeRecommendationCard(
                    recommended = recommended,
                    onClick = { onOpenRecommendation(recommended.entry) },
                    modifier = cardModifier,
                )
            }
        }
    }
}

@Composable
private fun HomeRecommendationCard(
    recommended: RecommendedMedia,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = recommended.entry
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.width(HomeCardWidth).height(HomeCardHeight),
    ) {
        Column(Modifier.fillMaxSize().padding(9.dp)) {
            MediaArtwork(entry.iconUrl, entry.displayName, Modifier.fillMaxWidth().height(128.dp))
            Spacer(Modifier.height(7.dp))
            Text(
                entry.displayName,
                color = Ink,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                recommended.reason ?: entry.type.displayName,
                color = FocusBlueBright,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal val HomeCardWidth = 172.dp

// 128dp d'illustration + jusqu'à 2 lignes de titre en 13sp/16sp de lineHeight + le label de type
// + une éventuelle barre de progression : 224dp laisse une marge confortable dans le pire cas
// (titre sur 2 lignes ET progression affichée) plutôt que de risquer un rognage en bas de carte.
internal val HomeCardHeight = 224.dp

// Favoris / dernières chaînes : surtout des logos, un titre sur une ligne suffit.
private val HomeCompactCardWidth = 136.dp

private val HomeCompactCardHeight = 150.dp

@Composable
private fun HomeMediaCard(
    entry: MediaEntry,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = if (compact) {
            modifier.width(HomeCompactCardWidth).height(HomeCompactCardHeight)
        } else {
            modifier.width(HomeCardWidth).height(HomeCardHeight)
        },
    ) {
        Column(Modifier.fillMaxSize().padding(if (compact) 7.dp else 9.dp)) {
            MediaArtwork(entry.iconUrl, entry.displayName, Modifier.fillMaxWidth().height(if (compact) 80.dp else 128.dp))
            Spacer(Modifier.height(if (compact) 5.dp else 7.dp))
            Text(
                entry.displayName,
                color = Ink,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(entry.type.displayName, color = FocusBlueBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (progress != null) {
                Spacer(Modifier.height(5.dp))
                HomeProgressBar(progress)
            }
        }
    }
}

@Composable
private fun HomeProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MutedInk.copy(alpha = 0.25f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(2.dp))
                .background(FocusBlueBright),
        )
    }
}

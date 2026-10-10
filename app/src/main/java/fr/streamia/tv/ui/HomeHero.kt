package fr.streamia.tv.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.TonightAnswers
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.HeroWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusPill
import kotlinx.coroutines.delay

// Hero de l'accueil : la reprise de lecture en grande carte, et à côté « Ce soir ? » + « Quoi de neuf maintenant ? ».

/** Clé de l'élément de liste du hero (la reprise, l'assistant et le résumé partagent le même défilement). */
internal const val HERO_ROW_KEY = "home-hero"

private val HeroHeight = 290.dp
private val TonightCardHeight = 180.dp

/** ◀ ▶ parcourent une liste sans quitter la carte tant qu'il reste un voisin ; aux extrémités, le focus sort normalement. */
private fun Modifier.pageWithArrows(index: Int, lastIndex: Int, onIndex: (Int) -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when {
        event.key == Key.DirectionLeft && index > 0 -> { onIndex(index - 1); true }
        event.key == Key.DirectionRight && index < lastIndex -> { onIndex(index + 1); true }
        else -> false
    }
}

@Composable
internal fun HomeHero(
    resumeCards: List<Pair<MediaEntry, Float?>>,
    resumeRestoreKey: String?,
    onResume: (MediaEntry) -> Unit,
    aiActive: Boolean,
    tonightAnswers: TonightAnswers,
    onOpenAssistant: (AssistantMode) -> Unit,
    assistantFocus: FocusRequester,
    showBrief: Boolean,
    brief: BriefUiState,
    briefRestoreKey: String?,
    onOpenBrief: (MediaEntry) -> Unit,
    onRefreshBrief: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(HeroHeight), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (resumeCards.isNotEmpty()) {
            ResumeHeroCard(resumeCards, resumeRestoreKey, onResume, Modifier.weight(1.7f).fillMaxHeight())
        }
        if (aiActive) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TonightHomeCard(
                    tonightAnswers,
                    Modifier.fillMaxWidth().then(if (showBrief) Modifier.height(TonightCardHeight) else Modifier.fillMaxHeight()).focusRequester(assistantFocus),
                ) { onOpenAssistant(AssistantMode.Tonight) }
                if (showBrief) BriefCompactCard(brief, briefRestoreKey, onOpenBrief, onRefreshBrief, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** Reprise la plus récente en grand ; ◀ ▶ passent aux suivantes, OK reprend la lecture. */
@Composable
private fun ResumeHeroCard(cards: List<Pair<MediaEntry, Float?>>, restoreKey: String?, onResume: (MediaEntry) -> Unit, modifier: Modifier) {
    var index by remember { mutableIntStateOf(cards.indexOfFirst { it.first.key == restoreKey }.coerceAtLeast(0)) }
    val safeIndex = index.coerceIn(0, cards.lastIndex)
    val (entry, progress) = cards[safeIndex]
    val restoreFocus = remember { FocusRequester() }
    LaunchedEffect(restoreKey) {
        if (restoreKey == null) return@LaunchedEffect
        delay(RESTORE_FOCUS_DELAY_MS)
        runCatching { restoreFocus.requestFocus() }
    }
    FocusableSurface(
        onClick = { onResume(entry) },
        focusScale = 1.02f,
        modifier = modifier.focusRequester(restoreFocus).pageWithArrows(safeIndex, cards.lastIndex) { index = it },
    ) {
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("REPRENDRE LA LECTURE", color = AccentPinkText, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    if (cards.size > 1) Text("◀ ▶  ${safeIndex + 1} sur ${cards.size}", color = MutedInk, fontSize = 14.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.displayName, color = Ink, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = HeroWeight, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        entry.type.displayName + (progress?.let { " · ${(it * 100).toInt()} % vu" } ?: ""),
                        color = Ink.copy(alpha = 0.8f), fontSize = 18.sp,
                    )
                    if (progress != null) {
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.22f))) {
                            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(AccentPink))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.height(46.dp).clip(RoundedCornerShape(RadiusPill)).background(AccentPink).padding(horizontal = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Reprendre", color = Ink, fontSize = 18.sp, fontWeight = HeroWeight) }
                }
            }
            MediaArtwork(entry.iconUrl, entry.displayName, Modifier.width(150.dp).fillMaxHeight())
        }
    }
}

/** « Quoi de neuf maintenant ? » en une ligne : la chaîne en cours (◀ ▶ pour les autres) et Actualiser. */
@Composable
private fun BriefCompactCard(brief: BriefUiState, restoreKey: String?, onOpen: (MediaEntry) -> Unit, onRefresh: () -> Unit, modifier: Modifier) {
    val items = brief.items.filter { it.channel != null }
    var index by remember { mutableIntStateOf(items.indexOfFirst { it.channel?.key == restoreKey }.coerceAtLeast(0)) }
    val restoreFocus = remember { FocusRequester() }
    LaunchedEffect(restoreKey) {
        if (restoreKey == null) return@LaunchedEffect
        delay(RESTORE_FOCUS_DELAY_MS)
        runCatching { restoreFocus.requestFocus() }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val item = items.getOrNull(index.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        FocusableSurface(
            onClick = { item?.channel?.let(onOpen) },
            enabled = item != null,
            focusScale = 1.02f,
            idleBackground = Color.White.copy(alpha = 0.1f),
            modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(restoreFocus)
                .pageWithArrows(index.coerceAtLeast(0), items.lastIndex) { index = it },
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("✦ Quoi de neuf maintenant ?", color = MutedInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (items.size > 1) Text("◀ ▶  ${index.coerceIn(0, items.lastIndex) + 1}/${items.size}", color = MutedInk, fontSize = 13.sp)
                }
                if (item != null) {
                    Text(item.text, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("EN DIRECT · " + item.channel!!.displayName, color = AccentPinkText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(
                        brief.error?.takeIf { !brief.loading } ?: if (brief.loading) "L'assistant résume ce qui passe…" else "Rien à signaler pour l'instant",
                        color = Ink, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        FocusableSurface(
            onClick = onRefresh,
            enabled = !brief.loading,
            contentDescription = "Actualiser le résumé",
            wrapContent = true,
            radius = RadiusPill,
            idleBackground = Color.White.copy(alpha = 0.12f),
            modifier = Modifier.size(48.dp),
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (brief.loading) ButtonSpinner(size = 20.dp) else StreamiaIcon(StreamiaIconGlyph.Refresh, tint = Ink, size = 22.dp)
            }
        }
    }
}

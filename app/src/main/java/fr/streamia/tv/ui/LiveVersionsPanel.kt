package fr.streamia.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.LiveVersionHealth
import fr.streamia.tv.domain.LiveVersionOption
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.Danger
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RadiusCard
import fr.streamia.tv.ui.theme.WarmSignal
import kotlinx.coroutines.yield

/**
 * Panneau « Versions » du Direct, ouvert directement par → : les versions de la chaîne en cours,
 * classées sur ce que le lecteur a réellement mesuré (image, fps, son), pas sur leur nom.
 * Premier OK sur une version : elle est lancée et le panneau reste ouvert pour comparer ; OK sur la
 * version en cours : le panneau se ferme. ← ou Retour le ferment aussi (Retour annule d'abord un test).
 */
@Composable
internal fun BoxScope.LiveVersionsPanel(
    channelName: String,
    options: List<LiveVersionOption>,
    currentLoading: Boolean,
    currentFailed: Boolean,
    /** Problème constaté en direct sur la version en cours (« Pas d'image », « Pas de son… »). */
    currentProblem: String?,
    /** « Test 2 / 5 · TF1 HD » pendant « Tester toutes les versions », sinon `null`. */
    scanProgress: String?,
    scanCount: Int,
    onSelect: (MediaEntry) -> Unit,
    onClose: () -> Unit,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onOpenPlaybackSettings: () -> Unit,
) {
    val scanning = scanProgress != null
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    val currentIndex = options.indexOfFirst { it.current }.coerceAtLeast(0)
    var focusedIndex by remember { mutableIntStateOf(currentIndex) }
    // Focus d'entrée sur la version en cours (pas en tête de liste).
    LaunchedEffect(Unit) {
        if (options.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(currentIndex)
        yield()
        runCatching { currentFocus.requestFocus() }
    }
    val firstOtherLanguage = options.indexOfFirst { !it.sameLanguage }

    Column(
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(540.dp)
            .padding(vertical = 40.dp, horizontal = 24.dp)
            .clip(RoundedCornerShape(RadiusCard))
            .background(Night.copy(alpha = 0.84f))
            .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(RadiusCard))
            .padding(horizontal = 22.dp, vertical = 22.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (scanning) onCancelScan() else onClose()
                    true
                } else {
                    false
                }
            },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Versions de $channelName",
                color = Ink,
                fontSize = 22.sp,
                fontWeight = HeadingWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text("${focusedIndex + 1} / ${options.size}", color = MutedInk, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            scanProgress ?: "Qualité, fps et son réellement mesurés à l'écran ; « annoncée » = d'après le nom seulement.",
            color = if (scanning) FocusBlueBright else MutedInk,
            fontSize = 12.sp,
            fontWeight = if (scanning) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            // Marge pour la ligne focalisée (agrandie + bordure) : sans elle, son contour est rogné.
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(options, key = { _, option -> option.entry.key }) { index, option ->
                Column {
                    if (index == firstOtherLanguage) {
                        Text(
                            "AUTRES LANGUES / PAYS",
                            color = MutedInk,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                        )
                    }
                    VersionRow(
                        option = option,
                        loading = option.current && currentLoading,
                        failed = option.current && currentFailed,
                        problem = currentProblem.takeIf { option.current },
                        onClick = {
                            when {
                                scanning -> Unit
                                option.current -> onClose()
                                else -> onSelect(option.entry)
                            }
                        },
                        onFocused = { focusedIndex = index },
                        modifier = if (index == currentIndex) Modifier.focusRequester(currentFocus) else Modifier,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (scanning) {
            PanelAction("Annuler le test", onCancelScan)
        } else {
            // Une version à la fois, jamais deux connexions : environ 6 s par version.
            PanelAction("Tester toutes les versions (≈ ${scanCount * 6} s)", onStartScan)
            Spacer(Modifier.height(8.dp))
            PanelAction("Audio, sous-titres, format…", onOpenPlaybackSettings)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (scanning) "Chaque version s'affiche quelques secondes · Retour : annuler"
            else "OK : lancer la version · OK sur la version en cours ou ← : fermer",
            color = MutedInk,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun PanelAction(label: String, onClick: () -> Unit) {
    FocusableSurface(onClick = onClick, focusScale = 1.02f, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun VersionRow(
    option: LiveVersionOption,
    loading: Boolean,
    failed: Boolean,
    problem: String?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimmed = option.health.rank >= LiveVersionHealth.Unavailable.rank && !option.current
    FocusableSurface(
        onClick = onClick,
        onFocused = onFocused,
        selected = option.current,
        focusScale = 1.02f,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    option.entry.displayName,
                    color = if (dimmed) MutedInk else Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (option.recommended) {
                    Spacer(Modifier.width(10.dp))
                    Text("RECOMMANDÉE", color = AccentPinkText, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                if (option.current) {
                    Spacer(Modifier.width(10.dp))
                    Text("EN COURS", color = FocusBlueBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                option.qualityText,
                color = if (option.measured && !dimmed) Ink else MutedInk,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // Symbole ET mot pour l'état : jamais la couleur seule (PRODUCT.md, accessibilité).
            val status = when {
                failed -> "✕ Ne répond pas"
                loading -> "… Chargement"
                problem != null -> "✕ $problem"
                option.health == LiveVersionHealth.Unavailable ->
                    "${option.health.symbol} ${option.health.label}${option.failureAgo?.let { " · $it" }.orEmpty()}"
                else -> "${option.health.symbol} ${option.health.label}"
            }
            val statusColor = when {
                failed || problem != null || option.health.rank >= LiveVersionHealth.Unavailable.rank -> Danger
                loading -> MutedInk
                option.health == LiveVersionHealth.Choppy -> WarmSignal
                option.health == LiveVersionHealth.Stable -> FocusBlueBright
                else -> MutedInk
            }
            Text(status, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            option.warnings.forEach { warning ->
                Text("⚠ $warning", color = WarmSignal, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

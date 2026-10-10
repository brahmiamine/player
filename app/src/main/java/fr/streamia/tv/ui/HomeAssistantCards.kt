package fr.streamia.tv.ui

import fr.streamia.tv.ui.theme.PillActionHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Rubriques de l'assistant IA sur l'accueil TV : « Ce soir ? » (carte principale), télécommande téléphone
// (carte secondaire, avec son QR code) et « Quoi de neuf maintenant ? ».

private val AssistantRowHeight = 196.dp
private val RemoteCardWidth = 360.dp

/**
 * « ✦ Assistant IA » : la carte « Ce soir ? » résume les dernières réponses (Détente · Un film · Seul) et
 * lance l'assistant d'un appui ; à droite, le QR code de la télécommande téléphone (non focalisable).
 */
@Composable
internal fun AiAssistantRow(
    answers: TonightAnswers,
    remote: RemoteUiState,
    onOpen: (AssistantMode) -> Unit,
    firstFocus: FocusRequester,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("✦ Assistant IA", fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().height(AssistantRowHeight),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TonightHomeCard(answers, Modifier.weight(1f).fillMaxHeight().focusRequester(firstFocus)) { onOpen(AssistantMode.Tonight) }
            AiRemoteQrCard(remote, Modifier.width(RemoteCardWidth).fillMaxHeight())
        }
    }
}

@Composable
private fun TonightHomeCard(answers: TonightAnswers, modifier: Modifier, onClick: () -> Unit) {
    FocusableSurface(onClick = onClick, focusScale = 1.02f, idleBackground = Color.White.copy(alpha = 0.06f), modifier = modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(AccentPink.copy(alpha = 0.32f), Color(0x42BF5AF2))))
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("✦ Ce soir ?", color = Ink, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = HeroWeight)
                Text(
                    "Trois questions, cinq propositions faites pour vous.",
                    color = Ink.copy(alpha = 0.85f),
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                answers.labels.forEach { label ->
                    Box(
                        Modifier.height(36.dp).clip(RoundedCornerShape(RadiusPill)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.height(44.dp).clip(RoundedCornerShape(RadiusPill)).background(AccentPink).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Commencer", color = Ink, fontSize = 17.sp, fontWeight = HeroWeight)
                }
            }
        }
    }
}

/** QR code de la télécommande téléphone, toujours affiché : le téléphone scanne, puis écrit à la TV. Non focalisable (rien à actionner à la télécommande). */
@Composable
internal fun AiRemoteQrCard(remote: RemoteUiState, modifier: Modifier = Modifier) {
    val qr = remember(remote.url) { remote.url?.let(::qrBitmap) }
    GlassSurface(modifier = modifier, shape = RoundedCornerShape(32.dp)) {
        Row(
            Modifier.fillMaxSize().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(120.dp).clip(RoundedCornerShape(16.dp)).background(if (qr != null) Color.White else Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                if (qr != null) {
                    Image(
                        qr.asImageBitmap(), "QR code de la télécommande téléphone",
                        Modifier.fillMaxSize().padding(8.dp), filterQuality = FilterQuality.None,
                    )
                } else {
                    Text("QR", color = MutedInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("✦ Télécommande téléphone", color = Ink, fontSize = 19.sp, fontWeight = HeroWeight, maxLines = 2)
                val last = remote.log.lastOrNull()
                Text(
                    when {
                        qr == null -> "Aucun réseau local détecté : connectez la TV au Wi-Fi ou à l'Ethernet."
                        last != null -> "« ${last.message} » → ${last.reply}"
                        else -> "Scannez, puis écrivez : « mets beIN Sports 1 », « reprends ma série »."
                    },
                    color = Ink.copy(alpha = 0.7f), fontSize = 15.sp, lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
                AiLoadingIndicator("Traitement du message…", remote.busy)
            }
        }
    }
}

private val BriefClock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** « Mis à jour à 14:09 » d'après l'heure du dernier résumé. */
internal fun BriefUiState.updatedLabel(): String? =
    updatedAtMillis?.let { "Mis à jour à " + BriefClock.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }

/**
 * « Quoi de neuf maintenant ? » : l'en-tête porte l'heure de mise à jour et « Actualiser » ; dans la carte, le résumé à
 * gauche (grand) et les chaînes en cours en tuiles côte à côte (OK ouvre ; ← → passent de l'une à l'autre).
 * Calculé à partir des guides déjà chargés, jamais d'une donnée de plus.
 */
@Composable
internal fun AiBriefRow(
    brief: BriefUiState,
    restoreItemKey: String?,
    onOpen: (MediaEntry) -> Unit,
    onRefresh: () -> Unit,
) {
    val items = brief.items.filter { it.channel != null }
    val rowState = rememberLazyListState()
    val restoreFocus = remember { FocusRequester() }
    val restoreIndex = rememberRowFocusRestore(rowState, restoreItemKey, items.mapNotNull { it.channel?.key }, restoreFocus)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionLabel("✦ Quoi de neuf maintenant ?", fontSize = 13.sp, modifier = Modifier.weight(1f))
            brief.updatedLabel()?.let { Text(it, color = MutedInk, fontSize = 14.sp) }
            FocusableSurface(
                onClick = onRefresh,
                enabled = !brief.loading,
                wrapContent = true,
                radius = RadiusPill,
                idleBackground = Color.White.copy(alpha = 0.12f),
                modifier = Modifier.height(PillActionHeight),
            ) {
                if (brief.loading) {
                    AiButtonLabel("Actualiser", loading = true)
                } else {
                    Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StreamiaIcon(StreamiaIconGlyph.Refresh, tint = Ink, size = 18.dp)
                        Text("Actualiser", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        GlassSurface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
            tintColor = Color.White.copy(alpha = 0.07f),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.width(BriefSummaryWidth), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val summary = when {
                        brief.error != null && !brief.loading -> brief.error
                        else -> brief.headline ?: items.firstOrNull()?.text
                    }
                    if (summary != null) {
                        Text(
                            summary, color = Ink, fontSize = if (items.isEmpty()) 19.sp else 26.sp, lineHeight = if (items.isEmpty()) 25.sp else 32.sp,
                            fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AiLoadingIndicator("L'assistant résume ce qui passe en ce moment…", brief.loading)
                    if (items.isNotEmpty()) {
                        val count = items.size
                        Text(if (count == 1) "1 chaîne à regarder maintenant" else "$count chaînes à regarder maintenant", color = MutedInk, fontSize = 15.sp)
                        if (count > 1) Text("◀ ▶ pour parcourir · OK pour ouvrir", color = MutedInk.copy(alpha = 0.7f), fontSize = 13.sp)
                    }
                }
                if (items.isNotEmpty()) {
                    LazyRow(
                        state = rowState,
                        modifier = Modifier.weight(1f).focusRestorer(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        itemsIndexed(items, key = { _, item -> item.channel!!.key + item.text.hashCode() }) { index, item ->
                            BriefTile(
                                item, onOpen = { onOpen(item.channel!!) },
                                modifier = Modifier.then(if (index == restoreIndex) Modifier.focusRequester(restoreFocus) else Modifier),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val BriefSummaryWidth = 340.dp
private val BriefTileWidth = 232.dp
private val BriefTileHeight = 188.dp

/** Tuile d'une chaîne en cours : logo, badge « EN DIRECT », ce qui passe, nom de la chaîne. */
@Composable
private fun BriefTile(item: BriefItemUi, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val channel = item.channel ?: return
    FocusableSurface(
        onClick = onOpen,
        focusScale = 1.03f,
        idleBackground = Color.White.copy(alpha = 0.1f),
        modifier = modifier.width(BriefTileWidth).height(BriefTileHeight),
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChannelLogo(channel.iconUrl, channel.displayName, Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)), imagePadding = 4)
                Row(
                    Modifier.height(26.dp).clip(RoundedCornerShape(RadiusPill)).background(AccentPink.copy(alpha = 0.24f)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(AccentPinkText))
                    Text("EN DIRECT", color = AccentPinkText, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
                }
            }
            Text(item.text, color = Ink, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text(channel.displayName, color = AccentPinkText, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

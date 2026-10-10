package fr.streamia.tv.ui

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import fr.streamia.tv.R
import fr.streamia.tv.data.PhoneChatServer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.TonightAnswers
import fr.streamia.tv.data.TonightCompany
import fr.streamia.tv.data.TonightLength
import fr.streamia.tv.data.TonightMood
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusPill
import fr.streamia.tv.ui.theme.TypeBody
import fr.streamia.tv.ui.theme.TypeLabel
import fr.streamia.tv.ui.theme.TypeScreenTitle

// Écrans de l'assistant IA sur TV : Ce soir ?, Collections, Quoi de neuf maintenant ?
// Ils ne sont atteignables que quand l'assistant est actif (voir StreamiaApp : l'assistant coupé les ferme).

@Composable
private fun AssistantFrame(title: String, subtitle: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp)) {
        GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(RadiusPill)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                FocusableSurface(onClick = onBack, modifier = Modifier.width(120.dp).height(52.dp)) { BackLabel(TypeLabel, 14.dp) }
                Spacer(Modifier.width(18.dp))
                Text("✦ $title", color = Ink, fontSize = TypeScreenTitle, fontWeight = HeadingWeight)
                Spacer(Modifier.weight(1f))
                Text(subtitle, color = MutedInk, fontSize = TypeLabel)
            }
        }
        Spacer(Modifier.height(18.dp))
        content()
    }
}

@Composable
private fun AssistantChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(onClick = onClick, selected = selected, accent = selected, wrapContent = true, modifier = modifier.height(46.dp)) {
        Text(label, color = Ink, fontSize = TypeLabel, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun <T> ChoiceGroup(
    title: String,
    options: List<T>,
    selected: T,
    text: (T) -> String,
    onSelect: (T) -> Unit,
    firstFocus: FocusRequester? = null,
) {
    Text(title, color = MutedInk, fontSize = TypeLabel, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, option ->
            AssistantChip(
                text(option), option == selected, { onSelect(option) },
                if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
    Spacer(Modifier.height(18.dp))
}

/** Ligne d'un contenu proposé : affiche, titre, détail (« Film · ★ 7,4 ») et, si l'IA en donne une, sa raison. */
@Composable
private fun AssistantEntryRow(entry: MediaEntry, detail: String?, why: String?, onClick: () -> Unit) {
    FocusableSurface(onClick = onClick, focusScale = 1.01f, modifier = Modifier.fillMaxWidth().height(if (why != null) 96.dp else 78.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (entry.type == MediaType.Live) {
                ChannelLogo(entry.iconUrl, entry.displayName, Modifier.width(64.dp).height(64.dp))
            } else {
                MediaArtwork(entry.iconUrl, entry.displayName, Modifier.width(52.dp).height(72.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.displayName, color = Ink, fontSize = TypeBody, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!detail.isNullOrBlank()) Text(detail, color = FocusBlueBright, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (why != null) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text("✦", color = FocusBlueBright, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(why, color = Ink.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ---------- Ce soir ? ----------

@Composable
fun TonightScreen(
    state: TonightUiState,
    onStart: (TonightAnswers) -> Unit,
    onOpen: (MediaEntry) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var mood by remember { mutableStateOf(TonightMood.Relax) }
    var length by remember { mutableStateOf(TonightLength.Film) }
    var company by remember { mutableStateOf(TonightCompany.Alone) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    AssistantFrame("Ce soir ?", "Trois questions, cinq propositions", onBack) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            Column(Modifier.width(500.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                ChoiceGroup("Votre humeur", TonightMood.entries, mood, TonightMood::label, { mood = it }, firstFocus)
                ChoiceGroup("Combien de temps ?", TonightLength.entries, length, TonightLength::label, { length = it })
                ChoiceGroup("Avec qui ?", TonightCompany.entries, company, TonightCompany::label, { company = it })
                FocusableSurface(
                    onClick = { onStart(TonightAnswers(mood, length, company)) },
                    enabled = !state.loading,
                    accent = true,
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AiButtonLabel(if (state.picks.isEmpty()) "Proposer" else "Proposer autre chose", state.loading)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AiLoadingIndicator("L'assistant compose votre soirée…", visible = true)
                    }
                    state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.error, color = MutedInk, fontSize = TypeBody)
                    }
                    state.picks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Répondez aux trois questions pour recevoir vos propositions.", color = MutedInk, fontSize = TypeBody)
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.picks, key = { it.entry.key + it.why }) { pick ->
                            AssistantEntryRow(pick.entry, pick.detail, pick.why) { onOpen(pick.entry) }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Collections ----------

@Composable
fun CollectionsScreen(
    state: CollectionsUiState,
    onOpen: (MediaEntry) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(state.collections.isNotEmpty()) { if (state.collections.isNotEmpty()) runCatching { firstFocus.requestFocus() } }
    AssistantFrame("Collections", "Sagas et thèmes composés par l'assistant", onBack) {
        when {
            state.loading || !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AiLoadingIndicator("L'assistant regroupe votre catalogue…", visible = true)
            }
            state.collections.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.error ?: "Aucune collection à proposer pour l'instant.", color = MutedInk, fontSize = TypeBody)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(CardRowSpacing)) {
                items(state.collections.size, key = { state.collections[it].title }) { index ->
                    val collection = state.collections[index]
                    HomeCardRow(
                        title = if (collection.ordered) "${collection.title} · à voir dans l'ordre" else collection.title,
                        entries = collection.entries.map { it to null },
                        firstFocusRequester = if (index == 0) firstFocus else null,
                        restoreItemKey = null,
                        onEntryClick = onOpen,
                    )
                }
            }
        }
    }
}

// ---------- Quoi de neuf maintenant ? ----------

@Composable
fun WhatsNewScreen(
    state: BriefUiState,
    onRefresh: () -> Unit,
    onOpen: (MediaEntry) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(state.loading) { if (!state.loading) runCatching { firstFocus.requestFocus() } }
    AssistantFrame("Quoi de neuf maintenant ?", "Matchs et programmes en direct", onBack) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FocusableSurface(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.width(240.dp).height(52.dp).focusRequester(firstFocus)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AiButtonLabel("Actualiser", state.loading) }
                }
                Spacer(Modifier.width(18.dp))
                if (state.headline != null) {
                    Text(state.headline, color = Ink, fontSize = TypeBody, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
            when {
                state.loading || !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AiLoadingIndicator("L'assistant résume ce qui passe en ce moment…", visible = true)
                }
                state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error ?: "Rien à signaler pour l'instant.", color = MutedInk, fontSize = TypeBody)
                }
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.items.size, key = { state.items[it].text }) { index ->
                        val item = state.items[index]
                        val channel = item.channel
                        if (channel != null) {
                            AssistantEntryRow(channel, "Regarder sur ${channel.displayName}", item.text) { onOpen(channel) }
                        } else {
                            GlassSurface(modifier = Modifier.fillMaxWidth()) {
                                Text("✦ ${item.text}", color = Ink, fontSize = TypeBody, modifier = Modifier.padding(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Télécommande téléphone ----------

/**
 * Chat avec la TV depuis le téléphone : QR code vers une page web locale (voir [PhoneChatServer]). Le serveur ne vit que
 * pendant cet écran ; chaque message coûte une requête à l'assistant, avec l'icône IA animée tant que la réponse est attendue.
 */
// Le logo est un fichier image (webp) lu tel quel pour la page du téléphone : openRawResource convient, malgré l'alerte lint.
@SuppressLint("ResourceType")
@Composable
fun RemoteScreen(state: RemoteUiState, onMessage: (String) -> String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val latest = rememberUpdatedState(onMessage)
    val resources = LocalContext.current.resources
    val server = remember {
        val logo = runCatching { resources.openRawResource(R.drawable.streamia_logo_mark).use { it.readBytes() } }.getOrNull()
        PhoneChatServer(logo) { text -> latest.value(text) }
    }
    val url = remember { runCatching { server.start() }.getOrNull() }
    DisposableEffect(Unit) { onDispose { server.close() } }
    val qr = remember(url) { url?.let(::qrBitmap) }
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
    AssistantFrame("Télécommande téléphone", "Écrivez à la TV depuis votre téléphone", onBack) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            GlassSurface(modifier = Modifier.width(360.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (qr != null) {
                        Image(qr.asImageBitmap(), "QR code", Modifier.size(220.dp), filterQuality = FilterQuality.None)
                        Spacer(Modifier.height(14.dp))
                        Text("Scannez ce code avec votre téléphone (même réseau que la TV).", color = Ink, fontSize = TypeLabel, lineHeight = 20.sp)
                    } else {
                        Text("Aucun réseau local détecté : connectez la TV au Wi-Fi ou à l'Ethernet.", color = MutedInk, fontSize = TypeBody)
                    }
                    Spacer(Modifier.height(14.dp))
                    FocusableSurface(onClick = onBack, modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(backFocus)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BackLabel(TypeLabel, 14.dp) }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Essayez : « mets beIN Sports 1 », « reprends ma série », « trouve le match du PSG ce soir », « un film d'action des années 90 ».", color = MutedInk, fontSize = TypeLabel, lineHeight = 20.sp)
                AiLoadingIndicator("L'assistant traite votre message…", state.busy)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.log.asReversed(), key = { it.message + it.reply }) { exchange ->
                        GlassSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(exchange.message, color = MutedInk, fontSize = TypeLabel)
                                Text("✦ ${exchange.reply}", color = Ink, fontSize = TypeBody)
                            }
                        }
                    }
                }
            }
        }
    }
}

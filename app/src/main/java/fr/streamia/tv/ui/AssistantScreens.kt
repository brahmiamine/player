package fr.streamia.tv.ui

import fr.streamia.tv.ui.theme.ButtonHeight
import fr.streamia.tv.ui.theme.BackButtonWidth
import fr.streamia.tv.ui.theme.PillActionHeight
import fr.streamia.tv.ui.theme.PillHeight
import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.RadiusPanel
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.HeroWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.KickerLetterSpacing
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusPill
import fr.streamia.tv.ui.theme.TypeBody
import fr.streamia.tv.ui.theme.TypeHero
import fr.streamia.tv.ui.theme.TypeLabel
import fr.streamia.tv.ui.theme.TypeScreenTitle

// Écrans de l'assistant IA sur TV : Ce soir ?
// Ils ne sont atteignables que quand l'assistant est actif (voir StreamiaApp : l'assistant coupé les ferme).

@Composable
private fun AssistantFrame(title: String, subtitle: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp)) {
        GlassSurface(modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(RadiusPill)) {
            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                FocusableSurface(onClick = onBack, radius = RadiusPill, modifier = Modifier.width(BackButtonWidth).height(ButtonHeight)) {
                    BackLabel(TypeBody, 18.dp)
                }
                Spacer(Modifier.width(18.dp))
                Text("✦ $title", color = Ink, fontSize = TypeScreenTitle, fontWeight = HeadingWeight)
                Spacer(Modifier.weight(1f))
                Text(subtitle, color = MutedInk, fontSize = TypeBody, modifier = Modifier.padding(end = 60.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        content()
    }
}

@Composable
private fun AssistantChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Même pastille que la carte « Ce soir ? » de l'accueil (36 dp, 15 sp) ; la sélection passe en aplat rose.
    FocusableSurface(
        onClick = onClick,
        idleBackground = if (selected) AccentPink else Color.White.copy(alpha = 0.14f),
        wrapContent = true,
        radius = RadiusPill,
        modifier = modifier.height(PillHeight),
    ) {
        Text(label, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp))
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
    Text(title.uppercase(java.util.Locale.FRENCH), color = MutedInk, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = KickerLetterSpacing)
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEachIndexed { index, option ->
            AssistantChip(
                text(option), option == selected, { onSelect(option) },
                if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
    Spacer(Modifier.height(18.dp))
}

// ---------- Ce soir ? ----------

/**
 * « Ce soir ? » sur TV : questions à gauche, proposition n°1 en grand (Regarder, Autre proposition), les quatre
 * autres en grille 2 × 2 dessous.
 */
@Composable
fun TonightScreen(
    state: TonightUiState,
    onStart: (TonightAnswers) -> Unit,
    onReplace: (Int) -> Unit,
    onOpen: (MediaEntry) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var mood by remember { mutableStateOf(state.answers.mood) }
    var length by remember { mutableStateOf(state.answers.length) }
    var company by remember { mutableStateOf(state.answers.company) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    AssistantFrame("Ce soir ?", "Trois questions, cinq propositions", onBack) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.width(340.dp).fillMaxHeight()) {
                ChoiceGroup("Votre humeur", TonightMood.entries, mood, TonightMood::label, { mood = it }, firstFocus)
                ChoiceGroup("Combien de temps ?", TonightLength.entries, length, TonightLength::label, { length = it })
                ChoiceGroup("Avec qui ?", TonightCompany.entries, company, TonightCompany::label, { company = it })
                Spacer(Modifier.weight(1f))
                FocusableSurface(
                    onClick = { onStart(TonightAnswers(mood, length, company)) },
                    enabled = !state.loading,
                    accent = true,
                    radius = RadiusPill,
                    modifier = Modifier.fillMaxWidth().height(PillActionHeight),
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
                    else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        val first = state.picks.first()
                        TonightHeroCard(first, replacing = state.replacing == 0, onOpen = { onOpen(first.entry) }, onReplace = { onReplace(0) })
                        val rest = state.picks.drop(1).take(4)
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            rest.chunked(2).forEach { pair ->
                                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    pair.forEach { pick ->
                                        TonightGridCard(pick, Modifier.weight(1f).fillMaxHeight()) { onOpen(pick.entry) }
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                            if (rest.size <= 2) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** Affiche d'une proposition ; un programme TV montre le logo de sa chaîne. */
@Composable
private fun TonightArtwork(entry: MediaEntry, modifier: Modifier, radius: androidx.compose.ui.unit.Dp) {
    val shaped = modifier.clip(RoundedCornerShape(radius))
    if (entry.type == MediaType.Live) {
        Box(shaped.background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            ChannelLogo(entry.iconUrl, entry.displayName, Modifier.fillMaxSize(), imagePadding = 14)
        }
    } else {
        MediaArtwork(entry.iconUrl, entry.displayName, shaped)
    }
}

/** Proposition n°1 : grande affiche, raison de l'assistant, Regarder et Autre proposition. */
@Composable
private fun TonightHeroCard(pick: TonightPick, replacing: Boolean, onOpen: () -> Unit, onReplace: () -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth().height(236.dp)) {
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            TonightArtwork(pick.entry, Modifier.width(140.dp).fillMaxHeight(), 16.dp)
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                Text("N°1 POUR VOUS", color = AccentPinkText, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = KickerLetterSpacing)
                Text(pick.entry.displayName, color = Ink, fontSize = TypeHero, lineHeight = 33.sp, fontWeight = HeroWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!pick.detail.isNullOrBlank()) Text(pick.detail, color = AccentPinkText, fontSize = TypeBody, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("✦ ${pick.why}", color = Ink.copy(alpha = 0.88f), fontSize = 17.sp, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusableSurface(onClick = onOpen, accent = true, wrapContent = true, radius = RadiusPill, modifier = Modifier.height(PillActionHeight)) {
                        Text("▶  Regarder", color = Ink, fontSize = TypeBody, fontWeight = HeroWeight, modifier = Modifier.padding(horizontal = 24.dp))
                    }
                    FocusableSurface(onClick = onReplace, enabled = !replacing, idleBackground = Color.White.copy(alpha = 0.12f), wrapContent = true, radius = RadiusPill, modifier = Modifier.height(PillActionHeight)) {
                        if (replacing) {
                            AiButtonLabel("Autre proposition", loading = true)
                        } else {
                            Text("↻  Autre proposition", color = Ink, fontSize = TypeBody, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Propositions 2 à 5 : affiche, titre, détail et raison ; OK ouvre le contenu. */
@Composable
private fun TonightGridCard(pick: TonightPick, modifier: Modifier, onClick: () -> Unit) {
    FocusableSurface(onClick = onClick, focusScale = 1.02f, idleBackground = Color.White.copy(alpha = 0.07f), radius = RadiusPanel, modifier = modifier) {
        Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TonightArtwork(pick.entry, Modifier.width(76.dp).fillMaxHeight(), 12.dp)
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                Text(pick.entry.displayName, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!pick.detail.isNullOrBlank()) Text(pick.detail, color = AccentPinkText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("✦ ${pick.why}", color = Ink.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

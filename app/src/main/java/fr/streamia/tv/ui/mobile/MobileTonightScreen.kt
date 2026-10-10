package fr.streamia.tv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.TonightAnswers
import fr.streamia.tv.data.TonightCompany
import fr.streamia.tv.data.TonightLength
import fr.streamia.tv.data.TonightMood
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.AiButtonLabel
import fr.streamia.tv.ui.AiLoadingIndicator
import fr.streamia.tv.ui.AiSparkle
import fr.streamia.tv.ui.ChannelLogo
import fr.streamia.tv.ui.MediaArtwork
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.TonightPick
import fr.streamia.tv.ui.TonightUiState
import fr.streamia.tv.ui.labels
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkLight
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.KickerLetterSpacing
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusPanel
import fr.streamia.tv.ui.theme.RadiusPill

/**
 * « Ce soir ? » sur téléphone : réponses résumées dans une pastille « Modifier » qui ouvre la feuille de questions,
 * proposition n°1 en grand (Regarder, Autre proposition), puis les suivantes en lignes. La feuille s'ouvre d'elle-même
 * tant qu'il n'y a aucune proposition.
 */
@Composable
fun MobileTonightScreen(
    state: TonightUiState,
    onStart: (TonightAnswers) -> Unit,
    onReplace: (Int) -> Unit,
    onOpen: (MediaEntry) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var sheetOpen by rememberSaveable { mutableStateOf(state.picks.isEmpty() && !state.loading) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = MobileGutter, end = MobileGutter, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MobileIconButton(StreamiaIconGlyph.ChevronLeft, onClick = onBack)
                Text("✦ Ce soir ?", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            AnswersPill(
                state.answers,
                Modifier.padding(start = MobileGutter, end = MobileGutter, top = 12.dp),
                onClick = { sheetOpen = true },
            )
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AiLoadingIndicator("L'assistant compose votre soirée…", visible = true)
                }
                state.error != null -> CenteredNote(state.error)
                state.picks.isEmpty() -> CenteredNote("Répondez aux trois questions pour recevoir vos propositions.")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = MobileGutter, end = MobileGutter, top = 14.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(state.picks, key = { _, pick -> pick.entry.key + pick.why }) { index, pick ->
                        if (index == 0) {
                            HeroPick(pick, replacing = state.replacing == 0, onOpen = { onOpen(pick.entry) }, onReplace = { onReplace(0) })
                        } else {
                            PickRow(pick) { onOpen(pick.entry) }
                        }
                    }
                }
            }
        }
        if (sheetOpen) {
            QuestionsSheet(
                initial = state.answers,
                onDismiss = { sheetOpen = false },
                onSubmit = { answers ->
                    sheetOpen = false
                    onStart(answers)
                },
            )
        }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MutedInk, fontSize = 14.sp, lineHeight = 19.sp)
    }
}

/** « Détente · Un film · Seul    Modifier ⌄ ». */
@Composable
private fun AnswersPill(answers: TonightAnswers, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(RadiusPill)
    Row(
        modifier
            .fillMaxWidth()
            .height(MobileMinTouch)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(answers.labels.joinToString(" · "), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("Modifier ⌄", color = AccentPinkText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PickArtwork(entry: MediaEntry, modifier: Modifier, radius: Dp) {
    val shaped = modifier.clip(RoundedCornerShape(radius))
    if (entry.type == MediaType.Live) {
        Box(shaped.background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            ChannelLogo(entry.iconUrl, entry.displayName, Modifier.fillMaxSize(), imagePadding = 16)
        }
    } else {
        MediaArtwork(entry.iconUrl, entry.displayName, shaped)
    }
}

/** Proposition n°1 : affiche 190 dp, titre, détail, raison, Regarder et ↻. */
@Composable
private fun HeroPick(pick: TonightPick, replacing: Boolean, onOpen: () -> Unit, onReplace: () -> Unit) {
    MobileCard(Modifier.fillMaxWidth(), onClick = onOpen, radius = RadiusPanel) {
        Column {
            Box(Modifier.fillMaxWidth().height(190.dp)) {
                PickArtwork(pick.entry, Modifier.fillMaxSize(), 0.dp)
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp)
                        .clip(RoundedCornerShape(RadiusPill)).background(AccentPink)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) {
                    Text("N°1", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pick.entry.displayName, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!pick.detail.isNullOrBlank()) Text(pick.detail, color = AccentPinkText, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("✦ ${pick.why}", color = Ink.copy(alpha = 0.88f), fontSize = 14.sp, lineHeight = 19.sp)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(RadiusPill)).background(AccentPink)
                            .clickable(role = Role.Button, onClick = onOpen),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("▶  Regarder", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                            .clickable(enabled = !replacing, role = Role.Button, onClick = onReplace),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (replacing) AiSparkle(size = 20.dp) else Text("↻", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Propositions suivantes : affiche 56 × 80, titre, détail, raison, bouton ▶. */
@Composable
private fun PickRow(pick: TonightPick, onOpen: () -> Unit) {
    MobileCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PickArtwork(pick.entry, Modifier.width(56.dp).height(80.dp), 10.dp)
            Column(Modifier.weight(1f)) {
                Text(pick.entry.displayName, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!pick.detail.isNullOrBlank()) Text(pick.detail, color = AccentPinkText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                Text("✦ ${pick.why}", color = Ink.copy(alpha = 0.8f), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            Box(Modifier.size(MobileMinTouch).clip(CircleShape).background(AccentPink), contentAlignment = Alignment.Center) {
                Text("▶", color = Ink, fontSize = 16.sp)
            }
        }
    }
}

/** Feuille du bas « Vos envies ce soir » : les trois questions en pastilles, puis « ✦ Proposer ». */
@Composable
private fun QuestionsSheet(initial: TonightAnswers, onDismiss: () -> Unit, onSubmit: (TonightAnswers) -> Unit) {
    var mood by remember { mutableStateOf(initial.mood) }
    var length by remember { mutableStateOf(initial.length) }
    var company by remember { mutableStateOf(initial.company) }
    MobileBottomSheet(title = "Vos envies ce soir", subtitle = "Trois questions, cinq propositions", onDismiss = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetGroup("Votre humeur", TonightMood.entries, mood, TonightMood::label) { mood = it }
            SheetGroup("Combien de temps ?", TonightLength.entries, length, TonightLength::label) { length = it }
            SheetGroup("Avec qui ?", TonightCompany.entries, company, TonightCompany::label) { company = it }
            Box(
                Modifier.padding(top = 4.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(RadiusPill))
                    .background(Brush.verticalGradient(listOf(AccentPinkLight, AccentPink)))
                    .clickable(role = Role.Button) { onSubmit(TonightAnswers(mood, length, company)) },
                contentAlignment = Alignment.Center,
            ) {
                AiButtonLabel("Proposer", loading = false)
            }
        }
    }
}

@Composable
private fun <T> SheetGroup(title: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column {
        Text(title.uppercase(java.util.Locale.FRENCH), color = MutedInk, fontSize = 11.sp, fontWeight = HeadingWeight, letterSpacing = KickerLetterSpacing)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option -> MobileChip(text(option), option == selected, onClick = { onSelect(option) }) }
        }
    }
}

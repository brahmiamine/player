package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.HomeBlock
import fr.streamia.tv.data.HomePlace
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import kotlinx.coroutines.launch

// Fenêtres de choix et tuiles des Paramètres.

internal data class SettingsModalOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

internal data class SettingsModalState(
    val title: String,
    val description: String,
    val options: List<SettingsModalOption>,
)

@Composable
internal fun CitySearchModal(
    onSearch: suspend (String) -> List<HomePlace>,
    onPick: (HomePlace) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<HomePlace>?>(null) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.76f)).modalFocusTrap(),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(modifier = Modifier.width(580.dp)) {
            Column(Modifier.padding(26.dp)) {
                Text("Choisir une ville", color = Ink, fontSize = 24.sp, fontWeight = HeadingWeight)
                Spacer(Modifier.height(16.dp))
                TvTextField(query, { query = it }, "Nom de la ville", Modifier.fillMaxWidth().focusRequester(fieldFocus))
                Spacer(Modifier.height(10.dp))
                FocusableSurface(
                    onClick = {
                        scope.launch {
                            searching = true
                            results = onSearch(query)
                            searching = false
                        }
                    },
                    enabled = query.isNotBlank() && !searching,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (searching) "Recherche…" else "Rechercher", color = Ink, fontSize = 15.sp, fontWeight = HeadingWeight)
                    }
                }
                results?.let { places ->
                    Spacer(Modifier.height(12.dp))
                    if (places.isEmpty()) Text("Aucune ville trouvée.", color = MutedInk, fontSize = 13.sp)
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        places.forEach { place ->
                            FocusableSurface(onClick = { onPick(place) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                                    Text(place.name, color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Retour pour annuler", color = MutedInk, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Garde le focus dans un modal : sans cela, Haut/Bas au bout de la liste saute sur les cartes
 * visibles derrière lui (le modal n'est qu'une couche au-dessus de l'écran, pas une fenêtre).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun FocusTrap(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().modalFocusTrap()) { content() }
}

/** Piège de focus commun à tous les modals : le focus ne quitte jamais la couche tant qu'elle est ouverte. */
internal fun Modifier.modalFocusTrap(): Modifier =
    focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()

@Composable
internal fun SettingsSectionTitle(title: String) {
    Text(
        title.uppercase(),
        color = MutedInk,
        fontSize = 12.sp,
        fontWeight = HeadingWeight,
        letterSpacing = 1.1.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 0.dp, start = 4.dp),
    )
}

@Composable
internal fun SettingsChoiceModal(
    state: SettingsModalState,
    onDismiss: () -> Unit,
    onOption: (SettingsModalOption) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val firstFocus = remember(state.title) { FocusRequester() }
    LaunchedEffect(state.title) { runCatching { firstFocus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.76f)).modalFocusTrap(),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(modifier = Modifier.width(580.dp)) {
            Column(Modifier.padding(26.dp)) {
                Text(state.title, color = Ink, fontSize = 24.sp, fontWeight = HeadingWeight)
                Spacer(Modifier.height(8.dp))
                Text(state.description, color = MutedInk, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(18.dp))
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.options.forEachIndexed { index, option ->
                        FocusableSurface(
                            onClick = { onOption(option) },
                            selected = option.selected,
                            accent = option.selected,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                        ) {
                            Row(
                                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    option.label,
                                    color = Ink,
                                    fontSize = 15.sp,
                                    fontWeight = if (option.selected) HeadingWeight else androidx.compose.ui.text.font.FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                )
                                if (option.selected) {
                                    Text("Actuel", color = FocusBlueBright, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Retour pour annuler", color = MutedInk, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun HomeBlocksModal(
    blocks: List<Pair<HomeBlock, String>>,
    disabledBlocks: Set<HomeBlock>,
    onToggle: (HomeBlock) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.76f)).modalFocusTrap(),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(modifier = Modifier.width(640.dp)) {
            Column(Modifier.padding(26.dp)) {
                Text("Blocs de l'accueil", color = Ink, fontSize = 24.sp, fontWeight = HeadingWeight)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cochez les blocs à afficher sur l'accueil. Décochez pour les masquer.",
                    color = MutedInk,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    blocks.forEachIndexed { index, (block, label) ->
                        val enabled = block !in disabledBlocks
                        FocusableSurface(
                            onClick = { onToggle(block) },
                            selected = enabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                        ) {
                            Row(
                                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StreamiaIcon(
                                    if (enabled) StreamiaIconGlyph.CheckboxOn else StreamiaIconGlyph.CheckboxOff,
                                    tint = if (enabled) FocusBlueBright else MutedInk,
                                    size = 22.dp,
                                )
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    label,
                                    color = Ink,
                                    fontSize = 15.sp,
                                    fontWeight = if (enabled) HeadingWeight else androidx.compose.ui.text.font.FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Retour pour fermer", color = MutedInk, fontSize = 12.sp)
            }
        }
    }
}

/** Dernière carte de Paramètres ayant eu le focus : le focus lui est rendu quand le modal qu'elle a ouvert se ferme. */
internal class SettingsFocusTracker {
    var last: FocusRequester? = null
}

internal val LocalSettingsFocusTracker = androidx.compose.runtime.staticCompositionLocalOf<SettingsFocusTracker?> { null }

/** Groupe de lignes de réglage : colonne serrée sous un éventuel titre de section. */
@Composable
internal fun SettingsGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

/**
 * Ligne de réglage : titre et description à gauche, à droite la valeur courante et une flèche, ou un
 * interrupteur quand [toggle] est renseigné (le clic bascule alors directement). [glyph] et
 * [modifier] sont conservés pour les appelants historiques (grille de tuiles) mais ne servent plus.
 */
@Composable
internal fun SettingsTile(
    @Suppress("UNUSED_PARAMETER") glyph: StreamiaIconGlyph,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    @Suppress("UNUSED_PARAMETER") modifier: Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    toggle: Boolean? = null,
    description: String? = settingsRowDescription(title),
) {
    val tracker = LocalSettingsFocusTracker.current
    val requester = remember { FocusRequester() }
    FocusableSurface(
        onClick = onClick,
        enabled = enabled,
        selected = selected && toggle == null,
        onFocused = { tracker?.last = requester },
        focusScale = 1.02f,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).focusRequester(requester),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = if (enabled) Ink else MutedInk, fontSize = 17.sp, fontWeight = HeadingWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (description != null) {
                    Text(description, color = MutedInk, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (toggle != null) {
                SettingsSwitch(on = toggle, enabled = enabled)
            } else {
                Text(subtitle, color = if (enabled) Ink.copy(alpha = 0.85f) else MutedInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp))
                StreamiaIcon(StreamiaIconGlyph.ArrowForward, tint = MutedInk, size = 18.dp)
            }
        }
    }
}

@Composable
private fun SettingsSwitch(on: Boolean, enabled: Boolean) {
    Box(
        Modifier
            .width(54.dp).height(30.dp)
            .background(if (on && enabled) AccentPink else Color.White.copy(alpha = 0.22f), androidx.compose.foundation.shape.RoundedCornerShape(15.dp))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(24.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape))
    }
}

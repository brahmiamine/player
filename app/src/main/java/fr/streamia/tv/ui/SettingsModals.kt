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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.HomeBlock
import fr.streamia.tv.data.HomePlace
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
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.76f)),
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
    Box(Modifier.fillMaxSize().focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()) { content() }
}

@Composable
internal fun SettingsSectionTitle(title: String) {
    Text(
        title,
        color = FocusBlueBright,
        fontSize = 14.sp,
        fontWeight = HeadingWeight,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
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
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.76f)),
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
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.76f)),
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

@Composable
internal fun SettingsTile(
    glyph: StreamiaIconGlyph,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val tracker = LocalSettingsFocusTracker.current
    val requester = remember { FocusRequester() }
    FocusableSurface(
        onClick = onClick,
        enabled = enabled,
        selected = selected,
        accent = selected,
        onFocused = { tracker?.last = requester },
        modifier = modifier.fillMaxSize().focusRequester(requester),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            StreamiaIcon(glyph, size = 22.dp)
            Spacer(Modifier.height(5.dp))
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = HeadingWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = MutedInk, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

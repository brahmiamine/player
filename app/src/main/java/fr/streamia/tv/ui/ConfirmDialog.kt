package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import fr.streamia.tv.ui.theme.FocusBlueBright
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.TypeBody
import fr.streamia.tv.ui.theme.TypeLabel
import fr.streamia.tv.ui.theme.TypeSectionTitle

/** Confirmation d'une action qui fait quitter l'écran courant. Focus sur « Annuler » par sécurité. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.76f)), contentAlignment = Alignment.Center) {
        GlassSurface(modifier = Modifier.width(560.dp)) {
            Column(Modifier.padding(26.dp)) {
                Text(title, color = Ink, fontSize = TypeSectionTitle, fontWeight = HeadingWeight)
                Spacer(Modifier.height(8.dp))
                Text(message, color = MutedInk, fontSize = TypeBody)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusableSurface(onClick = onDismiss, modifier = Modifier.width(200.dp).height(52.dp).focusRequester(cancelFocus)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Annuler", color = Ink, fontSize = TypeLabel, fontWeight = FontWeight.Bold)
                        }
                    }
                    FocusableSurface(onClick = onConfirm, accent = true, modifier = Modifier.width(200.dp).height(52.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(confirmLabel, color = Ink, fontSize = TypeLabel, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Choix unique dans une liste, à cases à cocher : une seule case cochée (l'option en cours), OK
 * coche et ferme. Longue liste (pistes audio/sous-titres) : défilement, ouverte sur l'option en
 * cours. Retour ferme sans rien changer.
 */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val initialIndex = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val selectedFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { selectedFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.76f)), contentAlignment = Alignment.Center) {
        GlassSurface(modifier = Modifier.width(560.dp)) {
            Column(Modifier.padding(26.dp)) {
                Text(title, color = Ink, fontSize = TypeSectionTitle, fontWeight = HeadingWeight)
                Spacer(Modifier.height(16.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 470.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(options) { index, label ->
                        val checked = index == selectedIndex
                        FocusableSurface(
                            onClick = { onSelect(index) },
                            selected = checked,
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                                .then(if (index == initialIndex) Modifier.focusRequester(selectedFocus) else Modifier),
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                StreamiaIcon(
                                    if (checked) StreamiaIconGlyph.CheckboxOn else StreamiaIconGlyph.CheckboxOff,
                                    tint = if (checked) FocusBlueBright else MutedInk,
                                    size = 22.dp,
                                )
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    label,
                                    color = Ink,
                                    fontSize = TypeBody,
                                    fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("OK pour choisir · Retour pour annuler", color = MutedInk, fontSize = TypeLabel)
            }
        }
    }
}

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.AiProvider
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk

/** Saisie de la clé d'API d'un fournisseur. Vide + Enregistrer = supprimer la clé. */
@Composable
internal fun AiKeyModal(provider: AiProvider, hasKey: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    var key by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.76f)), contentAlignment = Alignment.Center) {
        GlassSurface(modifier = Modifier.width(640.dp)) {
            Column(Modifier.padding(26.dp)) {
                Text("Clé d'API · ${provider.label}", color = Ink, fontSize = 22.sp, fontWeight = HeadingWeight)
                Spacer(Modifier.height(8.dp))
                Text(
                    "La clé est chiffrée sur cet appareil. Elle n'apparaît jamais dans les sauvegardes ni sur GitHub." +
                        if (hasKey) " Une clé est déjà enregistrée : en saisir une nouvelle la remplace, valider un champ vide la supprime." else "",
                    color = MutedInk, fontSize = 13.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(14.dp))
                TvTextField(key, { key = it }, "Clé d'API", Modifier.fillMaxWidth().focusRequester(fieldFocus), PasswordVisualTransformation())
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusableSurface(onClick = onDismiss, modifier = Modifier.weight(1f).height(52.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Annuler", color = Ink, fontSize = 15.sp, fontWeight = HeadingWeight) }
                    }
                    FocusableSurface(onClick = { onSave(key) }, accent = key.isNotBlank(), enabled = key.isNotBlank() || hasKey, modifier = Modifier.weight(1f).height(52.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (key.isBlank()) "Supprimer la clé" else "Enregistrer", color = Ink, fontSize = 15.sp, fontWeight = HeadingWeight)
                        }
                    }
                }
            }
        }
    }
}

/** Liste tous les modèles du fournisseur (chargés avec la clé enregistrée) ; recherche instantanée, un seul choix. */
@Composable
internal fun AiModelPickerModal(
    provider: AiProvider,
    current: String?,
    load: suspend () -> Result<List<String>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var reload by remember { mutableIntStateOf(0) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(provider, reload) {
        models = null
        error = null
        load().onSuccess { models = it }.onFailure { error = it.message ?: "Chargement impossible." }
    }
    val shown = remember(models, query) {
        models.orEmpty().let { all -> if (query.isBlank()) all else all.filter { it.contains(query.trim(), ignoreCase = true) } }
    }
    val listState = rememberLazyListState()
    val modelFocus = remember { FocusRequester() }
    // Le focus doit entrer dans la fenêtre une fois la liste chargée : sinon il restait sur les
    // Paramètres en arrière-plan. Cible : le modèle actuel s'il est listé, sinon le premier.
    val focusedModel = shown.firstOrNull { it == current } ?: shown.firstOrNull()
    LaunchedEffect(models, error) {
        when {
            models == null && error == null -> Unit
            focusedModel != null -> {
                listState.scrollToItem(shown.indexOf(focusedModel))
                delay(50)
                runCatching { modelFocus.requestFocus() }
            }
            else -> runCatching { closeFocus.requestFocus() }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.76f)), contentAlignment = Alignment.Center) {
        GlassSurface(modifier = Modifier.width(700.dp)) {
            // Le focus ne sort pas de la fenêtre vers les Paramètres affichés derrière.
            Column(Modifier.padding(26.dp).focusProperties { onExit = { cancelFocusChange() } }.focusGroup()) {
                Text("Modèle · ${provider.label}", color = Ink, fontSize = 22.sp, fontWeight = HeadingWeight)
                Spacer(Modifier.height(8.dp))
                when {
                    error != null -> {
                        Text(error!!, color = FocusBlueBright, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        FocusableSurface(onClick = { reload++ }, modifier = Modifier.fillMaxWidth().height(52.dp).focusRequester(closeFocus)) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Réessayer", color = Ink, fontSize = 15.sp, fontWeight = HeadingWeight) }
                        }
                    }
                    models == null -> Text("Chargement des modèles…", color = MutedInk, fontSize = 14.sp)
                    else -> {
                        Text("${models!!.size} modèles disponibles", color = MutedInk, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        TvTextField(query, { query = it }, "Filtrer par nom", Modifier.fillMaxWidth().focusRequester(closeFocus))
                        Spacer(Modifier.height(10.dp))
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(shown, key = { it }) { model ->
                                FocusableSurface(onClick = { onPick(model) }, selected = model == current, modifier = Modifier.fillMaxWidth().height(48.dp).then(if (model == focusedModel) Modifier.focusRequester(modelFocus) else Modifier)) {
                                    Text(
                                        model,
                                        color = Ink,
                                        fontSize = 14.sp,
                                        fontWeight = if (model == current) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Retour pour fermer", color = MutedInk, fontSize = 12.sp)
            }
        }
    }
}

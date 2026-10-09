package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.AiProvider
import fr.streamia.tv.data.AiUsage
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
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
                AiProviderTitle("Clé d'API · ${provider.label}", provider)
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
                AiProviderTitle("Modèle · ${provider.label}", provider)
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

/** Titre de fenêtre précédé du logo du fournisseur. */
@Composable
internal fun AiProviderTitle(title: String, provider: AiProvider) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(provider.logo), contentDescription = provider.label, modifier = Modifier.size(32.dp))
        Text(title, color = Ink, fontSize = 22.sp, fontWeight = HeadingWeight)
    }
}

/** Consommation de chaque modèle utilisé : requêtes, tokens, durée, coût estimé, quotas et caractéristiques annoncées. */
@Composable
internal fun AiUsageModal(load: suspend () -> List<AiUsage>, onReset: () -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    var reload by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<AiUsage>?>(null) }
    LaunchedEffect(reload) { rows = load() }
    val closeFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(rows) {
        if (rows != null) {
            delay(50)
            runCatching { if (rows.orEmpty().isNotEmpty()) firstRowFocus.requestFocus() else closeFocus.requestFocus() }
        }
    }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.76f)), contentAlignment = Alignment.Center) {
        GlassSurface(modifier = Modifier.width(820.dp)) {
            Column(Modifier.padding(26.dp).focusProperties { onExit = { cancelFocusChange() } }.focusGroup()) {
                Text("Consommation de l'assistant IA", color = Ink, fontSize = 22.sp, fontWeight = HeadingWeight)
                Spacer(Modifier.height(8.dp))
                val list = rows
                when {
                    list == null -> Text("Chargement…", color = MutedInk, fontSize = 14.sp)
                    list.isEmpty() -> Text(
                        "Aucune requête envoyée pour l'instant. Les compteurs apparaissent dès qu'une fonction IA (traduction, similaires) utilise un modèle.",
                        color = MutedInk, fontSize = 14.sp, lineHeight = 19.sp,
                    )
                    else -> {
                        val cost = list.mapNotNull(AiUsage::estimatedCost).takeIf { it.isNotEmpty() }?.sum()
                        Text(
                            "Total : ${numbers.format(list.sumOf(AiUsage::requests))} requêtes · ${numbers.format(list.sumOf(AiUsage::totalTokens))} tokens" +
                                (cost?.let { " · ≈ ${formatDollars(it)}" } ?: "") +
                                "  (${numbers.format(list.sumOf(AiUsage::failures))} échec(s))",
                            color = FocusBlueBright, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(10.dp))
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(list, key = { it.provider.name + "|" + it.model }) { usage ->
                                FocusableSurface(
                                    onClick = {},
                                    focusScale = 1.01f,
                                    modifier = Modifier.fillMaxWidth().height(196.dp).then(if (usage === list.first()) Modifier.focusRequester(firstRowFocus) else Modifier),
                                ) {
                                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(usage.provider.logo), null, Modifier.size(20.dp))
                                            Text("${usage.provider.label} · ${usage.model}", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        UsageLine("Requêtes", "${numbers.format(usage.requests)} (${numbers.format(usage.failures)} échec(s)) · durée moyenne ${usage.averageMillis} ms")
                                        UsageLine("Tokens", "${numbers.format(usage.totalTokens)} (entrée ${numbers.format(usage.promptTokens)} · sortie ${numbers.format(usage.completionTokens)})")
                                        usage.byFeature.takeIf { it.isNotEmpty() }?.let { byFeature ->
                                            UsageLine("Fonctions", byFeature.entries.joinToString(" · ") { "${it.key.label} ${it.value}" })
                                        }
                                        usage.estimatedCost?.let { UsageLine("Coût estimé", formatDollars(it)) }
                                        val info = usage.info
                                        UsageLine(
                                            "Modèle",
                                            listOfNotNull(
                                                info?.contextTokens?.let { "contexte ${numbers.format(it)} tokens" },
                                                info?.maxOutputTokens?.let { "sortie max ${numbers.format(it)}" },
                                                info?.inputPricePerMillion?.let { "entrée ${formatDollars(it)}/M" },
                                                info?.outputPricePerMillion?.let { "sortie ${formatDollars(it)}/M" },
                                            ).joinToString(" · ").ifEmpty { "caractéristiques non annoncées par le fournisseur" },
                                        )
                                        UsageLine(
                                            "Quotas",
                                            listOfNotNull(
                                                usage.requestQuota?.let { "requêtes $it" },
                                                usage.tokenQuota?.let { "tokens $it" },
                                            ).joinToString(" · ").ifEmpty { "non communiqués par le fournisseur" },
                                        )
                                        UsageLine("Dernier appel", DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(usage.lastAtMillis)) +
                                            (usage.lastError?.let { " · $it" } ?: ""), maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusableSurface(onClick = onDismiss, modifier = Modifier.weight(1f).height(52.dp).focusRequester(closeFocus)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Fermer", color = Ink, fontSize = 15.sp, fontWeight = HeadingWeight) }
                    }
                    FocusableSurface(
                        onClick = { onReset(); rows = emptyList(); reload++ },
                        enabled = rows.orEmpty().isNotEmpty(),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Remettre à zéro", color = Ink, fontSize = 15.sp, fontWeight = HeadingWeight) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageLine(label: String, value: String, maxLines: Int = 1) {
    Row {
        Text(label, color = MutedInk, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
        Text(value, color = Ink, fontSize = 12.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** 0,0006 $ pour les très petits montants, 1,25 $ sinon. */
private fun formatDollars(value: Double): String =
    if (value < 0.01) String.format(java.util.Locale.US, "%.4f $", value) else String.format(java.util.Locale.US, "%.2f $", value)

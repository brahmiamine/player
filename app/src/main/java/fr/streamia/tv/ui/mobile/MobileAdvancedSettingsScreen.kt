package fr.streamia.tv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.AiLanguages
import fr.streamia.tv.data.AiProvider
import fr.streamia.tv.data.AiUsage
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.HomePlace
import fr.streamia.tv.data.PrayerMethod
import fr.streamia.tv.data.UpdateCheckResult
import fr.streamia.tv.ui.AccentPill
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.TvTextField
import fr.streamia.tv.ui.UpdateDialogAction
import fr.streamia.tv.ui.backupFileName
import fr.streamia.tv.ui.prayerMethodLabel
import fr.streamia.tv.ui.updateDialogContent
import fr.streamia.tv.ui.updateSubtitle
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import kotlinx.coroutines.launch
import java.text.NumberFormat

private sealed interface AdvancedSheet {
    data object City : AdvancedSheet
    data object Prayer : AdvancedSheet
    data object Provider : AdvancedSheet
    data object Language : AdvancedSheet
    data object ApiKey : AdvancedSheet
    data object Model : AdvancedSheet
    data object Usage : AdvancedSheet
    data object Update : AdvancedSheet
}

/**
 * Réglages avancés au doigt : ville et prières, assistant IA, mises à jour, sauvegarde. Le reste
 * des réglages vit déjà dans [MobileSettingsScreen] ; chaque choix s'ouvre en feuille du bas.
 */
@Composable
fun MobileAdvancedSettingsScreen(
    settings: AppSettings,
    detectedPlaceName: String?,
    currentVersion: String,
    updateChecking: Boolean,
    updateCheck: UpdateCheckResult?,
    onCheckForUpdate: () -> Unit,
    onDismissUpdateCheck: () -> Unit,
    onInstallUpdate: () -> Unit,
    onAllowUpdateInstall: () -> Unit,
    onExportBackup: suspend () -> String,
    onImportBackup: suspend (String) -> String,
    onSearchCities: suspend (String) -> List<HomePlace>,
    onSetHomePlace: (HomePlace?) -> Unit,
    onSetPrayerMethod: (PrayerMethod) -> Unit,
    onToggleAi: () -> Unit,
    onSetAiLanguage: (String) -> Unit,
    onLoadAiUsage: suspend () -> List<AiUsage>,
    onResetAiUsage: () -> Unit,
    onSetAiProvider: (AiProvider) -> Unit,
    onSetAiModel: (AiProvider, String) -> Unit,
    hasAiKey: (AiProvider) -> Boolean,
    onSaveAiKey: (AiProvider, String) -> Unit,
    onLoadAiModels: suspend (AiProvider) -> Result<List<String>>,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<AdvancedSheet?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var keyRevision by remember { mutableIntStateOf(0) }
    val keySet = remember(keyRevision, settings.aiProvider) { hasAiKey(settings.aiProvider) }

    // La fenêtre de mise à jour s'ouvre d'elle-même quand elle attend un geste ou change d'étape.
    val updateStateKey = updateCheck?.let { it::class.simpleName }
    var lastUpdateStateKey by remember { mutableStateOf(updateStateKey) }
    LaunchedEffect(updateStateKey) {
        if (updateStateKey == lastUpdateStateKey) return@LaunchedEffect
        lastUpdateStateKey = updateStateKey
        if (updateCheck is UpdateCheckResult.Downloaded || updateCheck is UpdateCheckResult.AwaitingInstallPermission ||
            updateCheck is UpdateCheckResult.Installing || updateCheck is UpdateCheckResult.Error
        ) sheet = AdvancedSheet.Update
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = onExportBackup()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("Impossible d'écrire à cet emplacement.")
            }.onSuccess { message = "Sauvegarde enregistrée." }
                .onFailure { message = "Échec de la sauvegarde : " + (it.message ?: "erreur inconnue") + "." }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalStateException("Fichier illisible.")
                onImportBackup(json)
            }.onSuccess { message = it }
                .onFailure { message = "Échec de la restauration : " + (it.message ?: "fichier invalide") + "." }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MobileIconButton(StreamiaIconGlyph.ArrowBack, onClick = onBack)
            Text("Réglages avancés", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 4.dp))
        }
        message?.let {
            Text(it, color = AccentPinkText, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clickable { message = null }.padding(horizontal = 24.dp, vertical = 6.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Group("Ville et prières") {
                SettingsLink("Ville (météo et prières)", settings.homePlace?.name ?: ("Automatique" + detectedPlaceName?.let { " · $it" }.orEmpty())) { sheet = AdvancedSheet.City }
                SettingsLink("Calcul des prières", prayerMethodLabel(settings.prayerMethod)) { sheet = AdvancedSheet.Prayer }
            }
            Group("Assistant IA") {
                SettingsToggle("Assistant IA", "Coupe toutes les fonctions IA quand il est désactivé", settings.aiEnabled, onToggleAi)
                if (settings.aiEnabled) {
                    SettingsLink("Fournisseur", settings.aiProvider.label) { sheet = AdvancedSheet.Provider }
                    SettingsLink("Clé d'API", if (keySet) "Enregistrée" else "Non définie") { sheet = AdvancedSheet.ApiKey }
                    if (keySet) SettingsLink("Modèle", settings.aiModels[settings.aiProvider] ?: "Aucun choisi") { sheet = AdvancedSheet.Model }
                    SettingsLink("Langue des descriptions", AiLanguages.name(settings.aiLanguage)) { sheet = AdvancedSheet.Language }
                }
                SettingsLink("Consommation IA", "Requêtes, tokens, quotas") { sheet = AdvancedSheet.Usage }
            }
            Group("Application") {
                SettingsLink("Mises à jour", updateSubtitle(currentVersion, updateChecking, updateCheck)) {
                    sheet = AdvancedSheet.Update
                    // Rien en cours ni en attente : nouvelle vérification.
                    if (!updateChecking && (updateCheck == null || updateCheck is UpdateCheckResult.UpToDate ||
                            updateCheck is UpdateCheckResult.NoTaggedRelease || updateCheck is UpdateCheckResult.Error && updateCheck.release == null)
                    ) onCheckForUpdate()
                }
                SettingsLink("Sauvegarder les réglages", "Exporter les préférences") { exportLauncher.launch(backupFileName()) }
                SettingsLink("Restaurer les réglages", "Importer une sauvegarde") {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
            }
        }
    }

    val close = { sheet = null }
    when (sheet) {
        AdvancedSheet.City -> CitySheet(onSearchCities, onPick = { onSetHomePlace(it); close() }, onAuto = { onSetHomePlace(null); close() }, onDismiss = close)
        AdvancedSheet.Prayer -> ChoiceSheet("Calcul des prières", PrayerMethod.entries.toList(), { prayerMethodLabel(it) }, settings.prayerMethod, { onSetPrayerMethod(it); close() }, close)
        AdvancedSheet.Provider -> ChoiceSheet("Fournisseur d'IA", AiProvider.entries.toList(), { it.label }, settings.aiProvider, { onSetAiProvider(it); close() }, close)
        AdvancedSheet.Language -> ChoiceSheet("Langue des descriptions", AiLanguages.all, { it.second }, AiLanguages.all.firstOrNull { it.first == settings.aiLanguage }, { onSetAiLanguage(it.first); close() }, close)
        AdvancedSheet.ApiKey -> ApiKeySheet(settings.aiProvider, keySet, onSave = { onSaveAiKey(settings.aiProvider, it); keyRevision++; close() }, onDismiss = close)
        AdvancedSheet.Model -> ModelSheet(settings.aiProvider, settings.aiModels[settings.aiProvider], onLoadAiModels, onPick = { onSetAiModel(settings.aiProvider, it); close() }, onDismiss = close)
        AdvancedSheet.Usage -> UsageSheet(onLoadAiUsage, onResetAiUsage, close)
        AdvancedSheet.Update -> UpdateSheet(
            updateChecking, updateCheck, currentVersion,
            onAction = { action ->
                when (action) {
                    UpdateDialogAction.Install, UpdateDialogAction.RetryInstall -> onInstallUpdate()
                    UpdateDialogAction.OpenPermission -> onAllowUpdateInstall()
                    UpdateDialogAction.RetryCheck -> onCheckForUpdate()
                    UpdateDialogAction.Ok -> { close(); onDismissUpdateCheck() }
                }
            },
            onClose = { close(); }, onClear = onDismissUpdateCheck,
        )
        null -> Unit
    }
}

@Composable
private fun <T> ChoiceSheet(title: String, values: List<T>, label: (T) -> String, selected: T?, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    MobileBottomSheet(title, onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            values.forEach { value ->
                val on = value == selected
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.RadioButton) { onPick(value) }.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label(value), color = if (on) AccentPinkText else Ink, fontSize = 15.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (on) Text("✓", color = AccentPinkText, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    AccentPill(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)) {
        Text(label, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 15.dp).fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun CitySheet(onSearch: suspend (String) -> List<HomePlace>, onPick: (HomePlace) -> Unit, onAuto: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<HomePlace>?>(null) }
    MobileBottomSheet("Ville", "Météo et heures de prière de l'accueil", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MobileSheetAction("Détection automatique", StreamiaIconGlyph.Guide, onClick = onAuto)
            TvTextField(query, { query = it }, "Nom de la ville", Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            ActionButton(if (searching) "Recherche…" else "Rechercher", enabled = query.isNotBlank() && !searching) {
                scope.launch { searching = true; results = onSearch(query); searching = false }
            }
            results?.let { places ->
                if (places.isEmpty()) Text("Aucune ville trouvée.", color = MutedInk, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
                places.forEach { place ->
                    Text(
                        place.name, color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onPick(place) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiKeySheet(provider: AiProvider, hasKey: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    MobileBottomSheet("Clé d'API · ${provider.label}", onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "La clé est chiffrée sur cet appareil et n'apparaît jamais dans les sauvegardes." +
                    if (hasKey) " Une clé est déjà enregistrée : en saisir une nouvelle la remplace, valider un champ vide la supprime." else "",
                color = MutedInk, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 20.dp),
            )
            TvTextField(key, { key = it }, "Clé d'API", Modifier.fillMaxWidth().padding(horizontal = 16.dp), PasswordVisualTransformation())
            ActionButton(if (key.isBlank()) "Supprimer la clé" else "Enregistrer", enabled = key.isNotBlank() || hasKey) { onSave(key) }
        }
    }
}

@Composable
private fun ModelSheet(provider: AiProvider, current: String?, load: suspend (AiProvider) -> Result<List<String>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(provider, reload) {
        models = null; error = null
        load(provider).onSuccess { models = it }.onFailure { error = it.message ?: "Chargement impossible." }
    }
    val shown = models.orEmpty().let { all -> if (query.isBlank()) all else all.filter { it.contains(query.trim(), ignoreCase = true) } }
    MobileBottomSheet("Modèle · ${provider.label}", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                error != null -> {
                    Text(error!!, color = AccentPinkText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
                    ActionButton("Réessayer") { reload++ }
                }
                models == null -> Text("Chargement…", color = MutedInk, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                else -> {
                    TvTextField(query, { query = it }, "Rechercher un modèle", Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                        shown.forEach { model ->
                            val on = model == current
                            Text(
                                model, color = if (on) AccentPinkText else Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onPick(model) }.padding(horizontal = 20.dp, vertical = 14.dp),
                            )
                        }
                        if (shown.isEmpty()) Text("Aucun modèle.", color = MutedInk, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageSheet(load: suspend () -> List<AiUsage>, onReset: () -> Unit, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf<List<AiUsage>?>(null) }
    LaunchedEffect(Unit) { rows = load() }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    MobileBottomSheet("Consommation IA", onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val list = rows
            when {
                list == null -> Text("Chargement…", color = MutedInk, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
                list.isEmpty() -> Text(
                    "Aucune requête envoyée pour l'instant. Les compteurs apparaissent dès qu'une fonction IA utilise un modèle.",
                    color = MutedInk, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 20.dp),
                )
                else -> {
                    Text(
                        "${numbers.format(list.sumOf(AiUsage::requests))} requêtes · ${numbers.format(list.sumOf(AiUsage::totalTokens))} tokens · ${numbers.format(list.sumOf(AiUsage::failures))} échec(s)",
                        color = AccentPinkText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    list.forEach { usage ->
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(usage.provider.logo), null, Modifier.size(18.dp))
                                Text("${usage.provider.label} · ${usage.model}", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                "${numbers.format(usage.requests)} requêtes (${numbers.format(usage.failures)} échec(s)) · ${numbers.format(usage.totalTokens)} tokens",
                                color = MutedInk, fontSize = 12.sp,
                            )
                            usage.requestQuota?.let { Text("Quota requêtes $it", color = MutedInk, fontSize = 12.sp) }
                            usage.tokenQuota?.let { Text("Quota tokens $it", color = MutedInk, fontSize = 12.sp) }
                            usage.lastError?.let { Text(it, color = MutedInk, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    MobileSheetAction("Remettre à zéro", StreamiaIconGlyph.Delete, onClick = { onReset(); rows = emptyList() })
                }
            }
        }
    }
}

@Composable
private fun UpdateSheet(
    checking: Boolean,
    result: UpdateCheckResult?,
    currentVersion: String,
    onAction: (UpdateDialogAction) -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit,
) {
    val content = updateDialogContent(checking, result, currentVersion)
    val dismiss = { onClose(); if (content.closeClearsState) onClear() }
    MobileBottomSheet(content.title, content.newVersion?.let { "Version $it" }, onDismiss = dismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(content.message, color = Ink, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 20.dp))
            content.hint?.let { Text(it, color = MutedInk, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
            content.progress?.takeIf { it >= 0f }?.let {
                Text("${(it * 100).toInt()} %", color = AccentPinkText, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            }
            content.primary?.let { ActionButton(it.label) { onAction(it) } }
            MobileSheetAction(content.secondaryLabel, StreamiaIconGlyph.Close, tint = MutedInk, onClick = dismiss)
        }
    }
}

package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.BufferMode
import fr.streamia.tv.data.DisplayModeSwitch
import fr.streamia.tv.data.HomeBlock
import fr.streamia.tv.data.JustWatchSection
import fr.streamia.tv.data.homeBlock
import fr.streamia.tv.data.HomePlace
import fr.streamia.tv.data.LiveChannelSortOrder
import fr.streamia.tv.data.LiveStreamFormat
import fr.streamia.tv.data.PrayerMethod
import fr.streamia.tv.data.UpdateCheckResult
import fr.streamia.tv.data.VideoAspectSetting
import fr.streamia.tv.data.VodSortOrder
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.RadiusPill
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: AppSettings,
    playlistName: String?,
    accountExpiresAtEpochSeconds: Long?,
    /** Ville effectivement utilisée par l'accueil (détectée si aucune n'est réglée). */
    detectedPlaceName: String?,
    busy: Boolean,
    liveHistoryCount: Int,
    movieHistoryCount: Int,
    seriesHistoryCount: Int,
    currentVersion: String,
    updateChecking: Boolean,
    updateCheck: UpdateCheckResult?,
    onToggleLivePreview: () -> Unit,
    onCycleLivePreviewDelay: () -> Unit,
    onCycleVodSeekStep: () -> Unit,
    onCycleVideoAspect: () -> Unit,
    onCycleBufferMode: () -> Unit,
    onCycleDisplayModeSwitch: () -> Unit = {},
    onToggleTunneling: () -> Unit = {},
    onCycleLiveStreamFormat: () -> Unit,
    onCycleLiveChannelSortOrder: () -> Unit,
    onCycleVodSortOrder: () -> Unit,
    onCycleEpgTimeOffset: () -> Unit,
    onToggleAutoPlayNextEpisode: () -> Unit,
    onToggleLiveVersionFailover: () -> Unit,
    onCycleSubtitleSizeScale: () -> Unit,
    onToggleSubtitleBackground: () -> Unit,
    onToggleHomeBlock: (HomeBlock) -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onOrganizer: () -> Unit,
    onRefresh: () -> Unit,
    onClearLiveHistory: () -> Unit,
    onClearMovieHistory: () -> Unit,
    onClearSeriesHistory: () -> Unit,
    onClearAllHistory: () -> Unit,
    onChangePlaylist: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onDismissUpdateCheck: () -> Unit,
    onInstallUpdate: () -> Unit = {},
    onAllowUpdateInstall: () -> Unit = {},
    onExportBackup: suspend () -> String,
    onImportBackup: suspend (String) -> String,
    onAbout: () -> Unit,
    onParentalControl: () -> Unit,
    onSearchCities: suspend (String) -> List<HomePlace>,
    onSetHomePlace: (HomePlace?) -> Unit,
    onSetPrayerMethod: (PrayerMethod) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeModal by remember { mutableStateOf<SettingsModalState?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var homeBlocksModalOpen by remember { mutableStateOf(false) }
    var updateDialogOpen by remember { mutableStateOf(false) }
    // La fenêtre s'ouvre d'elle-même quand la mise à jour attend un geste ou change d'étape
    // (retour du réglage d'autorisation, installation lancée, échec), pas à la simple arrivée.
    val updateStateKey = updateCheck?.let { it::class.simpleName }
    var lastUpdateStateKey by remember { mutableStateOf(updateStateKey) }
    LaunchedEffect(updateStateKey) {
        if (updateStateKey == lastUpdateStateKey) return@LaunchedEffect
        lastUpdateStateKey = updateStateKey
        if (updateCheck is UpdateCheckResult.Downloaded || updateCheck is UpdateCheckResult.AwaitingInstallPermission ||
            updateCheck is UpdateCheckResult.Installing || updateCheck is UpdateCheckResult.Error
        ) updateDialogOpen = true
    }
    var citySearchOpen by remember { mutableStateOf(false) }
    val homeBlockRows = remember {
        listOf(
            HomeBlock.Resume to "Reprendre la lecture",
            HomeBlock.Favorites to "Favoris",
            HomeBlock.RecentChannels to "Dernières chaînes regardées",
            HomeBlock.FootballScores to "Scores football",
            HomeBlock.LiveMatches to "Matchs en direct",
            HomeBlock.TvProgrammeNow to "Programme TV FR en direct",
            HomeBlock.TvProgrammeTonight to "Programme TV FR ce soir",
            HomeBlock.BeinSportsNow to "beIN Sports en direct",
            HomeBlock.BeinSportsNext to "beIN Sports suivant",
            HomeBlock.UkGuideNow to "UK en direct",
            HomeBlock.UkGuideNext to "UK suivant",
            HomeBlock.Recommendations to "Recommandations",
        ) + JustWatchSection.entries.map { it.homeBlock to "${it.title} (JustWatch)" }
    }

    fun openChoices(
        title: String,
        description: String,
        values: List<Pair<String, Boolean>>,
        onSelect: (Int) -> Unit,
    ) {
        activeModal = SettingsModalState(
            title = title,
            description = description,
            options = values.mapIndexed { index, value ->
                SettingsModalOption(
                    label = value.first,
                    selected = value.second,
                    onSelect = { onSelect(index) },
                )
            },
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = onExportBackup()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("Impossible d'écrire à cet emplacement.")
            }.onSuccess { backupMessage = "Sauvegarde enregistrée." }
                .onFailure { error -> backupMessage = "Échec de la sauvegarde : " + (error.message ?: "erreur inconnue") + "." }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalStateException("Fichier illisible.")
                onImportBackup(json)
            }.onSuccess { message -> backupMessage = message }
                .onFailure { error -> backupMessage = "Échec de la restauration : " + (error.message ?: "fichier invalide") + "." }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 42.dp, vertical = 28.dp)) {
        GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(RadiusPill)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StreamiaLogo(compact = true)
                Spacer(Modifier.weight(1f))
                Text("Paramètres", color = Ink, fontSize = 27.sp, fontWeight = HeadingWeight)
            }
        }
        Spacer(Modifier.height(14.dp))

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSectionTitle("Liste & accueil")
            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(
                    StreamiaIconGlyph.Swap,
                    playlistName?.takeIf(String::isNotBlank) ?: "Liste active",
                    accountExpiresAtEpochSeconds?.let { "Expire le " + formatExpiry(it) } ?: "Aucune date d'expiration",
                    {
                        openChoices(
                            "Changer de liste",
                            "Vous allez quitter la liste actuelle et revenir au gestionnaire de playlists.",
                            listOf("Annuler" to false, "Continuer" to false),
                        ) { if (it == 1) onChangePlaylist() }
                    },
                    Modifier.focusRequester(firstFocus).weight(1f),
                )
                SettingsTile(
                    StreamiaIconGlyph.Guide,
                    "Ville (météo & prières)",
                    settings.homePlace?.name ?: ("Automatique" + detectedPlaceName?.let { " · $it" }.orEmpty()),
                    {
                        openChoices(
                            "Ville",
                            "Utilisée pour la météo et les heures de prière de l'accueil. " +
                                "La détection automatique se base sur la connexion (peut se tromper avec un VPN).",
                            listOf("Détection automatique" to (settings.homePlace == null), "Rechercher une ville…" to false),
                        ) { if (it == 0) onSetHomePlace(null) else citySearchOpen = true }
                    },
                    Modifier.weight(1f),
                )
                SettingsTile(
                    StreamiaIconGlyph.Settings,
                    "Calcul des prières",
                    prayerMethodLabel(settings.prayerMethod),
                    {
                        val values = PrayerMethod.entries.toList()
                        openChoices(
                            "Calcul des prières",
                            "Choisissez la méthode suivie par votre mosquée ou votre pays.",
                            values.map { prayerMethodLabel(it) to (it == settings.prayerMethod) },
                        ) { onSetPrayerMethod(values[it]) }
                    },
                    Modifier.weight(1f),
                )
                Spacer(Modifier.weight(1f))
            }

            SettingsSectionTitle("Lecture & direct")
            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(
                    glyph = StreamiaIconGlyph.Live,
                    title = "Aperçu TV en direct",
                    subtitle = if (settings.livePreviewEnabled) "Activé" else "Désactivé",
                    onClick = {
                        openChoices(
                            "Aperçu TV en direct",
                            "Choisissez si un aperçu doit démarrer lorsque vous parcourez les chaînes.",
                            listOf(
                                "Activé" to settings.livePreviewEnabled,
                                "Désactivé" to !settings.livePreviewEnabled,
                            ),
                        ) { index ->
                            val targetEnabled = index == 0
                            if (targetEnabled != settings.livePreviewEnabled) onToggleLivePreview()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    selected = settings.livePreviewEnabled,
                )
                SettingsTile(
                    glyph = StreamiaIconGlyph.Refresh,
                    title = "Délai de l'aperçu",
                    subtitle = previewDelayLabel(settings.livePreviewDelayMs),
                    onClick = {
                        val values = AppSettings.LIVE_PREVIEW_DELAYS_MS
                        openChoices(
                            "Délai de l'aperçu",
                            "Réduit les lancements de flux pendant une navigation rapide.",
                            values.map { previewDelayLabel(it) to (it == settings.livePreviewDelayMs) },
                        ) { target -> cycleTo(values, settings.livePreviewDelayMs, target, onCycleLivePreviewDelay) }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = settings.livePreviewEnabled,
                )
                SettingsTile(
                    glyph = StreamiaIconGlyph.ArrowForward,
                    title = "Avance / retour VOD",
                    subtitle = settings.vodSeekStepSeconds.toString() + " secondes",
                    onClick = {
                        val values = AppSettings.VOD_SEEK_STEPS_SECONDS
                        openChoices(
                            "Avance / retour VOD",
                            "Choisissez le pas utilisé par les actions d'avance et de retour.",
                            values.map { (it.toString() + " secondes") to (it == settings.vodSeekStepSeconds) },
                        ) { target -> cycleTo(values, settings.vodSeekStepSeconds, target, onCycleVodSeekStep) }
                    },
                    modifier = Modifier.weight(1f),
                )
                SettingsTile(
                    glyph = StreamiaIconGlyph.Live,
                    title = "Format vidéo",
                    subtitle = videoAspectLabel(settings.videoAspect),
                    onClick = {
                        val values = VideoAspectSetting.entries.toList()
                        openChoices(
                            "Format vidéo",
                            "Détermine comment l'image remplit l'écran.",
                            values.map { videoAspectLabel(it) to (it == settings.videoAspect) },
                        ) { target -> cycleTo(values, settings.videoAspect, target, onCycleVideoAspect) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(
                    glyph = StreamiaIconGlyph.Live,
                    title = "Format du flux Live",
                    subtitle = liveStreamFormatLabel(settings.liveStreamFormat),
                    onClick = {
                        val values = LiveStreamFormat.entries.toList()
                        openChoices(
                            "Format du flux Live",
                            "Automatique est recommandé ; forcez un format seulement si nécessaire.",
                            values.map { liveStreamFormatLabel(it) to (it == settings.liveStreamFormat) },
                        ) { target -> cycleTo(values, settings.liveStreamFormat, target, onCycleLiveStreamFormat) }
                    },
                    modifier = Modifier.weight(1f),
                )
                SettingsTile(
                    glyph = StreamiaIconGlyph.Refresh,
                    title = "Stabilité du flux",
                    subtitle = bufferModeLabel(settings.bufferMode),
                    onClick = {
                        val values = BufferMode.entries.toList()
                        openChoices(
                            "Stabilité du flux",
                            "Choisissez le compromis entre réactivité et stabilité de lecture.",
                            values.map { bufferModeLabel(it) to (it == settings.bufferMode) },
                        ) { target -> cycleTo(values, settings.bufferMode, target, onCycleBufferMode) }
                    },
                    modifier = Modifier.weight(1f),
                )
                SettingsTile(
                    glyph = StreamiaIconGlyph.Live,
                    title = "Adapter l'affichage",
                    subtitle = displayModeSwitchLabel(settings.displayModeSwitch),
                    onClick = {
                        val values = DisplayModeSwitch.entries.toList()
                        openChoices(
                            "Adapter l'affichage",
                            "Bascule l'écran sur la résolution (4K) et la fréquence (24/25/50 Hz) de la vidéo. " +
                                "Chaque bascule noircit l'écran 1 à 3 s : déconseillé en direct si vous zappez souvent.",
                            values.map { displayModeSwitchLabel(it) to (it == settings.displayModeSwitch) },
                        ) { target -> cycleTo(values, settings.displayModeSwitch, target, onCycleDisplayModeSwitch) }
                    },
                    modifier = Modifier.weight(1f),
                )
                SettingsTile(
                    glyph = StreamiaIconGlyph.Settings,
                    title = "Mode tunnel (4K HDR)",
                    subtitle = if (settings.tunnelingEnabled) "Activé" else "Désactivé",
                    onClick = {
                        openChoices(
                            "Mode tunnel",
                            "Le boîtier synchronise lui-même image et son : lecture 4K HDR plus fluide sur les TV compatibles. " +
                                "Désactivez-le en cas d'écran noir ou de son décalé. S'applique à la prochaine lecture.",
                            listOf("Activé" to settings.tunnelingEnabled, "Désactivé" to !settings.tunnelingEnabled),
                        ) { index -> if ((index == 0) != settings.tunnelingEnabled) onToggleTunneling() }
                    },
                    modifier = Modifier.weight(1f),
                    selected = settings.tunnelingEnabled,
                )
            }
            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(
                    glyph = StreamiaIconGlyph.Swap,
                    title = "Secours automatique",
                    subtitle = if (settings.liveVersionFailover) "Activé" else "Désactivé",
                    onClick = {
                        openChoices(
                            "Secours automatique",
                            "En direct, passe sur une autre version de la chaîne (HD, FHD…) si l'image est coupée " +
                                "6 secondes, ou après 3 coupures d'au moins 5 secondes en 5 minutes. " +
                                "Sans effet sur les chaînes qui n'ont qu'une version.",
                            listOf("Activé" to settings.liveVersionFailover, "Désactivé" to !settings.liveVersionFailover),
                        ) { index -> if ((index == 0) != settings.liveVersionFailover) onToggleLiveVersionFailover() }
                    },
                    modifier = Modifier.weight(1f),
                    selected = settings.liveVersionFailover,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }

            SettingsSectionTitle("Catalogue & affichage")
            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(
                    glyph = StreamiaIconGlyph.Reorder,
                    title = "Tri des chaînes",
                    subtitle = liveSortLabel(settings.liveChannelSortOrder),
                    onClick = {
                        val values = LiveChannelSortOrder.entries.toList()
                        openChoices(
                            "Tri des chaînes en direct",
                            "Choisissez l'ordre affiché dans le navigateur Live.",
                            values.map { liveSortLabel(it) to (it == settings.liveChannelSortOrder) },
                        ) { target -> cycleTo(values, settings.liveChannelSortOrder, target, onCycleLiveChannelSortOrder) }
                    },
                    modifier = Modifier.weight(1f),
                )
                SettingsTile(
                    glyph = StreamiaIconGlyph.Reorder,
                    title = "Tri Films / Séries",
                    subtitle = vodSortLabel(settings.vodSortOrder),
                    onClick = {
                        val values = VodSortOrder.entries.toList()
                        openChoices(
                            "Tri Films / Séries",
                            "Choisissez l'ordre des contenus VOD.",
                            values.map { vodSortLabel(it) to (it == settings.vodSortOrder) },
                        ) { target -> cycleTo(values, settings.vodSortOrder, target, onCycleVodSortOrder) }
                    },
                    modifier = Modifier.weight(1f),
                )
                SettingsTile(
                    glyph = StreamiaIconGlyph.Guide,
                    title = "Décalage horaire EPG",
                    subtitle = epgOffsetLabel(settings.epgTimeOffsetHours),
                    onClick = {
                        val values = AppSettings.EPG_TIME_OFFSETS_HOURS
                        openChoices(
                            "Décalage horaire EPG",
                            "Ajustez uniquement si votre guide TV est décalé par rapport aux programmes.",
                            values.map { epgOffsetLabel(it) to (it == settings.epgTimeOffsetHours) },
                        ) { target -> cycleTo(values, settings.epgTimeOffsetHours, target, onCycleEpgTimeOffset) }
                    },
                    modifier = Modifier.weight(1f),
                )
                SettingsTile(
                    glyph = StreamiaIconGlyph.Series,
                    title = "Épisode suivant auto.",
                    subtitle = if (settings.autoPlayNextEpisode) "Activé" else "Désactivé",
                    onClick = {
                        openChoices(
                            "Épisode suivant automatique",
                            "Lance automatiquement l'épisode suivant lorsqu'il est disponible.",
                            listOf(
                                "Activé" to settings.autoPlayNextEpisode,
                                "Désactivé" to !settings.autoPlayNextEpisode,
                            ),
                        ) { index ->
                            val targetEnabled = index == 0
                            if (targetEnabled != settings.autoPlayNextEpisode) onToggleAutoPlayNextEpisode()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    selected = settings.autoPlayNextEpisode,
                )
            }

            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(
                    glyph = StreamiaIconGlyph.Reorder,
                    title = "Taille des sous-titres",
                    subtitle = subtitleScaleLabel(settings.subtitleSizeScale),
                    onClick = {
                        val values = AppSettings.SUBTITLE_SIZE_SCALES
                        openChoices(
                            "Taille des sous-titres",
                            "Choisissez une taille confortable à votre distance de visionnage.",
                            values.map { subtitleScaleLabel(it) to (it == settings.subtitleSizeScale) },
                        ) { target -> cycleTo(values, settings.subtitleSizeScale, target, onCycleSubtitleSizeScale) }
                    },
                    modifier = Modifier.weight(1f),
                )
                SettingsTile(
                    glyph = StreamiaIconGlyph.Settings,
                    title = "Fond des sous-titres",
                    subtitle = if (settings.subtitleBackgroundEnabled) "Activé" else "Désactivé",
                    onClick = {
                        openChoices(
                            "Fond des sous-titres",
                            "Le fond améliore la lisibilité sur les scènes claires.",
                            listOf(
                                "Activé" to settings.subtitleBackgroundEnabled,
                                "Désactivé" to !settings.subtitleBackgroundEnabled,
                            ),
                        ) { index ->
                            val targetEnabled = index == 0
                            if (targetEnabled != settings.subtitleBackgroundEnabled) onToggleSubtitleBackground()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    selected = settings.subtitleBackgroundEnabled,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }

            SettingsSectionTitle("Blocs de l'accueil")
            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val enabledBlocks = homeBlockRows.count { (block, _) -> block !in settings.disabledHomeBlocks }
                SettingsTile(
                    glyph = StreamiaIconGlyph.CheckboxOn,
                    title = "Blocs de l'accueil",
                    subtitle = if (enabledBlocks == homeBlockRows.size) {
                        "Tous activés"
                    } else {
                        "$enabledBlocks / ${homeBlockRows.size} activés"
                    },
                    onClick = { homeBlocksModalOpen = true },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }

            SettingsSectionTitle("Outils & gestion")
            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(StreamiaIconGlyph.Search, "Recherche", "Chaînes, films et séries", onSearch, Modifier.weight(1f))
                SettingsTile(StreamiaIconGlyph.Guide, "Guide TV", "EPG et grille des chaînes", onEpg, Modifier.weight(1f))
                SettingsTile(StreamiaIconGlyph.Reorder, "Organiser", "Catégories et contenus", onOrganizer, Modifier.weight(1f))
                SettingsTile(
                    StreamiaIconGlyph.Refresh,
                    if (busy) "Actualisation…" else "Actualiser",
                    "Recharge la liste et le catalogue",
                    onRefresh,
                    Modifier.weight(1f),
                    enabled = !busy,
                )
            }
            // « Changer de liste » : une seule tuile, celle de la liste active (section « Liste & accueil »).
            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(
                    StreamiaIconGlyph.Lock,
                    "Contrôle parental",
                    if (settings.parentalControlEnabled) "Activé" else "Désactivé",
                    onParentalControl,
                    Modifier.weight(1f),
                    selected = settings.parentalControlEnabled,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }

            SettingsSectionTitle("Données & application")
            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val allHistoryCount = liveHistoryCount + movieHistoryCount + seriesHistoryCount
                SettingsTile(
                    StreamiaIconGlyph.Delete,
                    "Historique Direct",
                    liveHistoryCount.toString() + " élément(s)",
                    {
                        openChoices(
                            "Effacer l'historique Direct",
                            "Cette action retire uniquement l'historique des chaînes en direct.",
                            listOf("Annuler" to false, "Effacer" to false),
                        ) { if (it == 1) onClearLiveHistory() }
                    },
                    Modifier.weight(1f),
                    enabled = liveHistoryCount > 0,
                )
                SettingsTile(
                    StreamiaIconGlyph.Delete,
                    "Historique Films",
                    movieHistoryCount.toString() + " élément(s)",
                    {
                        openChoices(
                            "Effacer l'historique Films",
                            "Cette action retire uniquement l'historique des films.",
                            listOf("Annuler" to false, "Effacer" to false),
                        ) { if (it == 1) onClearMovieHistory() }
                    },
                    Modifier.weight(1f),
                    enabled = movieHistoryCount > 0,
                )
                SettingsTile(
                    StreamiaIconGlyph.Delete,
                    "Historique Séries",
                    seriesHistoryCount.toString() + " élément(s)",
                    {
                        openChoices(
                            "Effacer l'historique Séries",
                            "Cette action retire uniquement l'historique des séries.",
                            listOf("Annuler" to false, "Effacer" to false),
                        ) { if (it == 1) onClearSeriesHistory() }
                    },
                    Modifier.weight(1f),
                    enabled = seriesHistoryCount > 0,
                )
                SettingsTile(
                    StreamiaIconGlyph.Delete,
                    "Effacer tout l'historique",
                    allHistoryCount.toString() + " élément(s)",
                    {
                        openChoices(
                            "Effacer tout l'historique",
                            "Tous les historiques Direct, Films et Séries seront supprimés.",
                            listOf("Annuler" to false, "Tout effacer" to false),
                        ) { if (it == 1) onClearAllHistory() }
                    },
                    Modifier.weight(1f),
                    enabled = allHistoryCount > 0,
                )
            }

            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTile(
                    StreamiaIconGlyph.Refresh,
                    "Mises à jour",
                    updateSubtitle(currentVersion, updateChecking, updateCheck),
                    {
                        updateDialogOpen = true
                        // Rien en cours ni en attente : nouvelle vérification.
                        if (!updateChecking && (updateCheck == null || updateCheck is UpdateCheckResult.UpToDate ||
                                updateCheck is UpdateCheckResult.NoTaggedRelease || updateCheck is UpdateCheckResult.Error && updateCheck.release == null)
                        ) onCheckForUpdate()
                    },
                    Modifier.weight(1f),
                    selected = updateChecking || updateCheck is UpdateCheckResult.Downloaded ||
                        updateCheck is UpdateCheckResult.AwaitingInstallPermission,
                )
                SettingsTile(
                    StreamiaIconGlyph.Settings,
                    "À propos",
                    "Version, appareil et cache",
                    onAbout,
                    Modifier.weight(1f),
                )
                SettingsTile(
                    StreamiaIconGlyph.Swap,
                    "Sauvegarder les réglages",
                    "Exporter les préférences",
                    { exportLauncher.launch(backupFileName()) },
                    Modifier.weight(1f),
                )
                SettingsTile(
                    StreamiaIconGlyph.Swap,
                    "Restaurer les réglages",
                    "Importer une sauvegarde",
                    { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    Modifier.weight(1f),
                )
            }
        }

        backupMessage?.let { message ->
            Spacer(Modifier.height(10.dp))
            FocusableSurface(onClick = { backupMessage = null }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(message + "  ·  OK pour fermer", color = Ink, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }
    }

    if (updateDialogOpen) {
        val content = updateDialogContent(updateChecking, updateCheck, currentVersion)
        UpdateDialog(
            content = content,
            currentVersion = currentVersion,
            onAction = { action ->
                when (action) {
                    UpdateDialogAction.Install, UpdateDialogAction.RetryInstall -> onInstallUpdate()
                    UpdateDialogAction.OpenPermission -> onAllowUpdateInstall()
                    UpdateDialogAction.RetryCheck -> onCheckForUpdate()
                    UpdateDialogAction.Ok -> {
                        updateDialogOpen = false
                        onDismissUpdateCheck()
                    }
                }
            },
            onClose = {
                updateDialogOpen = false
                if (content.closeClearsState) onDismissUpdateCheck()
            },
        )
    }

    activeModal?.let { modal ->
        SettingsChoiceModal(
            state = modal,
            onDismiss = { activeModal = null },
            onOption = { option ->
                option.onSelect()
                activeModal = null
            },
        )
    }

    if (citySearchOpen) {
        CitySearchModal(
            onSearch = onSearchCities,
            onPick = { place ->
                onSetHomePlace(place)
                citySearchOpen = false
            },
            onDismiss = { citySearchOpen = false },
        )
    }

    if (homeBlocksModalOpen) {
        HomeBlocksModal(
            blocks = homeBlockRows,
            disabledBlocks = settings.disabledHomeBlocks,
            onToggle = onToggleHomeBlock,
            onDismiss = { homeBlocksModalOpen = false },
        )
    }
}

package fr.streamia.tv.ui.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.LiveVersionHealth
import fr.streamia.tv.domain.LiveVersionOption
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.ui.AI_SUBTITLE_STATUS_PREFIX
import fr.streamia.tv.ui.AccentPill
import fr.streamia.tv.ui.AiSparkle
import fr.streamia.tv.ui.ButtonSpinner
import fr.streamia.tv.ui.ChannelLogo
import fr.streamia.tv.ui.LocalSystemDensity
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.TrackChoice
import fr.streamia.tv.ui.TvTextField
import fr.streamia.tv.ui.VideoAspect
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.Danger
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.WarmSignal

/**
 * Le lecteur vit dans la surface TV réduite (ResponsiveTvViewport) : une feuille du bas y serait minuscule.
 * Elle reprend la densité réelle de l'appareil, comme les écrans mobiles.
 */
@Composable
fun NativeDensitySheet(content: @Composable () -> Unit) {
    val system = LocalSystemDensity.current ?: LocalDensity.current
    CompositionLocalProvider(LocalDensity provides system, content = content)
}

/** Liste à choix unique en feuille du bas (pistes audio, sous-titres trouvés…). */
@Composable
fun MobileChoiceSheet(title: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    NativeDensitySheet {
        MobileBottomSheet(title, onDismiss = onDismiss) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { index, label ->
                    val on = index == selectedIndex
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.RadioButton) { onSelect(index) }.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label, color = if (on) AccentPinkText else Ink, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, modifier = Modifier.weight(1f),
                        )
                        if (on) Text("✓", color = AccentPinkText, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

private enum class SettingsPage { Main, Audio, Subtitle }

/** Réglages de lecture au doigt : audio, sous-titres (pistes, IA, fichier, URL, décalage), format vidéo. */
@Composable
internal fun MobilePlayerSettingsSheet(
    audioTracks: List<TrackChoice>, audioIndex: Int,
    subtitleTracks: List<TrackChoice>, subtitleIndex: Int,
    aspect: VideoAspect, dolbyVisionLabel: String?, dolbyAtmosLabel: String?,
    onAudioSelected: (Int) -> Unit, onSubtitleSelected: (Int) -> Unit,
    onNextAspect: () -> Unit, onClose: () -> Unit,
    externalSubtitleAvailable: Boolean,
    externalSubtitleLabel: String?,
    externalSubtitleError: String?,
    onPickExternalSubtitleFile: (() -> Unit)?,
    onLoadExternalSubtitleUrl: ((String) -> Unit)?,
    onSearchOnlineSubtitles: (() -> Unit)?,
    onlineSubtitleBusy: Boolean,
    onlineSubtitleStatus: String?,
    onlineSubtitleResultCount: Int,
    onShowOnlineSubtitleResults: (() -> Unit)?,
    subtitleOffsetMs: Long?,
    onShiftSubtitle: ((Long) -> Unit)?,
    onResetSubtitleShift: (() -> Unit)?,
    aiTranslateLanguage: String?,
) {
    var page by remember { mutableStateOf(SettingsPage.Main) }
    when (page) {
        SettingsPage.Audio -> MobileChoiceSheet("Piste audio", audioTracks.map(TrackChoice::label), audioIndex, { onAudioSelected(it); page = SettingsPage.Main }, { page = SettingsPage.Main })
        SettingsPage.Subtitle -> MobileChoiceSheet("Sous-titres", subtitleTracks.map(TrackChoice::label), subtitleIndex, { onSubtitleSelected(it); page = SettingsPage.Main }, { page = SettingsPage.Main })
        SettingsPage.Main -> NativeDensitySheet {
            var urlOpen by remember { mutableStateOf(false) }
            var url by remember { mutableStateOf("") }
            var pendingUrl by remember { mutableStateOf(false) }
            // L'URL n'est vidée qu'une fois le chargement réussi : un échec laisse la saisie intacte.
            LaunchedEffect(externalSubtitleLabel, externalSubtitleError) {
                if (!pendingUrl) return@LaunchedEffect
                pendingUrl = false
                if (externalSubtitleError == null) { urlOpen = false; url = "" }
            }
            val canExternal = externalSubtitleAvailable && onPickExternalSubtitleFile != null && onLoadExternalSubtitleUrl != null
            val ai = aiTranslateLanguage != null && onSearchOnlineSubtitles != null && canExternal
            MobileBottomSheet("Lecture", onDismiss = onClose) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (ai) {
                        SheetButton(
                            if (onlineSubtitleBusy) onlineSubtitleStatus ?: "Recherche du sous-titre…" else "Traduire par IA en $aiTranslateLanguage",
                            busy = onlineSubtitleBusy, ai = onlineSubtitleBusy && onlineSubtitleStatus?.startsWith(AI_SUBTITLE_STATUS_PREFIX) == true || !onlineSubtitleBusy,
                        ) { if (!onlineSubtitleBusy) onSearchOnlineSubtitles?.invoke() }
                    }
                    if (externalSubtitleError != null) Text(externalSubtitleError, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                    SheetRow("Piste audio", audioTracks.getOrNull(audioIndex)?.label ?: "Auto") { page = SettingsPage.Audio }
                    SheetRow("Sous-titres", subtitleTracks.getOrNull(subtitleIndex)?.label ?: "Désactivés") { page = SettingsPage.Subtitle }
                    if (subtitleOffsetMs != null && onShiftSubtitle != null && onResetSubtitleShift != null) {
                        OffsetRow(subtitleOffsetMs, onShiftSubtitle, onResetSubtitleShift)
                    }
                    SheetRow("Format vidéo", aspect.label, onNextAspect)
                    val dolby = listOfNotNull(dolbyVisionLabel, dolbyAtmosLabel).joinToString(" · ")
                    Text(
                        if (dolby.isBlank()) "Dolby : aucun format Dolby sélectionné" else dolby,
                        color = if (dolby.isBlank()) MutedInk else AccentPinkText, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                    if (canExternal) {
                        Text("SOUS-TITRE EXTERNE", color = AccentPinkText, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.1.sp, modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp))
                        Text(externalSubtitleLabel ?: "Aucun sous-titre externe chargé", color = if (externalSubtitleLabel != null) Ink else MutedInk, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                        if (!ai && onSearchOnlineSubtitles != null) {
                            SheetButton(
                                if (onlineSubtitleBusy) onlineSubtitleStatus ?: "Recherche en cours…" else "Rechercher automatiquement",
                                busy = onlineSubtitleBusy, ai = onlineSubtitleStatus?.startsWith(AI_SUBTITLE_STATUS_PREFIX) == true,
                            ) { if (!onlineSubtitleBusy) onSearchOnlineSubtitles() }
                        }
                        if (onlineSubtitleResultCount > 1 && onShowOnlineSubtitleResults != null) {
                            SheetRow("Autres résultats", "$onlineSubtitleResultCount", onShowOnlineSubtitleResults)
                        }
                        SheetRow("Charger un fichier .srt / .vtt", null) { onPickExternalSubtitleFile?.invoke() }
                        SheetRow("Charger depuis une URL", null) { urlOpen = !urlOpen }
                        if (urlOpen) {
                            TvTextField(url, { url = it }, "URL du sous-titre (.srt ou .vtt)", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                            SheetButton("Valider", enabled = url.isNotBlank()) {
                                pendingUrl = true
                                onLoadExternalSubtitleUrl?.invoke(url.trim())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(title: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = AccentPinkText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun SheetButton(label: String, busy: Boolean = false, ai: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    AccentPill(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (busy && !ai) ButtonSpinner(size = 20.dp) else if (ai) AiSparkle(size = 20.dp)
            if (busy || ai) androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            Text(label, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** Décalage du sous-titre : −5 s, −0,5 s, valeur (touche = remise à zéro), +0,5 s, +5 s. */
@Composable
private fun OffsetRow(offsetMs: Long, onShift: (Long) -> Unit, onReset: () -> Unit) {
    val value = if (offsetMs == 0L) "Synchro" else String.format(java.util.Locale.ROOT, "%+.1f s", offsetMs / 1000.0)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Décalage du sous-titre (+ = plus tard)", color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("−5" to -5_000L, "−0,5" to -500L).forEach { (l, d) -> OffsetButton(l, Modifier.weight(1f)) { onShift(d) } }
            OffsetButton(value, Modifier.weight(1.4f), accent = true, onClick = onReset)
            listOf("+0,5" to 500L, "+5" to 5_000L).forEach { (l, d) -> OffsetButton(l, Modifier.weight(1f)) { onShift(d) } }
        }
    }
}

@Composable
private fun OffsetButton(label: String, modifier: Modifier, accent: Boolean = false, onClick: () -> Unit) {
    MobileCard(modifier.heightIn(min = 52.dp), onClick = onClick) {
        Text(label, color = if (accent) AccentPinkText else Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
    }
}

/** Versions d'une chaîne du Direct en feuille du bas : toucher une version la lance ; la version en cours ferme la feuille. */
@Composable
fun MobileLiveVersionsSheet(
    channelName: String,
    options: List<LiveVersionOption>,
    currentLoading: Boolean,
    currentFailed: Boolean,
    currentProblem: String?,
    scanProgress: String?,
    scanCount: Int,
    onSelect: (MediaEntry) -> Unit,
    onClose: () -> Unit,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onOpenPlaybackSettings: () -> Unit,
) {
    val scanning = scanProgress != null
    NativeDensitySheet {
        MobileBottomSheet("Versions de $channelName", scanProgress ?: "Qualité, fps et son mesurés à l'écran", onDismiss = { if (scanning) onCancelScan() else onClose() }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    val dimmed = option.health.rank >= LiveVersionHealth.Unavailable.rank && !option.current
                    val status = when {
                        option.current && currentFailed -> "✕ Ne répond pas"
                        option.current && currentLoading -> "… Chargement"
                        option.current && currentProblem != null -> "✕ $currentProblem"
                        option.health == LiveVersionHealth.Unavailable -> "${option.health.symbol} ${option.health.label}${option.failureAgo?.let { " · $it" }.orEmpty()}"
                        else -> "${option.health.symbol} ${option.health.label}"
                    }
                    val bad = option.current && (currentFailed || currentProblem != null) || option.health.rank >= LiveVersionHealth.Unavailable.rank
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) {
                            when {
                                scanning -> Unit
                                option.current -> onClose()
                                else -> onSelect(option.entry)
                            }
                        }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ChannelLogo(option.entry.iconUrl, option.entry.displayName, Modifier.size(44.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                option.entry.displayName + if (option.current) "  · EN COURS" else if (option.recommended) "  · RECOMMANDÉE" else "",
                                color = if (option.current) AccentPinkText else if (dimmed) MutedInk else Ink,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(option.qualityText, color = MutedInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(status, color = if (bad) Danger else MutedInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            option.warnings.forEach { Text("⚠ $it", color = WarmSignal, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
                if (scanning) {
                    MobileSheetAction("Annuler le test", StreamiaIconGlyph.Close, tint = MutedInk, onClick = onCancelScan)
                } else {
                    MobileSheetAction("Tester toutes les versions (≈ ${scanCount * 6} s)", StreamiaIconGlyph.Refresh, onClick = onStartScan)
                    MobileSheetAction("Audio, sous-titres, format…", StreamiaIconGlyph.Settings, onClick = onOpenPlaybackSettings)
                }
            }
        }
    }
}

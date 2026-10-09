package fr.streamia.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RadiusCard

@Composable
internal fun BoxScope.PlayerSettings(
    audioTracks: List<TrackChoice>, audioIndex: Int,
    subtitleTracks: List<TrackChoice>, subtitleIndex: Int,
    aspect: VideoAspect, dolbyVisionLabel: String?, dolbyAtmosLabel: String?,
    firstFocus: FocusRequester, onAudioSelected: (Int) -> Unit, onSubtitleSelected: (Int) -> Unit,
    onNextAspect: () -> Unit, onClose: () -> Unit,
    externalSubtitleAvailable: Boolean = false,
    externalSubtitleLabel: String? = null,
    externalSubtitleError: String? = null,
    onPickExternalSubtitleFile: (() -> Unit)? = null,
    onLoadExternalSubtitleUrl: ((String) -> Unit)? = null,
    onSearchOnlineSubtitles: (() -> Unit)? = null,
    onlineSubtitleBusy: Boolean = false,
    onlineSubtitleStatus: String? = null,
    onlineSubtitleResultCount: Int = 0,
    onShowOnlineSubtitleResults: (() -> Unit)? = null,
    /** Décalage manuel du sous-titre chargé (null : pas de réglage possible pour ce sous-titre). */
    subtitleOffsetMs: Long? = null,
    onShiftSubtitle: ((Long) -> Unit)? = null,
    onResetSubtitleShift: (() -> Unit)? = null,
    /** Langue des réglages IA (« Français ») quand l'assistant est actif : bouton « Traduire par IA » en tête. */
    aiTranslateLanguage: String? = null,
) {
    // Audio / sous-titres : choix dans une fenêtre à cases à cocher (sélection unique), au lieu
    // d'une liste déroulante dans le panneau. Le focus revient sur la ligne à la fermeture.
    var picker by remember { mutableStateOf<TrackPicker?>(null) }
    val subtitleFocus = remember { FocusRequester() }
    val ownAudioFocus = remember { FocusRequester() }
    val aiButton = aiTranslateLanguage != null && onSearchOnlineSubtitles != null &&
        externalSubtitleAvailable && onPickExternalSubtitleFile != null && onLoadExternalSubtitleUrl != null
    // Bouton IA présent : c'est lui qui reçoit le focus à l'ouverture du panneau.
    val audioFocus = if (aiButton) ownAudioFocus else firstFocus
    Column(
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(430.dp)
            .padding(vertical = 40.dp, horizontal = 24.dp)
            .clip(RoundedCornerShape(RadiusCard))
            .background(Night.copy(alpha = 0.82f))
            .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(RadiusCard))
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Lecture", color = Ink, fontSize = 24.sp, fontWeight = HeadingWeight)
            Spacer(Modifier.weight(1f))
            FocusableSurface(onClick = onClose, modifier = Modifier.width(100.dp).height(48.dp)) {
                Text("Fermer", color = Ink, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (aiButton && onSearchOnlineSubtitles != null) {
            AiTranslateButton(
                language = aiTranslateLanguage.orEmpty(),
                searching = onlineSubtitleBusy,
                status = onlineSubtitleStatus,
                onClick = onSearchOnlineSubtitles,
                modifier = Modifier.focusRequester(firstFocus),
            )
            if (onlineSubtitleResultCount > 1 && onShowOnlineSubtitleResults != null) {
                FocusableSurface(onClick = onShowOnlineSubtitleResults, modifier = Modifier.fillMaxWidth().height(58.dp)) {
                    Text("Autres résultats ($onlineSubtitleResultCount)", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
        TrackRow("Piste audio", audioTracks.getOrNull(audioIndex)?.label ?: "Auto", { picker = TrackPicker.Audio }, Modifier.focusRequester(audioFocus))
        TrackRow("Sous-titres", subtitleTracks.getOrNull(subtitleIndex)?.label ?: "Désactivés", { picker = TrackPicker.Subtitle }, Modifier.focusRequester(subtitleFocus))
        if (subtitleOffsetMs != null && onShiftSubtitle != null && onResetSubtitleShift != null) {
            SubtitleOffsetRow(subtitleOffsetMs, onShiftSubtitle, onResetSubtitleShift)
        }
        SettingButton("Format vidéo", aspect.label, onNextAspect)
        val dolbyText = listOfNotNull(dolbyVisionLabel, dolbyAtmosLabel).joinToString(" · ")
        Text(if (dolbyText.isBlank()) "Dolby : aucun format Dolby sélectionné" else dolbyText,
            color = if (dolbyText.isBlank()) MutedInk else FocusBlueBright, fontSize = 13.sp,
            fontWeight = if (dolbyText.isBlank()) FontWeight.Normal else FontWeight.Bold, lineHeight = 18.sp)
        if (externalSubtitleAvailable && onPickExternalSubtitleFile != null && onLoadExternalSubtitleUrl != null) {
            Spacer(Modifier.height(4.dp))
            ExternalSubtitleSection(
                currentLabel = externalSubtitleLabel,
                errorMessage = externalSubtitleError,
                onPickFile = onPickExternalSubtitleFile,
                onLoadUrl = onLoadExternalSubtitleUrl,
                // Recherche déjà proposée en tête par le bouton « Traduire par IA ».
                onSearchOnline = onSearchOnlineSubtitles.takeUnless { aiButton },
                searching = onlineSubtitleBusy,
                status = onlineSubtitleStatus,
                resultCount = onlineSubtitleResultCount,
                onShowResults = onShowOnlineSubtitleResults,
                aiAbove = aiButton,
            )
        }
        Text("OK sur une ligne pour choisir la langue.", color = MutedInk, fontSize = 13.sp, lineHeight = 19.sp)
    }
    picker?.let { current ->
        val audio = current == TrackPicker.Audio
        val rowFocus = if (audio) audioFocus else subtitleFocus
        fun close() {
            picker = null
            runCatching { rowFocus.requestFocus() }
        }
        ChoiceDialog(
            title = if (audio) "Piste audio" else "Sous-titres",
            options = (if (audio) audioTracks else subtitleTracks).map(TrackChoice::label),
            selectedIndex = if (audio) audioIndex else subtitleIndex,
            onSelect = { index ->
                if (audio) onAudioSelected(index) else onSubtitleSelected(index)
                close()
            },
            onDismiss = ::close,
        )
    }
}

private enum class TrackPicker { Audio, Subtitle }

/** Avance (+) ou retarde (−) le sous-titre : −5 s, −0,5 s, valeur (OK = remise à zéro), +0,5 s, +5 s. */
@Composable
private fun SubtitleOffsetRow(offsetMs: Long, onShift: (Long) -> Unit, onReset: () -> Unit) {
    val value = if (offsetMs == 0L) "Synchro" else String.format(java.util.Locale.ROOT, "%+.1f s", offsetMs / 1000.0)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Décalage du sous-titre (+ = plus tard)", color = MutedInk, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("−5" to -5_000L, "−0,5" to -500L).forEach { (label, delta) ->
                FocusableSurface(onClick = { onShift(delta) }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text(label, color = Ink, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
            FocusableSurface(onClick = onReset, modifier = Modifier.weight(1.4f).height(48.dp)) {
                Text(value, color = FocusBlueBright, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp))
            }
            listOf("+0,5" to 500L, "+5" to 5_000L).forEach { (label, delta) ->
                FocusableSurface(onClick = { onShift(delta) }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text(label, color = Ink, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

/** Premier bouton du panneau quand l'assistant IA est actif : cherche un sous-titre puis le traduit si besoin. */
@Composable
private fun AiTranslateButton(language: String, searching: Boolean, status: String?, onClick: () -> Unit, modifier: Modifier) {
    FocusableSurface(onClick = { if (!searching) onClick() }, accent = true, modifier = modifier.fillMaxWidth().height(76.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AiSparkle(size = 22.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    if (searching) status ?: "Recherche du sous-titre…" else "Traduire par IA en $language",
                    color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Cherche un sous-titre et le traduit si besoin",
                    color = Ink.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ExternalSubtitleSection(
    currentLabel: String?,
    errorMessage: String?,
    onPickFile: () -> Unit,
    onLoadUrl: (String) -> Unit,
    onSearchOnline: (() -> Unit)? = null,
    searching: Boolean = false,
    status: String? = null,
    resultCount: Int = 0,
    onShowResults: (() -> Unit)? = null,
    /** Bouton « Traduire par IA » affiché plus haut : il garde seul la mise en avant. */
    aiAbove: Boolean = false,
) {
    var urlFieldOpen by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var pendingSubmission by remember { mutableStateOf(false) }

    // loadExternalSubtitle() n'a pas de retour direct : on ne referme/vide le champ qu'une fois le
    // résultat observé (currentLabel/errorMessage), pour qu'une URL invalide ou un échec de
    // chargement laisse le champ ouvert avec la saisie intacte plutôt que de forcer une resaisie.
    LaunchedEffect(currentLabel, errorMessage) {
        if (!pendingSubmission) return@LaunchedEffect
        pendingSubmission = false
        if (errorMessage == null) {
            urlFieldOpen = false
            urlInput = ""
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sous-titre externe", color = MutedInk, fontSize = 12.sp)
        Text(
            currentLabel ?: "Aucun sous-titre externe chargé",
            color = if (currentLabel != null) FocusBlueBright else MutedInk,
            fontSize = 14.sp,
            fontWeight = if (currentLabel != null) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onSearchOnline != null) {
            FocusableSurface(onClick = { if (!searching) onSearchOnline() }, accent = true, modifier = Modifier.fillMaxWidth().height(58.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Traduction des sous-titres par l'IA en cours : icône animée devant « Traduction IA 2/5… ».
                    if (searching && status?.startsWith(AI_SUBTITLE_STATUS_PREFIX) == true) {
                        AiSparkle()
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (searching) status ?: "Recherche en cours…" else "Rechercher automatiquement", color = Ink, fontSize = 14.sp)
                }
            }
            if (resultCount > 1 && onShowResults != null) {
                FocusableSurface(onClick = onShowResults, modifier = Modifier.fillMaxWidth().height(58.dp)) {
                    Text("Autres résultats ($resultCount)", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
        FocusableSurface(onClick = onPickFile, accent = onSearchOnline == null && !aiAbove, modifier = Modifier.fillMaxWidth().height(58.dp)) {
            Text("Charger un fichier .srt / .vtt", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        FocusableSurface(onClick = { urlFieldOpen = !urlFieldOpen }, modifier = Modifier.fillMaxWidth().height(58.dp)) {
            Text("Charger depuis une URL", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (urlFieldOpen) {
            TvTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = "URL du sous-titre (.srt ou .vtt)",
                modifier = Modifier.fillMaxWidth(),
            )
            FocusableSurface(
                onClick = {
                    val url = urlInput.trim()
                    if (url.isNotEmpty()) {
                        pendingSubmission = true
                        onLoadUrl(url)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("Valider", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

/** Ligne du panneau : titre, valeur en cours, chevron — OK ouvre la fenêtre de choix. */
@Composable
private fun TrackRow(title: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(onClick = onClick, modifier = modifier.fillMaxWidth().height(74.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = MutedInk, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(value, color = FocusBlueBright, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StreamiaIcon(StreamiaIconGlyph.ChevronDown, size = 16.dp)
        }
    }
}

@Composable
private fun SettingButton(title: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(onClick = onClick, modifier = modifier.fillMaxWidth().height(74.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = MutedInk, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = FocusBlueBright, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

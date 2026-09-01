package fr.streamia.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night

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
) {
    Column(
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(430.dp)
            .background(Night.copy(alpha = 0.98f))
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Lecture", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            FocusableSurface(onClick = onClose, modifier = Modifier.width(100.dp).height(48.dp)) {
                Text("Fermer", color = Ink, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        TrackDropdown("Piste audio", audioTracks, audioIndex, onAudioSelected, Modifier.focusRequester(firstFocus))
        TrackDropdown("Sous-titres", subtitleTracks, subtitleIndex, onSubtitleSelected)
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
            )
        }
        Text("OK ouvre la liste des langues. Haut/Bas sélectionne une option.", color = MutedInk, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun ExternalSubtitleSection(
    currentLabel: String?,
    errorMessage: String?,
    onPickFile: () -> Unit,
    onLoadUrl: (String) -> Unit,
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
        FocusableSurface(onClick = onPickFile, modifier = Modifier.fillMaxWidth().height(58.dp)) {
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

@Composable
private fun TrackDropdown(
    title: String,
    choices: List<TrackChoice>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        FocusableSurface(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().height(74.dp)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(title, color = MutedInk, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        choices.getOrNull(selectedIndex)?.label ?: "Auto",
                        color = FocusBlueBright,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    StreamiaIcon(if (expanded) StreamiaIconGlyph.ChevronUp else StreamiaIconGlyph.ChevronDown, size = 16.dp)
                }
            }
        }
        if (expanded) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 210.dp).background(Night)) {
                itemsIndexed(choices) { index, choice ->
                    FocusableSurface(
                        onClick = { onSelected(index); expanded = false },
                        selected = index == selectedIndex,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text(
                            choice.label,
                            color = if (index == selectedIndex) FocusBlueBright else Ink,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
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

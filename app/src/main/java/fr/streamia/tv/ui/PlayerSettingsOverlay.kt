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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    firstFocus: FocusRequester, onNextAudio: () -> Unit, onNextSubtitle: () -> Unit,
    onNextAspect: () -> Unit, onClose: () -> Unit,
) {
    Column(
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(430.dp)
            .background(Night.copy(alpha = 0.98f)).padding(26.dp),
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
        SettingButton("Piste audio", audioTracks.getOrNull(audioIndex)?.label ?: "Auto", onNextAudio, Modifier.focusRequester(firstFocus))
        SettingButton("Sous-titres", subtitleTracks.getOrNull(subtitleIndex)?.label ?: "Désactivés", onNextSubtitle)
        SettingButton("Format vidéo", aspect.label, onNextAspect)
        val dolbyText = listOfNotNull(dolbyVisionLabel, dolbyAtmosLabel).joinToString(" · ")
        Text(if (dolbyText.isBlank()) "Dolby : aucun format Dolby sélectionné" else dolbyText,
            color = if (dolbyText.isBlank()) MutedInk else FocusBlueBright, fontSize = 13.sp,
            fontWeight = if (dolbyText.isBlank()) FontWeight.Normal else FontWeight.Bold, lineHeight = 18.sp)
        Text("OK fait défiler les options disponibles. Retour ou Fermer revient à la vidéo.", color = MutedInk, fontSize = 13.sp, lineHeight = 19.sp)
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

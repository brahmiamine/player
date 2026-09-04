package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onToggleLivePreview: () -> Unit,
    onCycleLivePreviewDelay: () -> Unit,
    onCycleVodSeekStep: () -> Unit,
    onTools: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val firstFocus = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Night).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StreamiaLogo(compact = true)
            Spacer(Modifier.weight(1f))
            Text("Paramètres", color = Ink, fontSize = 27.sp, fontWeight = HeadingWeight)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Les réglages sont enregistrés automatiquement sur cette TV.",
            color = MutedInk,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsTile(
                    glyph = StreamiaIconGlyph.Live,
                    title = "Aperçu TV en direct",
                    subtitle = if (settings.livePreviewEnabled) "Activé" else "Désactivé",
                    onClick = onToggleLivePreview,
                    modifier = Modifier.focusRequester(firstFocus).weight(1f),
                    selected = settings.livePreviewEnabled,
                )
                SettingsTile(
                    glyph = StreamiaIconGlyph.Refresh,
                    title = "Délai de l'aperçu",
                    subtitle = previewDelayLabel(settings.livePreviewDelayMs),
                    onClick = onCycleLivePreviewDelay,
                    modifier = Modifier.weight(1f),
                    enabled = settings.livePreviewEnabled,
                )
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsTile(
                    glyph = StreamiaIconGlyph.ArrowForward,
                    title = "Avance / retour VOD",
                    subtitle = "${settings.vodSeekStepSeconds} secondes",
                    onClick = onCycleVodSeekStep,
                    modifier = Modifier.weight(1f),
                )
                SettingsTile(
                    glyph = StreamiaIconGlyph.Settings,
                    title = "Outils et gestion",
                    subtitle = "Recherche, EPG, organisation, actualisation et historiques",
                    onClick = onTools,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun previewDelayLabel(delayMs: Int): String = when {
    delayMs <= 0 -> "Immédiat"
    delayMs < 1_000 -> "$delayMs ms"
    delayMs % 1_000 == 0 -> "${delayMs / 1_000} s"
    else -> "${delayMs / 1_000.0} s"
}

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
    FocusableSurface(
        onClick = onClick,
        enabled = enabled,
        selected = selected,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            StreamiaIcon(glyph, size = 34.dp)
            Spacer(Modifier.height(12.dp))
            Text(title, color = Ink, fontSize = 20.sp, fontWeight = HeadingWeight)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = MutedInk, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

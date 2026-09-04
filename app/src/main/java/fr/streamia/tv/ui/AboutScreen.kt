package fr.streamia.tv.ui

import android.os.Build
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night

@Composable
fun AboutScreen(
    versionName: String,
    onLoadCacheSize: suspend () -> Long,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var cacheSizeBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { cacheSizeBytes = runCatching { onLoadCacheSize() }.getOrNull() }

    Column(Modifier.fillMaxSize().background(Night).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FocusableSurface(onClick = onBack, modifier = Modifier.width(120.dp).height(50.dp)) {
                Text("← Retour", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text("À propos", color = Ink, fontSize = 27.sp, fontWeight = HeadingWeight)
        }
        Spacer(Modifier.height(28.dp))

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AboutLine("Streamia TV", versionName)
            AboutLine("Appareil", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            AboutLine("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            AboutLine("Catalogue en cache", cacheSizeBytes?.let(::formatBytes) ?: "Calcul…")
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "L'app est distribuée en APK direct (pas de Play Store) : « Vérifier les mises à jour » dans Outils compare la version installée aux releases GitHub.",
            color = MutedInk,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MutedInk, fontSize = 14.sp, modifier = Modifier.width(220.dp))
        Text(value, color = Ink, fontSize = 15.sp, fontWeight = HeadingWeight)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f Mo".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f Ko".format(bytes / 1_000.0)
    else -> "$bytes o"
}

package fr.streamia.tv.ui.mobile

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.ui.StreamiaIcon
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.StreamiaLogo
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk

/** « À propos » au doigt : version, appareil, caches, mentions. */
@Composable
fun MobileAboutScreen(
    versionName: String,
    onLoadCacheSize: suspend () -> Long,
    onLoadEpgCacheSize: suspend () -> Long,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var cacheSizeBytes by remember { mutableStateOf<Long?>(null) }
    var epgCacheSizeBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        cacheSizeBytes = runCatching { onLoadCacheSize() }.getOrNull()
        epgCacheSizeBytes = runCatching { onLoadEpgCacheSize() }.getOrNull()
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("À propos", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = MobileGutter)) {
            MobileCard(Modifier.fillMaxWidth(), radius = 24.dp) {
                Column(Modifier.padding(16.dp)) {
                    StreamiaLogo(compact = true)
                    Spacer(Modifier.padding(top = 8.dp))
                    InfoLine("Version", versionName)
                    InfoLine("Appareil", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    InfoLine("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    InfoLine("Catalogue en cache", cacheSizeBytes?.let(::formatBytes) ?: "Calcul…")
                    InfoLine("EPG en cache", epgCacheSizeBytes?.let(::formatBytes) ?: "Calcul…")
                }
            }
            Text(
                "L'app est distribuée en APK direct (pas de Play Store) : « Vérifier les mises à jour » dans les réglages avancés compare la version installée aux releases GitHub.",
                color = MutedInk, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 16.dp, start = 8.dp, end = 8.dp),
            )
            // Mention exigée par les conditions d'utilisation de l'API TMDB (recommandations, mots-clés).
            Text(
                "Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB.",
                color = MutedInk, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp, bottom = 24.dp),
            )
        }
    }
}

private sealed interface PinStep {
    data object VerifyCurrent : PinStep
    data object EnterNew : PinStep
    data class ConfirmNew(val pending: String) : PinStep
    data object ConfirmDisable : PinStep
}

/** Contrôle parental au doigt : définir / changer / désactiver le code, saisi au pavé en feuille du bas. */
@Composable
fun MobileParentalControlScreen(
    enabled: Boolean,
    onSetPin: (String) -> Unit,
    onVerifyPin: suspend (String) -> Boolean,
    onDisable: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var step by remember { mutableStateOf<PinStep?>(null) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Contrôle parental", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = MobileGutter)) {
            Text(
                if (enabled) {
                    "Protection activée. Un code est demandé la première fois que vous ouvrez une catégorie " +
                        "verrouillée depuis Organiser, jusqu'à la fermeture complète de l'application."
                } else {
                    "Aucun code défini : aucune catégorie n'est protégée. Définissez un code pour pouvoir " +
                        "verrouiller des catégories depuis Organiser."
                },
                color = MutedInk, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            MobileCard(Modifier.padding(top = 8.dp).fillMaxWidth(), radius = 24.dp) {
                Column(Modifier.fillMaxWidth()) {
                    PageRow(
                        if (enabled) "Changer le code" else "Définir un code", "Code à 4 chiffres, saisi deux fois",
                    ) { step = if (enabled) PinStep.VerifyCurrent else PinStep.EnterNew }
                    if (enabled) PageRow("Désactiver", "Retire le code et déverrouille tout") { step = PinStep.ConfirmDisable }
                }
            }
        }
    }
    val current = step ?: return
    val close = { step = null }
    when (current) {
        PinStep.VerifyCurrent -> PinSheet("Code actuel", "Entrez le code actuel pour pouvoir le changer", close) { pin ->
            if (onVerifyPin(pin)) { step = PinStep.EnterNew; null } else "Code incorrect"
        }
        PinStep.EnterNew -> PinSheet("Nouveau code", "Choisissez un code à 4 chiffres", close) { pin ->
            step = PinStep.ConfirmNew(pin); null
        }
        is PinStep.ConfirmNew -> PinSheet("Confirmez le code", "Ressaisissez le même code", close) { pin ->
            if (pin == current.pending) { onSetPin(pin); step = null; null } else "Les deux codes sont différents"
        }
        PinStep.ConfirmDisable -> PinSheet("Désactiver le contrôle parental", "Entrez le code actuel pour confirmer", close) { pin ->
            if (onVerifyPin(pin)) { onDisable(); step = null; null } else "Code incorrect"
        }
    }
}

@Composable
private fun PinSheet(title: String, subtitle: String, onDismiss: () -> Unit, onSubmit: suspend (String) -> String?) {
    // key : chaque étape repart d'un pavé vide.
    androidx.compose.runtime.key(title) {
        MobileKeypadSheet(
            title = title, subtitle = subtitle, maxDigits = 4, masked = true, autoSubmit = true,
            confirmLabel = "Valider", onSubmit = onSubmit, onDismiss = onDismiss,
        )
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MobileIconButton(StreamiaIconGlyph.ArrowBack, onClick = onBack)
        Text(title, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun PageRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MutedInk, fontSize = 12.sp)
        }
        StreamiaIcon(StreamiaIconGlyph.ArrowForward, tint = MutedInk, size = 16.dp)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MutedInk, fontSize = 13.sp, modifier = Modifier.weight(0.45f))
        Text(value, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.55f))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f Mo".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f Ko".format(bytes / 1_000.0)
    else -> "$bytes o"
}

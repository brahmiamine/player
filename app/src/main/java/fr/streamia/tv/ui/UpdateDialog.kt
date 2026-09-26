package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.UpdateCheckResult
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkLight
import fr.streamia.tv.ui.theme.Danger
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.GlassFillMedium
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import kotlinx.coroutines.yield
import kotlin.math.roundToInt

/** Étape du parcours de mise à jour, telle qu'affichée dans la fenêtre. */
internal enum class UpdateStepState { Pending, Active, Done, Failed }

/** Contenu de la fenêtre pour un état donné : titres, étapes, progression et actions. */
internal data class UpdateDialogContent(
    val title: String,
    val message: String,
    val hint: String? = null,
    /** Téléchargement, Autorisation, Installation ; null : pas d'étapes (déjà à jour…). */
    val steps: List<UpdateStepState>? = null,
    /** 0..1 ; -1 : progression indéterminée ; null : pas de barre. */
    val progress: Float? = null,
    val newVersion: String? = null,
    val primary: UpdateDialogAction? = null,
    val secondaryLabel: String = "Fermer",
    /** Fermer remet l'état à zéro (état final) ; sinon la fenêtre est seulement masquée. */
    val closeClearsState: Boolean = true,
    val tone: Tone = Tone.Normal,
) {
    enum class Tone { Normal, Success, Error }
}

internal enum class UpdateDialogAction(val label: String) {
    Install("Installer maintenant"),
    RetryInstall("Réessayer l'installation"),
    OpenPermission("Ouvrir le réglage"),
    RetryCheck("Réessayer"),
    Ok("OK"),
}

private val D = UpdateStepState.Done
private val A = UpdateStepState.Active
private val P = UpdateStepState.Pending
private val F = UpdateStepState.Failed

internal fun updateDialogContent(checking: Boolean, result: UpdateCheckResult?, currentVersion: String): UpdateDialogContent =
    when (result) {
        null -> UpdateDialogContent(
            title = "Recherche d'une mise à jour…",
            message = "Vérification de la dernière version publiée.",
            progress = -1f,
            secondaryLabel = "Masquer",
            closeClearsState = false,
        ).takeIf { checking } ?: UpdateDialogContent(title = "Mises à jour", message = "Version installée : $currentVersion", primary = UpdateDialogAction.Ok)
        is UpdateCheckResult.UpdateAvailable -> UpdateDialogContent(
            title = "Nouvelle version trouvée",
            message = "Préparation du téléchargement…",
            steps = listOf(A, P, P),
            progress = -1f,
            newVersion = result.release.version,
            secondaryLabel = "Masquer",
            closeClearsState = false,
        )
        is UpdateCheckResult.Downloading -> UpdateDialogContent(
            title = "Téléchargement de la mise à jour",
            message = "Vous pouvez masquer cette fenêtre : le téléchargement continue.",
            steps = listOf(A, P, P),
            progress = result.progress ?: -1f,
            newVersion = result.release.version,
            secondaryLabel = "Masquer",
            closeClearsState = false,
        )
        is UpdateCheckResult.Downloaded -> UpdateDialogContent(
            title = "Mise à jour prête à installer",
            message = "La version ${result.release.version} est téléchargée.",
            steps = listOf(D, D, A),
            newVersion = result.release.version,
            primary = UpdateDialogAction.Install,
            secondaryLabel = "Plus tard",
            closeClearsState = false,
        )
        is UpdateCheckResult.AwaitingInstallPermission -> UpdateDialogContent(
            title = "Autorisez Streamia à installer",
            message = "Android demande votre accord une seule fois pour les mises à jour de Streamia.",
            hint = "Dans le réglage : sélectionnez « Streamia TV » et activez l'interrupteur, puis revenez avec Retour. " +
                "L'installation reprend toute seule, même si Android redémarre Streamia.",
            steps = listOf(D, A, P),
            newVersion = result.release.version,
            primary = UpdateDialogAction.OpenPermission,
            secondaryLabel = "Plus tard",
            closeClearsState = false,
        )
        is UpdateCheckResult.Installing -> UpdateDialogContent(
            title = "Installation en cours",
            message = if (result.silent) {
                "Streamia va se fermer quelques secondes pour se mettre à jour. Rouvrez-la ensuite depuis l'accueil."
            } else {
                "Confirmez avec « Installer » dans la fenêtre Android."
            },
            hint = if (result.silent) null else "Streamia se ferme pendant l'installation : rouvrez-la ensuite depuis l'accueil.",
            steps = listOf(D, D, A),
            progress = -1f,
            newVersion = result.release.version,
            secondaryLabel = "Masquer",
            closeClearsState = false,
        )
        is UpdateCheckResult.UpToDate -> UpdateDialogContent(
            title = "Streamia est à jour",
            message = "Vous avez la dernière version (${result.currentVersion}).",
            primary = UpdateDialogAction.Ok,
            tone = UpdateDialogContent.Tone.Success,
        )
        is UpdateCheckResult.NoTaggedRelease -> UpdateDialogContent(
            title = "Aucune version publiée",
            message = "Aucune nouvelle version n'a encore été publiée.",
            primary = UpdateDialogAction.Ok,
        )
        is UpdateCheckResult.Error -> UpdateDialogContent(
            title = if (result.release != null) "Installation interrompue" else "Mise à jour impossible",
            message = result.message,
            steps = if (result.release != null) listOf(D, D, F) else listOf(F, P, P),
            newVersion = result.release?.version,
            primary = if (result.release != null) UpdateDialogAction.RetryInstall else UpdateDialogAction.RetryCheck,
            tone = UpdateDialogContent.Tone.Error,
        )
    }

/**
 * Fenêtre de mise à jour : étapes, progression réelle du téléchargement et une seule action
 * principale, qui reçoit le focus. Retour ou « Masquer » la ferme sans interrompre le travail.
 */
@Composable
internal fun UpdateDialog(
    content: UpdateDialogContent,
    currentVersion: String,
    onAction: (UpdateDialogAction) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val primaryFocus = remember { FocusRequester() }
    val secondaryFocus = remember { FocusRequester() }
    LaunchedEffect(content.primary, content.title) {
        yield()
        runCatching { if (content.primary != null) primaryFocus.requestFocus() else secondaryFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 820.dp)
                .fillMaxWidth(0.62f)
                .clip(RoundedCornerShape(28.dp))
                .background(Night.copy(alpha = 0.94f))
                .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(28.dp))
                .padding(horizontal = 36.dp, vertical = 30.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val badge = when (content.tone) {
                    UpdateDialogContent.Tone.Error -> Brush.linearGradient(listOf(Danger, Danger.copy(alpha = 0.7f)))
                    else -> Brush.linearGradient(listOf(AccentPinkLight, AccentPink))
                }
                Box(Modifier.size(62.dp).clip(CircleShape).background(badge), contentAlignment = Alignment.Center) {
                    StreamiaIcon(StreamiaIconGlyph.Refresh, tint = Color.White, size = 30.dp)
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(content.title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(content.message, color = MutedInk, fontSize = 15.sp, lineHeight = 20.sp)
                }
            }

            Spacer(Modifier.height(22.dp))
            VersionLine(currentVersion, content.newVersion)

            content.steps?.let { steps ->
                Spacer(Modifier.height(24.dp))
                UpdateSteps(steps)
            }

            content.progress?.let { progress ->
                Spacer(Modifier.height(24.dp))
                UpdateProgressBar(progress)
            }

            content.hint?.let { hint ->
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GlassFillMedium)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Text(hint, color = Ink, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }

            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                content.primary?.let { action ->
                    FocusableSurface(
                        onClick = { onAction(action) },
                        accent = true,
                        focusScale = 1.04f,
                        modifier = Modifier.width(280.dp).height(56.dp).focusRequester(primaryFocus),
                    ) {
                        Text(
                            action.label,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        )
                    }
                }
                if (content.primary != UpdateDialogAction.Ok) {
                    FocusableSurface(
                        onClick = onClose,
                        focusScale = 1.04f,
                        modifier = Modifier.width(200.dp).height(56.dp).focusRequester(secondaryFocus),
                    ) {
                        Text(content.secondaryLabel, color = Ink, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionLine(currentVersion: String, newVersion: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        VersionChip("Installée", currentVersion, highlighted = false)
        if (newVersion != null) {
            Text("  →  ", color = MutedInk, fontSize = 18.sp)
            VersionChip("Nouvelle", newVersion, highlighted = true)
        }
    }
}

@Composable
private fun VersionChip(label: String, value: String, highlighted: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) AccentPink.copy(alpha = 0.22f) else GlassFillMedium)
            .border(BorderStroke(1.dp, if (highlighted) AccentPink else GlassBorder), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MutedInk, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(value, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

private val STEP_LABELS = listOf("Téléchargement", "Autorisation", "Installation")

@Composable
private fun UpdateSteps(steps: List<UpdateStepState>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { index, state ->
            StepDot(index + 1, STEP_LABELS[index], state)
            if (index < steps.lastIndex) {
                val done = state == UpdateStepState.Done
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (done) AccentPink else GlassBorder),
                )
            }
        }
    }
}

@Composable
private fun StepDot(number: Int, label: String, state: UpdateStepState) {
    val (fill, border, text) = when (state) {
        UpdateStepState.Done -> Triple(AccentPink, AccentPink, Color.White)
        UpdateStepState.Active -> Triple(AccentPink.copy(alpha = 0.25f), AccentPinkLight, Color.White)
        UpdateStepState.Failed -> Triple(Danger.copy(alpha = 0.25f), Danger, Color.White)
        UpdateStepState.Pending -> Triple(Color.Transparent, GlassBorder, MutedInk)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(fill).border(BorderStroke(2.dp, border), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (state) {
                    UpdateStepState.Done -> "✓"
                    UpdateStepState.Failed -> "!"
                    else -> number.toString()
                },
                color = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = if (state == UpdateStepState.Pending) MutedInk else Ink,
            fontSize = 15.sp,
            fontWeight = if (state == UpdateStepState.Active) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/** Barre de progression : remplissage réel (0..1) avec pourcentage, ou segment animé si inconnu. */
@Composable
private fun UpdateProgressBar(progress: Float) {
    val determinate = progress >= 0f
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GlassFillMedium),
        ) {
            val fill = Brush.horizontalGradient(listOf(AccentPinkLight, AccentPink))
            if (determinate) {
                val animated by animateFloatAsState(progress.coerceIn(0f, 1f), animationSpec = tween(250), label = "update-progress")
                Box(Modifier.fillMaxWidth(animated).height(12.dp).clip(RoundedCornerShape(6.dp)).background(fill))
            } else {
                val transition = rememberInfiniteTransition(label = "update-indeterminate")
                val shift by transition.animateFloat(
                    initialValue = -0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart),
                    label = "update-indeterminate-shift",
                )
                val trackWidthPx = constraints.maxWidth
                Box(
                    Modifier
                        .offset { IntOffset((trackWidthPx * shift).roundToInt(), 0) }
                        .width(maxWidth * 0.35f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(fill),
                )
            }
        }
        if (determinate) {
            Spacer(Modifier.height(8.dp))
            Text("${(progress * 100).toInt()} %", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

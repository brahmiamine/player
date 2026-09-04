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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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

private sealed interface NewPinStep {
    data object EnterNew : NewPinStep
    data class ConfirmNew(val pending: String) : NewPinStep
}

@Composable
fun ParentalControlScreen(
    enabled: Boolean,
    onSetPin: (String) -> Unit,
    onVerifyPin: (String) -> Boolean,
    onDisable: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var newPinStep by remember { mutableStateOf<NewPinStep?>(null) }
    var confirmDisable by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Night).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FocusableSurface(onClick = onBack, modifier = Modifier.width(120.dp).height(50.dp)) {
                Text("← Retour", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text("Contrôle parental", color = Ink, fontSize = 27.sp, fontWeight = HeadingWeight)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (enabled) {
                "Protection activée. Un code est demandé la première fois que vous ouvrez une catégorie " +
                    "verrouillée depuis Organiser, jusqu'à la fermeture complète de l'application."
            } else {
                "Aucun code défini : aucune catégorie n'est protégée. Définissez un code pour pouvoir " +
                    "verrouiller des catégories depuis Organiser (sélection multiple → Verrouiller)."
            },
            color = MutedInk,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsTile(
                glyph = StreamiaIconGlyph.Lock,
                title = if (enabled) "Changer le code" else "Définir un code",
                subtitle = "Code à 4 chiffres, saisi deux fois",
                onClick = { newPinStep = NewPinStep.EnterNew },
                modifier = Modifier.width(260.dp).height(150.dp),
            )
            if (enabled) {
                SettingsTile(
                    glyph = StreamiaIconGlyph.Delete,
                    title = "Désactiver",
                    subtitle = "Retire le code et déverrouille tout",
                    onClick = { confirmDisable = true },
                    modifier = Modifier.width(260.dp).height(150.dp),
                )
            }
        }
    }

    when (val step = newPinStep) {
        NewPinStep.EnterNew -> ParentalPinDialog(
            title = "Nouveau code",
            subtitle = "Choisissez un code à 4 chiffres",
            onSubmit = { pin -> newPinStep = NewPinStep.ConfirmNew(pin); true },
            onCancel = { newPinStep = null },
        )
        is NewPinStep.ConfirmNew -> ParentalPinDialog(
            title = "Confirmez le code",
            subtitle = "Ressaisissez le même code",
            onSubmit = { pin ->
                val matches = pin == step.pending
                if (matches) {
                    onSetPin(pin)
                    newPinStep = null
                }
                matches
            },
            onCancel = { newPinStep = null },
        )
        null -> Unit
    }

    if (confirmDisable) {
        ParentalPinDialog(
            title = "Désactiver le contrôle parental",
            subtitle = "Entrez le code actuel pour confirmer",
            onSubmit = { pin ->
                val correct = onVerifyPin(pin)
                if (correct) {
                    onDisable()
                    confirmDisable = false
                }
                correct
            },
            onCancel = { confirmDisable = false },
        )
    }
}

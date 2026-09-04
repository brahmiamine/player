package fr.streamia.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.TypeLabel
import fr.streamia.tv.ui.theme.TypeSectionTitle

/**
 * Clavier numérique plein écran pour saisir un code parental — pas d'hypothèse sur la présence
 * de touches numériques physiques sur la télécommande, tout se pilote à la croix directionnelle.
 * [onSubmit] est appelé une fois [digitCount] chiffres saisis et doit renvoyer si le code est
 * correct ; en cas d'échec le champ se vide et un message d'erreur s'affiche.
 */
@Composable
fun ParentalPinDialog(
    title: String,
    subtitle: String,
    onSubmit: (String) -> Boolean,
    onCancel: () -> Unit,
    digitCount: Int = 4,
) {
    var pin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(420.dp).background(Night, RoundedCornerShape(16.dp)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StreamiaIcon(StreamiaIconGlyph.Lock, tint = FocusBlueBright, size = 30.dp)
            Spacer(Modifier.height(10.dp))
            Text(title, color = Ink, fontSize = TypeSectionTitle, fontWeight = HeadingWeight, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = MutedInk, fontSize = TypeLabel, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(digitCount) { index ->
                    Box(
                        Modifier.size(16.dp)
                            .background(if (index < pin.length) FocusBlueBright else DeepSurface, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                errorMessage ?: " ",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            NumericKeypad(
                onDigit = { digit ->
                    if (pin.length >= digitCount) return@NumericKeypad
                    val next = pin + digit
                    pin = next
                    if (next.length == digitCount) {
                        if (onSubmit(next)) {
                            errorMessage = null
                        } else {
                            errorMessage = "Code incorrect"
                            pin = ""
                        }
                    } else {
                        errorMessage = null
                    }
                },
                onClear = { pin = ""; errorMessage = null },
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun NumericKeypad(
    onDigit: (String) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit -> KeypadButton(digit) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeypadButton("Effacer", wide = true, onClick = onClear)
            KeypadButton("0") { onDigit("0") }
            KeypadButton("Annuler", wide = true, onClick = onCancel)
        }
    }
}

@Composable
private fun KeypadButton(label: String, wide: Boolean = false, onClick: () -> Unit) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.width(if (wide) 96.dp else 56.dp).height(52.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Ink, fontSize = if (wide) 12.sp else 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

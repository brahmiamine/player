package fr.streamia.tv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.ui.AccentPill
import fr.streamia.tv.ui.GlassSurface
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.RadiusTile
import kotlinx.coroutines.launch

/**
 * Pavé numérique en feuille du bas : numéro de chaîne (le Direct) ou code parental (4 chiffres,
 * validé tout seul à la dernière touche). [onSubmit] renvoie un message d'erreur, ou `null` si la
 * saisie est acceptée — l'appelant ferme alors la feuille.
 */
@Composable
fun MobileKeypadSheet(
    title: String,
    subtitle: String,
    maxDigits: Int,
    masked: Boolean,
    autoSubmit: Boolean,
    confirmLabel: String,
    onSubmit: suspend (String) -> String?,
    onDismiss: () -> Unit,
) {
    var digits by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit(value: String) {
        if (checking || value.isEmpty()) return
        checking = true
        scope.launch {
            val result = onSubmit(value)
            checking = false
            if (result != null) {
                error = result
                digits = ""
            }
        }
    }

    fun press(key: String) {
        if (checking) return
        error = null
        when (key) {
            "⌫" -> digits = digits.dropLast(1)
            "" -> Unit
            else -> if (digits.length < maxDigits) {
                digits += key
                if (autoSubmit && digits.length == maxDigits) submit(digits)
            }
        }
    }

    MobileBottomSheet(title = title, subtitle = subtitle, onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassSurface(Modifier.fillMaxWidth().height(68.dp), shape = RoundedCornerShape(RadiusTile)) {
                Box(Modifier.fillMaxWidth().height(68.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (masked) "•".repeat(digits.length) else digits,
                        color = Ink,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 6.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            error?.let { Text(it, color = AccentPinkText, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
            Spacer(Modifier.height(4.dp))
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫")).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { key ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(RoundedCornerShape(RadiusTile))
                                .background(if (key.isEmpty()) Color.Transparent else Color.White.copy(alpha = 0.08f))
                                .clickable(enabled = key.isNotEmpty(), role = Role.Button) { press(key) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(key, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (!autoSubmit) {
                Spacer(Modifier.height(4.dp))
                AccentPill(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clickable(enabled = digits.isNotEmpty(), role = Role.Button) { submit(digits) },
                ) {
                    Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                        Text(confirmLabel, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

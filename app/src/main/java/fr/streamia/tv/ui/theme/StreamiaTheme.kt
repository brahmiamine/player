package fr.streamia.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val Night = Color(0xFF080B0D)
val DeepSurface = Color(0xFF11171A)
val RaisedSurface = Color(0xFF1A2327)
val FocusBlue = Color(0xFF4E91A2)
val FocusBlueBright = Color(0xFF76BED0)
val WarmSignal = Color(0xFFE6B75C)
val Ink = Color(0xFFF4F7F8)
val MutedInk = Color(0xFFB8C2C6)
val Danger = Color(0xFFFFB4AB)

private val StreamiaColors = darkColorScheme(
    primary = FocusBlueBright,
    onPrimary = Night,
    secondary = WarmSignal,
    onSecondary = Night,
    background = Night,
    onBackground = Ink,
    surface = DeepSurface,
    onSurface = Ink,
    surfaceVariant = RaisedSurface,
    onSurfaceVariant = MutedInk,
    error = Danger,
    onError = Night,
)

@Composable
fun StreamiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StreamiaColors, content = content)
}

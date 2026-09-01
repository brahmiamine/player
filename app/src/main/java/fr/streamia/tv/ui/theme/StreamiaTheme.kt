package fr.streamia.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
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

/**
 * Échelle typographique partagée : le même rôle (titre de contenu, titre d'écran, titre de
 * section, paragraphe, bouton/méta) garde la même taille sur tous les écrans plutôt que chaque
 * écran n'invente la sienne au cas par cas. Ne couvre que les rôles réellement dupliqués entre
 * écrans — un texte réellement unique à un endroit garde une valeur littérale.
 */
val TypeHero = 30.sp // Titre de contenu (film, série)
val TypeHeroLineHeight = 36.sp
val TypeScreenTitle = 27.sp // En-tête d'écran utilitaire (EPG, Recherche, Organiser)
val TypeSectionTitle = 18.sp // Titre de section ("Catégories", "Saisons", nom de catégorie source…)
val TypeBody = 16.sp // Paragraphe (synopsis) et titres de carte/ligne de contenu
val TypeBodyLineHeight = 23.sp
val TypeLabel = 14.sp // Boutons, méta-ligne, texte secondaire

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

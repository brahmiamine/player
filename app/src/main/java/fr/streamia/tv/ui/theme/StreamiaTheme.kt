package fr.streamia.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// Palette "iOS Glass" : verre dépoli très marqué (visionOS) sur fond noir portant de larges
// dégradés colorés diffus, accent rose unique (#FF375F) utilisé en aplat sur les actions
// principales. Les rôles ci-dessous reprennent les noms de l'ancienne palette Nocturne (aucun
// écran n'a donc besoin d'être retouché pour les hériter) mais avec les valeurs du nouveau
// système ; les rôles propres au verre (remplissages translucides, bordures, rayons) sont
// nouveaux et vivent à côté.
val Night = Color(0xFF050506) // --color-bg : quasi noir, support des dégradés diffus derrière le verre
val DeepSurface = Color(0x1FFFFFFF) // blanc 12% : remplissage verre au repos
val RaisedSurface = Color(0x29FFFFFF) // blanc 16% : remplissage verre sélectionné (non focalisé)
val AccentPink = Color(0xFFFF375F)
val AccentPinkLight = Color(0xFFFF5C7C)
val AccentPinkText = Color(0xFFFF7A93) // texte sur fond sombre portant l'accent (méta, "EN DIRECT"…)
val FocusBlue = Color(0x3DFF375F) // accent rose 24% : remplissage d'un élément focalisé
val FocusBlueBright = AccentPink // contour de focus, icônes, accents
val WarmSignal = Color(0xFFFF9F0A) // signal d'alerte (hors-ligne, verrouillage)
val Ink = Color(0xFFFFFFFF) // --color-text
val MutedInk = Color(0x9EFFFFFF) // blanc 62%
val Danger = Color(0xFFFF6B6B)

// Rôles propres au verre : bordures et remplissages translucides des cartes/pilules, dérivés du
// blanc à alpha croissante (mêmes valeurs que le prototype HTML : 0.06 / 0.08 / 0.10 / 0.16).
val GlassBorder = Color(0x29FFFFFF)
val GlassFillFaint = Color(0x0FFFFFFF)
val GlassFillSoft = Color(0x14FFFFFF)
val GlassFillMedium = Color(0x1AFFFFFF)

// Rayons "très arrondis" (iOS 18 / visionOS) — un seul jeu de tailles pour tout l'habillage verre.
val RadiusCard = 28.dp
val RadiusPanel = 24.dp
val RadiusTile = 20.dp
val RadiusPill = 999.dp

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

// Le verre iOS porte sa hiérarchie par la graisse autant que par la taille : les titres vont du
// Bold au Black selon le prototype. N'affecte que les titres/hero ; les libellés de bouton et les
// onglets gardent leur graisse forte pour rester lisibles à distance du canapé.
val HeadingWeight = FontWeight.Bold
val HeroWeight = FontWeight.ExtraBold
// Espacement des étiquettes de section capitalisées ("CATÉGORIES", "REPRENDRE LA LECTURE"…).
val KickerLetterSpacing = 1.1.sp

private val StreamiaColors = darkColorScheme(
    primary = FocusBlueBright,
    onPrimary = Ink,
    secondary = WarmSignal,
    onSecondary = Night,
    background = Night,
    onBackground = Ink,
    surface = DeepSurface,
    onSurface = Ink,
    surfaceVariant = RaisedSurface,
    onSurfaceVariant = MutedInk,
    error = Danger,
    onError = Ink,
)

@Composable
fun StreamiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StreamiaColors, content = content)
}

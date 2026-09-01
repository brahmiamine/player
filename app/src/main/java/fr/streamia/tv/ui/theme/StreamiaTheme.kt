package fr.streamia.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// Palette "Nocturne" : fond bleu-gris quasi neutre, accent blurple unique utilisé en trait plutôt
// qu'en aplat. Les rôles ci-dessous reprennent ceux de l'ancienne palette (mêmes noms, donc aucun
// écran n'a besoin d'être retouché pour en bénéficier) mais avec les valeurs du nouveau système :
// bg/surface/texte/accent viennent tels quels de ses tokens ; les autres rôles (remplissage
// focus, bordure de repos, alerte) sont choisis sur ses rampes tonales pour rester cohérents avec
// elles.
val Night = Color(0xFF161826) // --color-bg
val DeepSurface = Color(0xFF232532) // --color-surface
val RaisedSurface = Color(0xFF3F424D) // --color-neutral-800
val FocusBlue = Color(0xFF2B2741) // --color-accent-900 : remplissage d'un élément focalisé
val FocusBlueBright = Color(0xFF9184D9) // --color-accent : icônes, accents, contour de focus
val WarmSignal = Color(0xFFC9A06B) // signal d'alerte (hors-ligne, suppression) à faible chroma
val Ink = Color(0xFFE9E9ED) // --color-text
val MutedInk = Color(0xFF9397AB) // --color-neutral-500
val Danger = Color(0xFFE2938C) // erreur, désaturée pour rester dans l'esprit du système

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

// Nocturne tient sa hiérarchie par la taille et l'espace, pas par la graisse : les titres n'y
// dépassent jamais 500 (Medium). N'affecte que les titres/hero ; les libellés de bouton et les
// onglets gardent leur graisse forte pour rester lisibles à distance du canapé.
val HeadingWeight = FontWeight.Medium
// Espacement des étiquettes de section capitalisées ("CATÉGORIES", "REPRENDRE LA LECTURE"…).
val KickerLetterSpacing = 1.1.sp

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

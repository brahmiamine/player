package fr.streamia.tv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import fr.streamia.tv.R
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkLight
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlue
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.KickerLetterSpacing
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusTile
import fr.streamia.tv.ui.theme.RaisedSurface
import fr.streamia.tv.ui.theme.TypeSectionTitle

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    // Permet à un panneau affiché par-dessus une vidéo plein écran (ex. catégories/chaînes du
    // Direct) de rendre ses lignes au repos transparentes — seul le focus/la sélection reste plein
    // — sans changer l'aspect opaque par défaut des autres écrans (Accueil, VOD, Organiser…).
    idleBackground: Color = DeepSurface,
    // Action principale (équivalent `.btn-accent` du prototype) : pilule dégradé accent avec
    // lueur permanente, plutôt que le remplissage verre neutre des autres tuiles.
    accent: Boolean = false,
    // Par défaut la surface se déploie sur toute la largeur qui lui est offerte (lignes de listes,
    // tuiles). Mis à `true`, elle se cintre à son contenu : indispensable dans un FlowRow, où un
    // `fillMaxSize()` interne forçait sinon chaque « chip » à occuper toute une ligne.
    wrapContent: Boolean = false,
    // Agrandissement au focus : à réduire pour les lignes pleine largeur, où 6 % déborde de l'écran.
    focusScale: Float = 1.06f,
    // Arrondi des coins : RadiusPill pour les pilules (pastilles, boutons), RadiusTile par défaut.
    radius: androidx.compose.ui.unit.Dp = RadiusTile,
    content: @Composable () -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }
    // Au doigt, sur téléphone seulement (la TV garde toujours son focus visible) : pas d'effet de focus TV (agrandissement, bordure rose) sur l'élément touché.
    val focused = hasFocus && !(LocalHandheld.current && LocalInputModeManager.current.inputMode == InputMode.Touch)
    var longPressConsumed by remember { mutableStateOf(false) }
    var selectPressed by remember { mutableStateOf(false) }
    // Le focus doit rester visible depuis l'autre bout du salon : un agrandissement net, une
    // lueur accent (pas seulement un changement de teinte) et une bordure large — jamais un
    // simple aplat de couleur — voir PRODUCT.md § Accessibilité & inclusion.
    // Animation courte (appui maintenu = focus qui défile vite) et limitée au scale, appliqué en
    // graphicsLayer : bon marché. Pas d'ombre au repos ni d'élévation animée — une ombre animée
    // force son re-rendu à chaque image, et une ombre par ligne dans des listes de centaines de
    // chaînes suffisait à faire saccader les petits GPU des boîtiers TV.
    // Lu uniquement dans graphicsLayer (phase de dessin) : l'animation ne recompose plus les deux
    // éléments concernés à chaque image, ce qui comptait en maintenant une flèche dans une longue liste.
    val scale = animateFloatAsState(if (focused) focusScale else 1f, animationSpec = tween(110), label = "focus-scale")
    val elevation = when {
        focused -> 18.dp
        accent -> 10.dp
        else -> 0.dp
    }
    val shape = RoundedCornerShape(radius)
    val background = when {
        focused -> FocusBlue
        selected -> RaisedSurface
        else -> idleBackground
    }
    val border = when {
        focused -> BorderStroke(3.dp, FocusBlueBright)
        accent -> BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
        selected -> BorderStroke(2.dp, FocusBlue)
        else -> BorderStroke(1.dp, GlassBorder)
    }
    val glowColor = when {
        focused -> AccentPink.copy(alpha = 0.55f)
        accent -> AccentPink.copy(alpha = 0.45f)
        else -> Color.Transparent
    }

    // Le scale/l'ombre de focus sont appliqués à une Box interne, jamais à `modifier` lui-même :
    // Modifier.scale() fait grandir les bounds vus par le système de focus (boundsInParent inclut
    // les graphicsLayer), donc les mettre sur le nœud focusable faisait grandir sa zone à chaque
    // frame de l'animation et déclenchait un bringIntoView vertical de la LazyColumn parente à
    // chaque changement de focus horizontal dans une LazyRow — d'où le tremblement de tout l'écran
    // en se déplaçant entre les cards. La Box externe (focus/clic) garde une taille fixe ; seule la
    // Box interne se redimensionne visuellement.
    Box(
        modifier = modifier
            .onFocusChanged {
                hasFocus = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .onPreviewKeyEvent { composeEvent ->
                val longAction = onLongClick ?: return@onPreviewKeyEvent false
                if (!enabled) return@onPreviewKeyEvent false
                val event = composeEvent.nativeKeyEvent
                if (!event.isTvSelectKey()) return@onPreviewKeyEvent false

                when {
                    event.action == AndroidKeyEvent.ACTION_DOWN && event.repeatCount > 0 && !longPressConsumed -> {
                        longPressConsumed = true
                        longAction()
                        true
                    }
                    event.action == AndroidKeyEvent.ACTION_DOWN && longPressConsumed -> true
                    event.action == AndroidKeyEvent.ACTION_UP && longPressConsumed -> {
                        longPressConsumed = false
                        selectPressed = false
                        true
                    }
                    // OK court : clic géré ici, pour que combinedClickable (appui long au doigt) ne voie
                    // jamais la touche et ne déclenche pas son propre appui long au clavier en plus.
                    // Un OK relâché dont l'appui visait un autre élément (écran qui vient de s'ouvrir) est ignoré.
                    event.action == AndroidKeyEvent.ACTION_DOWN -> {
                        selectPressed = true
                        true
                    }
                    event.action == AndroidKeyEvent.ACTION_UP -> {
                        if (selectPressed) onClick()
                        selectPressed = false
                        true
                    }
                    else -> false
                }
            }
            .combinedClickable(enabled = enabled, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .focusable(enabled)
            .then(
                if (contentDescription == null) Modifier
                else Modifier.semantics { this.contentDescription = contentDescription },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .then(if (wrapContent) Modifier else Modifier.fillMaxSize())
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                // Halo dessiné (anneaux translucides mis en cache) plutôt qu'une ombre colorée :
                // l'ombre était recalculée par le GPU à chaque déplacement du focus.
                .then(if (elevation > 0.dp) Modifier.focusHalo(glowColor, elevation, radius) else Modifier)
                .clip(shape)
                .then(
                    if (accent) {
                        Modifier.background(Brush.verticalGradient(listOf(AccentPinkLight, AccentPink)), shape)
                    } else {
                        Modifier.background(background, shape)
                    },
                )
                .border(border, shape),
            contentAlignment = Alignment.CenterStart,
        ) {
            content()
        }
    }
}

/**
 * Lueur autour d'une surface : quelques anneaux arrondis d'opacité décroissante, dessinés hors des
 * bords (la couche de l'échelle n'est pas rognée). Géométrie mise en cache par taille.
 */
internal fun Modifier.focusHalo(color: Color, spread: androidx.compose.ui.unit.Dp, radius: androidx.compose.ui.unit.Dp): Modifier =
    drawWithCache {
        val spreadPx = spread.toPx() * HALO_SPREAD_RATIO
        val radiusPx = minOf(radius.toPx(), size.minDimension / 2)
        val ringWidth = spreadPx / HALO_RINGS
        onDrawBehind {
            for (ring in 0 until HALO_RINGS) {
                val inset = ring * ringWidth + ringWidth / 2
                drawRoundRect(
                    color = color.copy(alpha = color.alpha * (1f - ring.toFloat() / HALO_RINGS) * 0.6f),
                    topLeft = androidx.compose.ui.geometry.Offset(-inset, -inset),
                    size = androidx.compose.ui.geometry.Size(size.width + inset * 2, size.height + inset * 2),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx + inset),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = ringWidth),
                )
            }
        }
    }

private const val HALO_RINGS = 4

private const val HALO_SPREAD_RATIO = 0.6f

private fun AndroidKeyEvent.isTvSelectKey(): Boolean = when (keyCode) {
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
    AndroidKeyEvent.KEYCODE_BUTTON_A,
    -> true
    else -> false
}

@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val fieldBackground = if (enabled) RaisedSurface else DeepSurface.copy(alpha = 0.5f)
    val textColor = if (enabled) Ink else MutedInk.copy(alpha = 0.66f)
    val labelColor = when {
        !enabled -> MutedInk.copy(alpha = 0.55f)
        focused -> FocusBlueBright
        else -> MutedInk
    }
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        visualTransformation = visualTransformation,
        textStyle = TextStyle(color = textColor, fontSize = 19.sp, fontWeight = FontWeight.Medium),
        modifier = modifier
            .height(if (supportingText == null) 68.dp else 88.dp)
            .clip(shape)
            .background(fieldBackground)
            .border(
                if (focused && enabled) 3.dp else 1.dp,
                if (focused && enabled) FocusBlueBright else GlassBorder,
                shape,
            )
            .onFocusChanged { focused = enabled && it.isFocused }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        decorationBox = { input ->
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.Center) {
                Text(label, color = labelColor, fontSize = 14.sp)
                Spacer(Modifier.height(3.dp))
                input()
                if (supportingText != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(supportingText, color = if (enabled) MutedInk else MutedInk.copy(alpha = 0.55f), fontSize = 12.sp)
                }
            }
        },
    )
}

@Composable
fun StreamiaLogo(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.streamia_logo_mark),
            contentDescription = "Logo Streamia TV",
            modifier = Modifier.size(if (compact) 42.dp else 58.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.width(if (compact) 12.dp else 16.dp))
        Text(
            text = "Streamia TV",
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            color = Ink,
            fontWeight = HeadingWeight,
        )
    }
}

/**
 * Étiquette au-dessus d'une liste ou d'une rangée ("Catégories", "Chaînes", "Reprendre la
 * lecture"…) : capitales espacées sur un ton neutre plutôt qu'un titre plein, pour la distinguer
 * du contenu qu'elle introduit — celui-ci garde sa pleine lisibilité, seule l'étiquette s'efface.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = TypeSectionTitle) {
    Text(
        text.asKickerLabel(),
        color = MutedInk,
        fontSize = fontSize,
        fontWeight = HeadingWeight,
        letterSpacing = KickerLetterSpacing,
        modifier = modifier,
    )
}

// En dehors du composable : lire une locale directement dans un @Composable n'est pas observable
// par la recomposition (Lint : "Reading locale in a non-observable way"). Locale.FRENCH plutôt que
// getDefault() : l'app n'affiche que du texte français (androidResources.localeFilters = "fr"),
// donc la casse ne doit pas dépendre de la locale système de l'appareil.
private fun String.asKickerLabel(): String = uppercase(java.util.Locale.FRENCH)

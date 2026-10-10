package fr.streamia.tv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.ui.GlassSurface
import fr.streamia.tv.ui.StreamiaIcon
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkLight
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.FocusBlue
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.KickerLetterSpacing
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusCard
import fr.streamia.tv.ui.theme.RadiusPill
import fr.streamia.tv.ui.theme.RadiusTile
import kotlin.math.max
import kotlin.math.min

// Composants communs de l'interface mobile : mêmes couleurs, rayons et verre que la TV
// (thème « iOS Glass »), mais cibles tactiles d'au moins 44 dp et gestes au doigt.

internal val MobileGutter = 16.dp
internal val MobileMinTouch = 44.dp

/** Onglets de la barre du bas. */
enum class MobileTab(val label: String, val glyph: StreamiaIconGlyph) {
    Home("Accueil", StreamiaIconGlyph.Home),
    Live("Direct", StreamiaIconGlyph.Live),
    Movies("Films", StreamiaIconGlyph.Movie),
    Series("Séries", StreamiaIconGlyph.Series),
    More("Plus", StreamiaIconGlyph.Menu),
}

/** Barre d'onglets flottante en pilule de verre, posée au-dessus de la barre de gestes. */
@Composable
fun MobileBottomBar(selected: MobileTab, onSelect: (MobileTab) -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .fillMaxWidth()
            .height(68.dp),
        shape = RoundedCornerShape(RadiusPill),
        tintColor = Color(0xE01E1E24),
    ) {
        Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MobileTab.entries.forEach { tab ->
                val active = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(RadiusPill))
                        .background(if (active) FocusBlue else Color.Transparent)
                        .clickable(role = Role.Tab) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    StreamiaIcon(tab.glyph, tint = if (active) AccentPinkText else MutedInk, size = 22.dp)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        tab.label,
                        color = if (active) AccentPinkText else MutedInk,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Bouton rond de verre de 44 dp (retour, recherche, réglages…). */
@Composable
fun MobileIconButton(
    glyph: StreamiaIconGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Ink,
) {
    GlassSurface(
        modifier = modifier.size(MobileMinTouch).clickable(role = Role.Button, onClick = onClick),
        shape = CircleShape,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            StreamiaIcon(glyph, tint = tint, size = 22.dp)
        }
    }
}

/** En-tête d'écran : titre large à gauche, actions rondes à droite. */
@Composable
fun MobileHeader(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = MobileGutter, end = MobileGutter, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            color = Ink,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** Étiquette de section en capitales espacées, avec un repère facultatif à droite. */
@Composable
fun MobileSectionLabel(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text.uppercase(java.util.Locale.FRENCH),
            color = MutedInk,
            fontSize = 11.sp,
            fontWeight = HeadingWeight,
            letterSpacing = KickerLetterSpacing,
        )
        if (hint != null) Text(hint, color = MutedInk, fontSize = 11.sp)
    }
}

/** Pastille de catégorie : sélectionnée = aplat accent, sinon verre. */
@Composable
fun MobileChip(label: String, selected: Boolean, onClick: () -> Unit, locked: Boolean = false, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(RadiusPill)
    // Pastille sélectionnée toujours ramenée à l'écran (retour sur la catégorie d'une chaîne, par exemple).
    val bringIntoView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(selected) { if (selected) bringIntoView.bringIntoView() }
    Row(
        modifier
            .bringIntoViewRequester(bringIntoView)
            .height(40.dp)
            .clip(shape)
            .background(if (selected) AccentPink else Color.White.copy(alpha = 0.09f))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (locked) StreamiaIcon(StreamiaIconGlyph.Lock, tint = if (selected) Ink else Color(0xFFFF9F0A), size = 14.dp)
        Text(label, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Rangée défilante de pastilles de catégories. */
@Composable
fun MobileChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = MobileGutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Carte de verre cliquable (tap) avec appui long facultatif. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    radius: Dp = RadiusTile,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val interaction = remember { MutableInteractionSource() }
    GlassSurface(
        modifier = if (onClick != null) {
            modifier.combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick,
            )
        } else modifier,
        shape = shape,
    ) { content() }
}

/** Grande carte d'action en aplat accent avec lueur (équivalent de `.btn-accent`). */
@Composable
fun MobileAccentCard(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(RadiusCard)
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(AccentPinkLight, AccentPink)))
            .clickable(role = Role.Button, onClick = onClick),
    ) { content() }
}

/**
 * Tirer pour actualiser : tirer la liste vers le bas depuis le haut, relâcher au-delà du seuil.
 * Le contenu doit contenir un conteneur défilant (LazyColumn, LazyVerticalGrid…).
 */
@Composable
fun MobilePullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val thresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    val connection = remember(thresholdPx, refreshing) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Remonter la liste d'abord résorbe le tirage en cours.
                if (pull > 0f && available.y < 0f) {
                    val consumed = max(available.y, -pull)
                    pull += consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // La liste est tout en haut et le doigt continue de descendre : on tire.
                if (!refreshing && source == NestedScrollSource.UserInput && available.y > 0f) {
                    pull = min(pull + available.y * 0.5f, thresholdPx * 1.6f)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!refreshing && pull >= thresholdPx) onRefresh()
                pull = 0f
                return Velocity.Zero
            }
        }
    }
    val indicatorHeight = with(LocalDensity.current) { (if (refreshing) thresholdPx * 0.7f else pull).toDp() }
    Column(modifier.nestedScroll(connection)) {
        Box(Modifier.fillMaxWidth().height(indicatorHeight), contentAlignment = Alignment.Center) {
            if (indicatorHeight > 12.dp) {
                Text(
                    when {
                        refreshing -> "ACTUALISATION…"
                        pull >= thresholdPx -> "RELÂCHER POUR ACTUALISER"
                        else -> "TIRER POUR ACTUALISER"
                    },
                    color = MutedInk,
                    fontSize = 11.sp,
                    fontWeight = HeadingWeight,
                    letterSpacing = KickerLetterSpacing,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/**
 * Feuille du bas : voile qui ferme au toucher, bouton Retour système, poignée. Sert aux menus
 * d'appui long (favori, masquer…), au tri et au pavé numérique.
 */
@Composable
fun MobileBottomSheet(title: String, subtitle: String? = null, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        GlassSurface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 560.dp),
            shape = RoundedCornerShape(topStart = RadiusCard, topEnd = RadiusCard),
            tintColor = Color(0xFA1C1C22),
            borderColor = GlassBorder,
        ) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(40.dp).height(5.dp).clip(RoundedCornerShape(RadiusPill)).background(Color.White.copy(alpha = 0.3f)))
                }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp)) {
                    Text(title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, color = MutedInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                content()
            }
        }
    }
}

/** Ligne d'action d'une feuille du bas (icône + libellé), 52 dp de haut. */
@Composable
fun MobileSheetAction(
    label: String,
    glyph: StreamiaIconGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = AccentPink,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StreamiaIcon(glyph, tint = tint, size = 22.dp)
        Text(label, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Page mobile : contenu en haut, barre d'onglets flottante en bas. */
@Composable
fun MobileScaffold(selected: MobileTab, onSelect: (MobileTab) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        MobileBottomBar(selected, onSelect)
    }
}

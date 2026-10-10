package fr.streamia.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RadiusCard
import kotlinx.coroutines.yield

// Colonne des catégories partagée par le Direct et Films/Séries.

@Composable
internal fun CategoryRail(
    type: MediaType,
    categories: List<MediaCategory>,
    selectedCategoryId: String,
    favoriteCategories: Set<String>,
    lockedCategories: Set<String>,
    countFor: (MediaCategory) -> Int,
    onSelected: (MediaCategory) -> Unit,
    onToggleFavorite: (MediaCategory) -> Unit,
    requestInitialFocus: Boolean = true,
    // Incrémenté par l'appelant pour ramener le focus sur la catégorie sélectionnée (Gauche/Retour
    // depuis les chaînes ou la grille). 0 = aucune demande.
    focusSelectedRequest: Int = 0,
    onRight: (() -> Unit)? = null,
    // Le Direct affiche ce panneau par-dessus la vidéo plein écran déjà en cours de lecture : les
    // lignes au repos passent en transparent (le fond assombri du Column suffit à garder le texte
    // lisible) plutôt que de masquer la vidéo derrière un aplat opaque. VOD (l'autre appelant) garde
    // le panneau opaque par défaut, puisqu'il n'y a rien à voir derrière.
    translucent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val selectedFocus = remember(selectedCategoryId, type) { FocusRequester() }

    // Catégorie mise en favori (remontée en tête) ou retirée (revenue à sa place) : la liste la
    // suit jusqu'à sa nouvelle position et lui garde le focus, au lieu de la laisser hors écran.
    var movedCategoryKey by remember { mutableStateOf<String?>(null) }
    val movedFocus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(categories) {
        val key = movedCategoryKey ?: return@LaunchedEffect
        val index = categories.indexOfFirst { it.key == key }
        if (index >= 0) {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                // Deux catégories au-dessus restent visibles pour garder le contexte.
                listState.scrollToItem((index - 2).coerceAtLeast(0))
            }
            yield()
            runCatching { movedFocus.requestFocus() }
        }
        movedCategoryKey = null
    }

    // Retour au rail : si la liste a été défilée et que la catégorie sélectionnée n'est plus
    // composée, son FocusRequester n'est rattaché à rien et requestFocus() échouait en silence
    // (impossible de revenir sur les catégories). On la refait d'abord défiler à l'écran.
    androidx.compose.runtime.LaunchedEffect(focusSelectedRequest) {
        if (focusSelectedRequest == 0) return@LaunchedEffect
        val index = categories.indexOfFirst { it.id == selectedCategoryId }
        if (index < 0) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            // Deux catégories au-dessus restent visibles pour garder le contexte.
            listState.scrollToItem((index - 2).coerceAtLeast(0))
        }
        yield()
        // requestFocus(Enter) renvoie false (sans lever) tant que la ligne n'est pas rattachée :
        // on retente alors à la frame suivante, une fois le défilement appliqué.
        val focused = runCatching { selectedFocus.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
        if (!focused) {
            withFrameNanos { }
            runCatching { selectedFocus.requestFocus(FocusDirection.Enter) }
        }
    }

    androidx.compose.runtime.LaunchedEffect(type, categories.size, selectedCategoryId, requestInitialFocus) {
        val index = categories.indexOfFirst { it.id == selectedCategoryId }
        if (index >= 0) {
            yield()
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                listState.scrollToItem(index)
                yield()
            }
            if (requestInitialFocus) {
                runCatching { selectedFocus.requestFocus() }
            }
        }
    }

    val railContent: @Composable () -> Unit = {
      Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 3.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Catégories")
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(categories, key = MediaCategory::key) { category ->
                val virtual = category.id == Catalog.ALL_CATEGORY_ID || category.id in VIRTUAL_CATEGORY_IDS
                FocusableSurface(
                    onClick = { onSelected(category) },
                    onLongClick = if (virtual || type != MediaType.Live) null else ({
                        movedCategoryKey = category.key
                        onToggleFavorite(category)
                    }),
                    selected = selectedCategoryId == category.id,
                    idleBackground = if (translucent) Color.Transparent else DeepSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .onPreviewKeyEvent { event ->
                            if (
                                onRight != null &&
                                event.type == KeyEventType.KeyDown &&
                                event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                            ) {
                                onRight()
                                true
                            } else false
                        }
                        .then(if (category.id == selectedCategoryId) Modifier.focusRequester(selectedFocus) else Modifier)
                        .then(if (category.key == movedCategoryKey) Modifier.focusRequester(movedFocus) else Modifier),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!virtual && type == MediaType.Live && category.key in favoriteCategories) {
                            StreamiaIcon(StreamiaIconGlyph.Star, size = 18.dp)
                            Spacer(Modifier.width(5.dp))
                        }
                        if (!virtual && category.key in lockedCategories) {
                            StreamiaIcon(StreamiaIconGlyph.Lock, tint = FocusBlueBright, size = 12.dp)
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            category.name,
                            color = Ink,
                            fontSize = 15.sp,
                            fontWeight = if (selectedCategoryId == category.id) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(countFor(category).toString(), color = MutedInk, fontSize = 13.sp)
                    }
                }
            }
        }
      }
    }
    if (translucent) {
        Box(
            modifier
                .clip(RoundedCornerShape(RadiusCard))
                .background(Night.copy(alpha = 0.72f))
                .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(RadiusCard)),
        ) { railContent() }
    } else {
        GlassSurface(modifier = modifier) { railContent() }
    }
}

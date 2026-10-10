package fr.streamia.tv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.StreamiaIcon
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.FocusBlue
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.WarmSignal

/**
 * Organiser au doigt : catégories de Direct, Films ou Séries. Un appui long démarre la sélection
 * multiple ; la barre du bas verrouille, masque ou déplace les catégories cochées. Le déplacement
 * de contenus d'une catégorie à l'autre reste dans l'Organiser de la TV.
 */
@Composable
fun MobileOrganizerScreen(
    catalog: Catalog,
    hiddenCategories: Set<String>,
    lockedCategories: Set<String>,
    parentalControlEnabled: Boolean,
    onCategoryOrderChanged: (MediaType, List<String>) -> Unit,
    onToggleCategoryHidden: (MediaCategory) -> Unit,
    onToggleCategoryLocked: (MediaCategory) -> Unit,
    onBack: () -> Unit,
) {
    var type by remember { mutableStateOf(MediaType.Live) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember(type) { mutableStateOf(emptySet<String>()) }
    var order by remember(catalog, type) { mutableStateOf(catalog.categoriesFor(type).map(MediaCategory::key)) }
    var notice by remember { mutableStateOf<String?>(null) }
    val byKey = remember(catalog, type) { catalog.categoriesFor(type).associateBy(MediaCategory::key) }
    val categories = remember(order, byKey) { order.mapNotNull(byKey::get) }

    BackHandler {
        if (selecting) { selecting = false; selected = emptySet() } else onBack()
    }

    fun toggle(key: String) {
        selected = if (key in selected) selected - key else selected + key
    }

    fun move(direction: Int) {
        val list = order.toMutableList()
        val indices = if (direction < 0) list.indices else list.indices.reversed()
        for (i in indices) {
            val target = i + direction
            if (list[i] in selected && target in list.indices && list[target] !in selected) {
                val tmp = list[i]; list[i] = list[target]; list[target] = tmp
            }
        }
        order = list
        onCategoryOrderChanged(type, list)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MobileIconButton(StreamiaIconGlyph.ArrowBack, onClick = { if (selecting) { selecting = false; selected = emptySet() } else onBack() })
            Text(
                if (selecting) "${selected.size} sélectionnée(s)" else "Organiser",
                color = Ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Text(
                if (selected.size == categories.size && categories.isNotEmpty()) "Aucun" else "Tout",
                color = AccentPink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable {
                        selecting = true
                        selected = if (selected.size == categories.size) emptySet() else categories.map(MediaCategory::key).toSet()
                    }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
            )
        }
        MobileChipRow {
            listOf(MediaType.Live, MediaType.Movie, MediaType.Series).forEach { mediaType ->
                MobileChip(mediaType.displayName, type == mediaType, onClick = { type = mediaType; selecting = false })
            }
        }
        Spacer(Modifier.height(10.dp))
        notice?.let { MobileMessage(it) { notice = null } }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = MobileGutter, end = MobileGutter, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(categories, key = { it.key }) { category ->
                val isSelected = category.key in selected
                val hidden = category.key in hiddenCategories
                val locked = category.key in lockedCategories
                MobileCard(
                    Modifier.fillMaxWidth().height(60.dp),
                    onClick = {
                        if (selecting) toggle(category.key) else notice = "Appui long pour passer en sélection multiple."
                    },
                    onLongClick = { selecting = true; toggle(category.key) },
                ) {
                    Row(
                        Modifier.fillMaxSize().background(if (isSelected) FocusBlue else Color.Transparent).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (selecting) {
                            Box(
                                Modifier.size(24.dp).clip(CircleShape).background(if (isSelected) AccentPink else Color.Transparent)
                                    .then(Modifier.padding(0.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                StreamiaIcon(if (isSelected) StreamiaIconGlyph.CheckboxOn else StreamiaIconGlyph.CheckboxOff, tint = Ink, size = 24.dp)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(category.name, color = Ink.copy(alpha = if (hidden) 0.45f else 1f), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("${catalog.countIn(type, category.id)} contenus", color = MutedInk, fontSize = 12.sp)
                        }
                        if (locked) StreamiaIcon(StreamiaIconGlyph.Lock, tint = WarmSignal, size = 16.dp)
                        if (hidden) StreamiaIcon(StreamiaIconGlyph.EyeOff, tint = MutedInk, size = 16.dp)
                    }
                }
            }
        }
        if (selecting) {
            val enabled = selected.isNotEmpty()
            MobileActionBar(
                listOf(
                    Triple<String, StreamiaIconGlyph, () -> Unit>("Verrouiller", StreamiaIconGlyph.Lock, {
                        if (!parentalControlEnabled) {
                            notice = "Activez d'abord le contrôle parental (Paramètres)."
                        } else {
                            selected.mapNotNull(byKey::get).forEach(onToggleCategoryLocked)
                        }
                    }),
                    Triple<String, StreamiaIconGlyph, () -> Unit>("Masquer", StreamiaIconGlyph.EyeOff, { selected.mapNotNull(byKey::get).forEach(onToggleCategoryHidden) }),
                    Triple<String, StreamiaIconGlyph, () -> Unit>("Monter", StreamiaIconGlyph.ChevronUp, { move(-1) }),
                    Triple<String, StreamiaIconGlyph, () -> Unit>("Descendre", StreamiaIconGlyph.ChevronDown, { move(1) }),
                ),
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun MobileActionBar(actions: List<Triple<String, StreamiaIconGlyph, () -> Unit>>, enabled: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xF21E1E24))
            .padding(6.dp),
    ) {
        actions.forEach { (label, glyph, action) ->
            Column(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(enabled = enabled, onClick = action),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                StreamiaIcon(glyph, tint = Ink.copy(alpha = if (enabled) 1f else 0.4f), size = 19.dp)
                Spacer(Modifier.height(4.dp))
                Text(label, color = Ink.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

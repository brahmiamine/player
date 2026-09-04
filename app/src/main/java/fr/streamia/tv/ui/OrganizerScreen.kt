package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.TypeLabel
import fr.streamia.tv.ui.theme.TypeSectionTitle
import fr.streamia.tv.ui.theme.TypeScreenTitle
import java.text.Collator
import java.util.Locale

@Composable
fun OrganizerScreen(
    catalog: Catalog,
    hiddenCategories: Set<String>,
    onCategoryOrderChanged: (MediaType, List<String>) -> Unit,
    onToggleCategoryHidden: (MediaCategory) -> Unit,
    onMoveEntries: (Set<String>, String) -> Unit,
    onResetMoves: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var type by remember { mutableStateOf(MediaType.Live) }
    var sourceCategoryId by remember(type) { mutableStateOf(catalog.categoriesFor(type).firstOrNull()?.id) }
    var selectedCategoryKeys by remember(type) { mutableStateOf(emptySet<String>()) }
    var selectedEntryKeys by remember(type, sourceCategoryId) { mutableStateOf(emptySet<String>()) }
    var destinationCategoryId by remember(type) {
        mutableStateOf(catalog.categoriesFor(type).drop(1).firstOrNull()?.id ?: catalog.categoriesFor(type).firstOrNull()?.id)
    }
    var localCategoryOrder by remember(catalog, type) {
        mutableStateOf(catalog.categoriesFor(type).map(MediaCategory::key))
    }
    var locallyMovedEntryKeys by remember(catalog, type, sourceCategoryId) { mutableStateOf(emptySet<String>()) }
    val categoriesByKey = remember(catalog, type) { catalog.categoriesFor(type).associateBy(MediaCategory::key) }
    val categories = remember(localCategoryOrder, categoriesByKey) { localCategoryOrder.mapNotNull(categoriesByKey::get) }
    val sourceEntries = remember(catalog, type, sourceCategoryId, locallyMovedEntryKeys) {
        sourceCategoryId?.let { catalog.entriesIn(type, it) }.orEmpty()
            .filterNot { it.key in locallyMovedEntryKeys }
    }

    Column(Modifier.fillMaxSize().background(Night).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FocusableSurface(onClick = onBack, modifier = Modifier.width(120.dp).height(50.dp)) {
                Text("← Retour", color = Ink, fontSize = TypeLabel, modifier = Modifier.padding(horizontal = 14.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text("Organiser le catalogue", color = Ink, fontSize = TypeScreenTitle, fontWeight = HeadingWeight)
            Spacer(Modifier.weight(1f))
            MediaType.entries.forEach { mediaType ->
                FocusableSurface(
                    onClick = {
                        type = mediaType
                        sourceCategoryId = catalog.categoriesFor(mediaType).firstOrNull()?.id
                        destinationCategoryId = catalog.categoriesFor(mediaType).drop(1).firstOrNull()?.id
                        selectedCategoryKeys = emptySet()
                        selectedEntryKeys = emptySet()
                    },
                    selected = type == mediaType,
                    modifier = Modifier.width(115.dp).height(48.dp),
                ) {
                    Text(mediaType.displayName, color = Ink, fontSize = TypeLabel, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
                }
                Spacer(Modifier.width(7.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Sélection multiple : cochez plusieurs catégories pour les déplacer ensemble, ou plusieurs contenus pour les affecter à une autre catégorie.",
            color = MutedInk,
            fontSize = TypeLabel,
        )
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(420.dp).fillMaxHeight()) {
                SectionLabel("Catégories (${categories.size})")
                Spacer(Modifier.height(9.dp))
                // Avec des centaines/milliers de catégories, remonter/descendre pas à pas jusqu'au
                // bon endroit est beaucoup trop lent : ⇈/⇊ envoient directement la sélection en
                // tête/en fin de liste, et « Trier A→Z » réordonne tout d'un coup — la plupart des
                // besoins de tri manuel disparaissent une fois la liste déjà rangée par nom.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryMoveButton(
                        glyph = "↑",
                        contentDescription = "Monter la sélection d'une position",
                        enabled = selectedCategoryKeys.isNotEmpty(),
                        onClick = {
                            val ordered = moveSelected(categories, selectedCategoryKeys, -1)
                            localCategoryOrder = ordered.map(MediaCategory::key)
                            onCategoryOrderChanged(type, ordered.map(MediaCategory::key))
                        },
                    )
                    CategoryMoveButton(
                        glyph = "↓",
                        contentDescription = "Descendre la sélection d'une position",
                        enabled = selectedCategoryKeys.isNotEmpty(),
                        onClick = {
                            val ordered = moveSelected(categories, selectedCategoryKeys, 1)
                            localCategoryOrder = ordered.map(MediaCategory::key)
                            onCategoryOrderChanged(type, ordered.map(MediaCategory::key))
                        },
                    )
                    CategoryMoveButton(
                        glyph = "⇈",
                        contentDescription = "Envoyer la sélection tout en haut",
                        enabled = selectedCategoryKeys.isNotEmpty(),
                        onClick = {
                            val ordered = moveSelectedToEdge(categories, selectedCategoryKeys, toStart = true)
                            localCategoryOrder = ordered.map(MediaCategory::key)
                            onCategoryOrderChanged(type, ordered.map(MediaCategory::key))
                        },
                    )
                    CategoryMoveButton(
                        glyph = "⇊",
                        contentDescription = "Envoyer la sélection tout en bas",
                        enabled = selectedCategoryKeys.isNotEmpty(),
                        onClick = {
                            val ordered = moveSelectedToEdge(categories, selectedCategoryKeys, toStart = false)
                            localCategoryOrder = ordered.map(MediaCategory::key)
                            onCategoryOrderChanged(type, ordered.map(MediaCategory::key))
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    FocusableSurface(
                        onClick = {
                            val selectedCategories = categories.filter { it.key in selectedCategoryKeys }
                            val hideSelected = selectedCategories.any { it.key !in hiddenCategories }
                            selectedCategories
                                .filter { (it.key in hiddenCategories) == hideSelected }
                                .forEach(onToggleCategoryHidden)
                        },
                        enabled = selectedCategoryKeys.isNotEmpty(),
                        contentDescription = "Afficher ou masquer les catégories sélectionnées",
                        modifier = Modifier.width(126.dp).height(44.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val allHidden = selectedCategoryKeys.isNotEmpty() && selectedCategoryKeys.all { it in hiddenCategories }
                            Text(if (allHidden) "Afficher" else "Masquer", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    FocusableSurface(
                        onClick = {
                            val ordered = sortAlphabetically(categories)
                            localCategoryOrder = ordered.map(MediaCategory::key)
                            onCategoryOrderChanged(type, ordered.map(MediaCategory::key))
                        },
                        enabled = categories.size > 1,
                        contentDescription = "Trier toutes les catégories par ordre alphabétique",
                        modifier = Modifier.width(112.dp).height(44.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Trier A→Z", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(9.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(categories, key = MediaCategory::key) { category ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            FocusableSurface(
                                onClick = {
                                    sourceCategoryId = category.id
                                    selectedEntryKeys = emptySet()
                                    if (destinationCategoryId == category.id) {
                                        destinationCategoryId = categories.firstOrNull { it.id != category.id }?.id
                                    }
                                },
                                selected = sourceCategoryId == category.id,
                                modifier = Modifier.weight(1f).height(50.dp),
                            ) {
                                Column(Modifier.padding(horizontal = 13.dp)) {
                                    Text(category.name, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        (if (category.key in hiddenCategories) "Masquée · " else "") +
                                            catalog.countIn(type, category.id) + " éléments",
                                        color = if (category.key in hiddenCategories) FocusBlueBright else MutedInk,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                            FocusableSurface(
                                onClick = {
                                    selectedCategoryKeys = selectedCategoryKeys.toMutableSet().apply {
                                        if (!add(category.key)) remove(category.key)
                                    }
                                },
                                selected = category.key in selectedCategoryKeys,
                                modifier = Modifier.width(52.dp).height(50.dp),
                                contentDescription = if (category.key in selectedCategoryKeys) {
                                    "Désélectionner ${category.name}"
                                } else {
                                    "Sélectionner ${category.name}"
                                },
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    StreamiaIcon(
                                        if (category.key in selectedCategoryKeys) StreamiaIconGlyph.CheckboxOn else StreamiaIconGlyph.CheckboxOff,
                                        size = 18.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val sourceName = categories.firstOrNull { it.id == sourceCategoryId }?.name ?: "Catégorie"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(sourceName, color = Ink, fontSize = TypeSectionTitle, fontWeight = HeadingWeight, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${selectedEntryKeys.size} sélectionné(s)", color = FocusBlueBright, fontSize = TypeLabel)
                }
                Spacer(Modifier.height(9.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(sourceEntries, key = MediaEntry::key, contentType = { "organizer-entry" }) { entry ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            FocusableSurface(
                                onClick = {
                                    selectedEntryKeys = selectedEntryKeys.toMutableSet().apply {
                                        if (!add(entry.key)) remove(entry.key)
                                    }
                                },
                                selected = entry.key in selectedEntryKeys,
                                modifier = Modifier.weight(1f).height(50.dp),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.number.toString(), color = MutedInk, fontSize = 11.sp, modifier = Modifier.width(48.dp))
                                    Text(entry.displayName, color = Ink, fontSize = TypeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    StreamiaIcon(
                                        if (entry.key in selectedEntryKeys) StreamiaIconGlyph.CheckboxOn else StreamiaIconGlyph.CheckboxOff,
                                        size = 18.dp,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val destination = categories.firstOrNull { it.id == destinationCategoryId }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FocusableSurface(
                        onClick = { destinationCategoryId = previousDestination(categories, destinationCategoryId, sourceCategoryId) },
                        modifier = Modifier.width(62.dp).height(52.dp),
                        contentDescription = "Catégorie de destination précédente",
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            StreamiaIcon(StreamiaIconGlyph.ArrowBack, tint = Ink, size = 18.dp)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Destination", color = MutedInk, fontSize = 11.sp)
                        Text(destination?.name ?: "Choisir une catégorie", color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FocusableSurface(
                        onClick = { destinationCategoryId = nextDestination(categories, destinationCategoryId, sourceCategoryId) },
                        modifier = Modifier.width(62.dp).height(52.dp),
                        contentDescription = "Catégorie de destination suivante",
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            StreamiaIcon(StreamiaIconGlyph.ArrowForward, tint = Ink, size = 18.dp)
                        }
                    }
                    FocusableSurface(
                        onClick = {
                            locallyMovedEntryKeys = locallyMovedEntryKeys + selectedEntryKeys
                            destinationCategoryId?.let { onMoveEntries(selectedEntryKeys, it) }
                            selectedEntryKeys = emptySet()
                        },
                        enabled = selectedEntryKeys.isNotEmpty() && destinationCategoryId != null,
                        modifier = Modifier.width(190.dp).height(52.dp),
                    ) {
                        Text("Déplacer la sélection", color = Ink, fontSize = TypeLabel, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp))
                    }
                    FocusableSurface(
                        onClick = { onResetMoves(selectedEntryKeys); selectedEntryKeys = emptySet() },
                        enabled = selectedEntryKeys.isNotEmpty(),
                        modifier = Modifier.width(150.dp).height(52.dp),
                    ) {
                        Text("Réinitialiser", color = Ink, fontSize = TypeLabel, modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryMoveButton(
    glyph: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FocusableSurface(
        onClick = onClick,
        enabled = enabled,
        contentDescription = contentDescription,
        modifier = Modifier.width(48.dp).height(44.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(glyph, color = Ink, fontSize = 17.sp)
        }
    }
}

private fun moveSelected(categories: List<MediaCategory>, selected: Set<String>, delta: Int): List<MediaCategory> {
    val result = categories.toMutableList()
    if (delta < 0) {
        for (index in 1 until result.size) {
            if (result[index].key in selected && result[index - 1].key !in selected) {
                val temp = result[index - 1]
                result[index - 1] = result[index]
                result[index] = temp
            }
        }
    } else {
        for (index in result.lastIndex - 1 downTo 0) {
            if (result[index].key in selected && result[index + 1].key !in selected) {
                val temp = result[index + 1]
                result[index + 1] = result[index]
                result[index] = temp
            }
        }
    }
    return result
}

/**
 * Envoie la sélection tout en tête ou tout en fin de liste en un seul geste, plutôt que pas à pas
 * comme [moveSelected] : indispensable dès que la liste compte des centaines/milliers de catégories.
 * [Iterable.partition] conserve l'ordre relatif à l'intérieur de chacun des deux groupes.
 */
private fun moveSelectedToEdge(categories: List<MediaCategory>, selected: Set<String>, toStart: Boolean): List<MediaCategory> {
    if (selected.isEmpty()) return categories
    val (chosen, rest) = categories.partition { it.key in selected }
    return if (toStart) chosen + rest else rest + chosen
}

/**
 * Tri alphabétique conscient de la locale (accents/casse français inclus) plutôt qu'un simple
 * `sortedBy { it.name.lowercase() }`, qui ordonne par point de code Unicode et placerait par
 * exemple « Éducation » après tout ce qui commence par une lettre non accentuée.
 */
private fun sortAlphabetically(categories: List<MediaCategory>): List<MediaCategory> {
    val collator = Collator.getInstance(Locale.FRENCH)
    return categories.sortedWith(Comparator { a, b -> collator.compare(a.name, b.name) })
}

private fun previousDestination(categories: List<MediaCategory>, current: String?, source: String?): String? {
    val eligible = categories.filter { it.id != source }
    if (eligible.isEmpty()) return null
    val index = eligible.indexOfFirst { it.id == current }.takeIf { it >= 0 } ?: 0
    return eligible[Math.floorMod(index - 1, eligible.size)].id
}

private fun nextDestination(categories: List<MediaCategory>, current: String?, source: String?): String? {
    val eligible = categories.filter { it.id != source }
    if (eligible.isEmpty()) return null
    val index = eligible.indexOfFirst { it.id == current }.takeIf { it >= 0 } ?: -1
    return eligible[Math.floorMod(index + 1, eligible.size)].id
}

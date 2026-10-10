package fr.streamia.tv.ui

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaType

/** Construction unique et testable des catégories affichées dans le navigateur TV. */
internal fun buildBrowserCategories(
    type: MediaType,
    providerCategories: List<MediaCategory>,
    favoriteCategoryKeys: Set<String>,
    hasFavoriteEntries: Boolean,
    hasHistory: Boolean,
    /** Direct seulement : au moins une chaîne vue en vraie 3840 × 2160 (catégorie juste après « Tout »). */
    hasUhdEntries: Boolean = false,
): List<MediaCategory> = buildList {
    add(Catalog.allCategory(type))
    if (hasUhdEntries && type == MediaType.Live) add(MediaCategory(UHD_CATEGORY_ID, "UHD 4K", type))
    if (hasFavoriteEntries) add(MediaCategory("__favorites__", "★ Favoris", type))
    if (hasHistory) add(MediaCategory("__history__", "↺ Historique", type))
    val (favorite, other) = providerCategories.partition { it.key in favoriteCategoryKeys }
    addAll(favorite)
    addAll(other)
}

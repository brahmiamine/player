package fr.streamia.tv.domain

/**
 * Accès parental : une catégorie verrouillée reste inaccessible tant que le code n'a pas été
 * saisi pendant la session courante. Le verrou est identifié par [MediaCategory.key], soit
 * `"Type:id"` — la même clé que [Catalog.categoryKey].
 */
fun isParentalBlocked(
    entry: MediaEntry,
    lockedCategoryKeys: Set<String>,
    parentalControlEnabled: Boolean,
    parentalUnlocked: Boolean,
): Boolean = parentalControlEnabled &&
    !parentalUnlocked &&
    Catalog.categoryKey(entry.type, entry.categoryId) in lockedCategoryKeys

fun parentalExcludedCategoryIds(
    categories: List<MediaCategory>,
    lockedCategoryKeys: Set<String>,
    hiddenCategoryKeys: Set<String> = emptySet(),
    parentalControlEnabled: Boolean,
    parentalUnlocked: Boolean,
    type: MediaType? = null,
): Set<String> {
    val excludedKeys = if (parentalControlEnabled && !parentalUnlocked) {
        hiddenCategoryKeys + lockedCategoryKeys
    } else {
        hiddenCategoryKeys
    }
    if (excludedKeys.isEmpty()) return emptySet()
    return categories.asSequence()
        .filter { type == null || it.type == type }
        .filter { it.key in excludedKeys }
        .mapTo(mutableSetOf(), MediaCategory::id)
}

fun parentalLockedCategoryIds(
    categories: List<MediaCategory>,
    lockedCategoryKeys: Set<String>,
    parentalControlEnabled: Boolean,
    parentalUnlocked: Boolean,
    type: MediaType? = null,
): Set<String> {
    if (!parentalControlEnabled || parentalUnlocked || lockedCategoryKeys.isEmpty()) return emptySet()
    return categories.asSequence()
        .filter { type == null || it.type == type }
        .filter { it.key in lockedCategoryKeys }
        .mapTo(mutableSetOf(), MediaCategory::id)
}

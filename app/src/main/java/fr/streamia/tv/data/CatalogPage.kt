package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaEntry

/** One bounded SQLite page plus the cursor needed to continue without materializing the full list. */
data class CatalogPage(
    val entries: List<MediaEntry>,
    val nextOffset: Int,
    val hasMore: Boolean,
)

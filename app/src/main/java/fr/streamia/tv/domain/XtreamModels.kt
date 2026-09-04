package fr.streamia.tv.domain

data class ServerCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
)

enum class MediaType(
    val pathSegment: String,
    val displayName: String,
    val pluralName: String,
    val defaultExtension: String,
) {
    Live("live", "Direct", "chaînes", "ts"),
    Movie("movie", "Films", "films", "mp4"),
    Series("series", "Séries", "séries", "mp4"),
}

data class MediaCategory(
    val id: String,
    val name: String,
    val type: MediaType,
) {
    val key: String = "${type.name}:$id"
}

data class MediaEntry(
    val id: Int,
    val name: String,
    val displayName: String = name,
    val type: MediaType = MediaType.Live,
    val categoryId: String,
    val iconUrl: String?,
    val number: Int,
    val extension: String = type.defaultExtension,
    val tvgId: String? = null,
    val plot: String? = null,
    val rating: Double? = null,
    val playable: Boolean = type != MediaType.Series,
    val addedAtEpochSeconds: Long? = null,
) {
    val key: String = "${type.name}:$id"
}

data class MediaDetails(
    val media: MediaEntry,
    val plot: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val releaseDate: String? = null,
    val duration: String? = null,
    val country: String? = null,
    val backdropUrl: String? = null,
    val posterUrl: String? = media.iconUrl,
    val youtubeTrailer: String? = null,
    val rating: Double? = media.rating,
    val tmdbId: String? = null,
)

data class SeriesEpisode(
    val id: Int,
    val season: Int,
    val number: Int,
    val title: String,
    val extension: String,
    val iconUrl: String? = null,
    val plot: String? = null,
    val duration: String? = null,
    val rating: Double? = null,
    val releaseDate: String? = null,
)

data class SeriesDetails(
    val series: MediaEntry,
    val episodes: List<SeriesEpisode>,
    val details: MediaDetails? = null,
) {
    val seasons: List<Int> = episodes.map(SeriesEpisode::season).distinct().sorted()
    fun episodesIn(season: Int): List<SeriesEpisode> = episodes.filter { it.season == season }
}

data class EpgProgram(
    val title: String,
    val description: String?,
    val startEpochSeconds: Long?,
    val endEpochSeconds: Long?,
    val channelId: String? = null,
    val category: String? = null,
)

data class EpgChannel(
    val channelId: String,
    val displayName: String? = null,
    val iconUrl: String? = null,
    val programs: List<EpgProgram> = emptyList(),
)

data class EpgGuide(
    val channels: Map<String, EpgChannel>,
    val loadedAtEpochSeconds: Long = System.currentTimeMillis() / 1000,
) {
    private val channelsByAlias: Map<String, EpgChannel> = buildMap {
        channels.forEach { (key, channel) ->
            put(key.normalizedLookupKey(), channel)
            put(channel.channelId.normalizedLookupKey(), channel)
            channel.displayName?.takeIf(String::isNotBlank)?.let { put(it.normalizedLookupKey(), channel) }
        }
    }

    fun forEntry(entry: MediaEntry): List<EpgProgram> {
        val candidates = buildList {
            entry.tvgId?.takeIf(String::isNotBlank)?.let(::add)
            add(entry.name)
            add(entry.displayName)
        }
        for (candidate in candidates) {
            channels[candidate]?.programs?.let { if (it.isNotEmpty()) return it }
            channelsByAlias[candidate.normalizedLookupKey()]?.programs?.let { if (it.isNotEmpty()) return it }
        }
        return emptyList()
    }
}

private fun String.normalizedLookupKey(): String = trim().lowercase()

/**
 * Corrige un décalage horaire du flux XMLTV (fréquent : le fournisseur publie parfois dans un
 * fuseau différent de celui attendu) en décalant les horaires de tous les programmes de [offsetHours]
 * heures. Appliquée à l'affichage plutôt qu'au chargement pour rester réactive à un changement du
 * réglage sans devoir recharger tout le guide.
 */
fun EpgGuide.withTimeOffset(offsetHours: Int): EpgGuide {
    if (offsetHours == 0) return this
    val shiftSeconds = offsetHours * 3_600L
    return copy(
        channels = channels.mapValues { (_, channel) ->
            channel.copy(
                programs = channel.programs.map { program ->
                    program.copy(
                        startEpochSeconds = program.startEpochSeconds?.plus(shiftSeconds),
                        endEpochSeconds = program.endEpochSeconds?.plus(shiftSeconds),
                    )
                },
            )
        },
    )
}

data class AccountInfo(
    val username: String,
    val status: String,
    val expiresAtEpochSeconds: Long?,
    val activeConnections: Int?,
    val maximumConnections: Int?,
)

data class Catalog(
    val categories: List<MediaCategory>,
    val entries: List<MediaEntry>,
    val account: AccountInfo? = null,
    /** Total provider rows per section. Empty means this is a legacy/full in-memory catalogue. */
    val totalCounts: Map<MediaType, Int> = emptyMap(),
    /** Provider rows per category, keyed with [MediaCategory.key]. */
    val categoryCounts: Map<String, Int> = emptyMap(),
    /** Categories for which at least one database page is currently materialized in [entries]. */
    val loadedCategoryKeys: Set<String> = emptySet(),
) {
    /**
     * Index only the entries that are actually materialized. A SQLite-backed catalogue can carry
     * accurate metadata for hundreds of thousands of provider rows while keeping only the current
     * browser page, favourites/recent entries and the active playback context in memory.
     */
    private val navigableEntries = entries.filterNot(MediaEntry::isVisualSeparator)
    private val entriesBySection = navigableEntries.groupBy(MediaEntry::type)
    private val entriesBySectionAndCategory = entriesBySection.mapValues { (_, sectionEntries) ->
        sectionEntries.groupBy(MediaEntry::categoryId)
    }
    private val categoriesBySection = categories.groupBy(MediaCategory::type)
    private val entriesByKey = navigableEntries.associateBy(MediaEntry::key)
    private val searchIndex by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        navigableEntries.map { entry ->
            IndexedEntry(entry, "${entry.name}\u0000${entry.displayName}\u0000${entry.tvgId.orEmpty()}".lowercase())
        }
    }
    private val lazyMetadata = totalCounts.isNotEmpty() || categoryCounts.isNotEmpty() || loadedCategoryKeys.isNotEmpty()

    fun categoriesFor(type: MediaType): List<MediaCategory> = categoriesBySection[type].orEmpty()

    fun entriesFor(type: MediaType): List<MediaEntry> = entriesBySection[type].orEmpty()

    fun entriesIn(type: MediaType, categoryId: String): List<MediaEntry> =
        if (categoryId == ALL_CATEGORY_ID) entriesFor(type)
        else entriesBySectionAndCategory[type]?.get(categoryId).orEmpty()

    fun entry(key: String): MediaEntry? = entriesByKey[key]

    fun count(type: MediaType): Int = totalCounts[type] ?: (entriesBySection[type]?.size ?: 0)

    fun countIn(type: MediaType, categoryId: String): Int =
        if (categoryId == ALL_CATEGORY_ID) count(type)
        else categoryCounts[categoryKey(type, categoryId)] ?: entriesIn(type, categoryId).size

    fun isCategoryLoaded(type: MediaType, categoryId: String): Boolean =
        !lazyMetadata || categoryKey(type, categoryId) in loadedCategoryKeys

    /**
     * Merges one freshly loaded database page into this lightweight catalogue without touching the
     * rows already materialized elsewhere (favourites, recents, other categories' pages). Existing
     * entries sharing a key with [newEntries] are replaced so a re-fetched row (e.g. after refresh)
     * reflects the latest provider data; [totalCounts]/[categoryCounts] stay authoritative and are
     * carried over unchanged since they describe the full provider catalogue, not what's in memory.
     */
    fun withMaterializedEntries(
        newEntries: List<MediaEntry>,
        loadedType: MediaType,
        loadedCategoryId: String,
    ): Catalog {
        val merged = LinkedHashMap<String, MediaEntry>(entries.size + newEntries.size)
        entries.forEach { merged[it.key] = it }
        newEntries.forEach { merged[it.key] = it }
        return copy(
            entries = merged.values.toList(),
            loadedCategoryKeys = loadedCategoryKeys + categoryKey(loadedType, loadedCategoryId),
        )
    }

    /**
     * Merges an entire section (e.g. every Live channel) fetched in one database query and marks
     * every category of [type] — including "Tout" — as loaded, so later per-category selections in
     * that section reuse it instead of re-querying. Used for the Live section, which stays small
     * enough to hydrate eagerly right after startup so zapping, the channel-number jump and the EPG
     * guide can rely on the full channel list without each patching a different lazy-loading gap.
     */
    fun withFullSectionMaterialized(newEntries: List<MediaEntry>, type: MediaType): Catalog {
        val merged = LinkedHashMap<String, MediaEntry>(entries.size + newEntries.size)
        entries.forEach { merged[it.key] = it }
        newEntries.forEach { merged[it.key] = it }
        val sectionKeys = categoriesFor(type).map { categoryKey(type, it.id) } + categoryKey(type, ALL_CATEGORY_ID)
        return copy(entries = merged.values.toList(), loadedCategoryKeys = loadedCategoryKeys + sectionKeys)
    }

    fun search(query: String, type: MediaType? = null, limit: Int = 500): List<MediaEntry> {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return emptyList()
        return searchIndex.asSequence()
            .filter { type == null || it.entry.type == type }
            .filter { it.text.contains(needle) }
            .take(limit)
            .map(IndexedEntry::entry)
            .toList()
    }

    companion object {
        const val ALL_CATEGORY_ID = "__all__"
        fun allCategory(type: MediaType) = MediaCategory(ALL_CATEGORY_ID, "Tout", type)
        fun categoryKey(type: MediaType, categoryId: String): String = "${type.name}:$categoryId"
    }
}

private data class IndexedEntry(val entry: MediaEntry, val text: String)

private val VISUAL_SEPARATOR_NAME = Regex("^\\s*#{2,}.*#{2,}\\s*$")

/** Les fournisseurs utilisent parfois de fausses chaînes ### ... ### comme séparateurs visuels. */
fun MediaEntry.isVisualSeparator(): Boolean = VISUAL_SEPARATOR_NAME.matches(displayName) ||
    VISUAL_SEPARATOR_NAME.matches(name)

fun List<MediaEntry>.adjacentTo(currentKey: String, delta: Int): MediaEntry? {
    if (isEmpty()) return null
    val currentIndex = indexOfFirst { it.key == currentKey }.takeIf { it >= 0 } ?: 0
    return this[Math.floorMod(currentIndex + delta, size)]
}

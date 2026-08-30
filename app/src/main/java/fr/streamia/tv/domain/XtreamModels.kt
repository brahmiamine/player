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
    val key: String get() = "${type.name}:$id"
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
    val key: String get() = "${type.name}:$id"
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
    fun forEntry(entry: MediaEntry): List<EpgProgram> {
        val candidates = buildList {
            entry.tvgId?.takeIf(String::isNotBlank)?.let(::add)
            add(entry.name)
            add(entry.displayName)
        }
        for (candidate in candidates) {
            channels[candidate]?.programs?.let { if (it.isNotEmpty()) return it }
            channels.values.firstOrNull {
                it.channelId.equals(candidate, ignoreCase = true) ||
                    it.displayName.equals(candidate, ignoreCase = true)
            }?.programs?.let { if (it.isNotEmpty()) return it }
        }
        return emptyList()
    }
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
) {
    private val entriesBySectionAndCategory = entries.groupBy { "${it.type.name}:${it.categoryId}" }
    private val entriesBySection = entries.groupBy(MediaEntry::type)
    private val categoriesBySection = categories.groupBy(MediaCategory::type)
    private val entriesByKey = entries.associateBy(MediaEntry::key)

    fun categoriesFor(type: MediaType): List<MediaCategory> = categoriesBySection[type].orEmpty()

    fun entriesFor(type: MediaType): List<MediaEntry> = entriesBySection[type].orEmpty()

    fun entriesIn(type: MediaType, categoryId: String): List<MediaEntry> =
        if (categoryId == ALL_CATEGORY_ID) entriesFor(type)
        else entriesBySectionAndCategory["${type.name}:$categoryId"].orEmpty()

    fun entry(key: String): MediaEntry? = entriesByKey[key]

    fun count(type: MediaType): Int = entriesBySection[type]?.size ?: 0

    fun search(query: String, type: MediaType? = null, limit: Int = 500): List<MediaEntry> {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return emptyList()
        return entries.asSequence()
            .filter { type == null || it.type == type }
            .filter {
                it.name.lowercase().contains(needle) ||
                    it.displayName.lowercase().contains(needle) ||
                    it.tvgId?.lowercase()?.contains(needle) == true
            }
            .take(limit)
            .toList()
    }

    companion object {
        const val ALL_CATEGORY_ID = "__all__"
        fun allCategory(type: MediaType) = MediaCategory(ALL_CATEGORY_ID, "Tout", type)
    }
}

fun List<MediaEntry>.adjacentTo(currentKey: String, delta: Int): MediaEntry? {
    if (isEmpty()) return null
    val currentIndex = indexOfFirst { it.key == currentKey }.takeIf { it >= 0 } ?: 0
    return this[Math.floorMod(currentIndex + delta, size)]
}

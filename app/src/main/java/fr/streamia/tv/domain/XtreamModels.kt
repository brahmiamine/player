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
) {
    val key: String = "${type.name}:$id"
}

data class SeriesEpisode(
    val id: Int,
    val season: Int,
    val number: Int,
    val title: String,
    val extension: String,
    val iconUrl: String? = null,
    val plot: String? = null,
    val duration: String? = null,
)

data class SeriesDetails(
    val series: MediaEntry,
    val episodes: List<SeriesEpisode>,
) {
    val seasons: List<Int> = episodes.map(SeriesEpisode::season).distinct().sorted()
    fun episodesIn(season: Int): List<SeriesEpisode> = episodes.filter { it.season == season }
}

data class EpgProgram(
    val title: String,
    val description: String?,
    val startEpochSeconds: Long?,
    val endEpochSeconds: Long?,
)

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

    fun categoriesFor(type: MediaType): List<MediaCategory> = categoriesBySection[type].orEmpty()

    fun entriesFor(type: MediaType): List<MediaEntry> = entriesBySection[type].orEmpty()

    fun entriesIn(type: MediaType, categoryId: String): List<MediaEntry> =
        if (categoryId == ALL_CATEGORY_ID) entriesFor(type)
        else entriesBySectionAndCategory["${type.name}:$categoryId"].orEmpty()

    fun count(type: MediaType): Int = entriesBySection[type]?.size ?: 0

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

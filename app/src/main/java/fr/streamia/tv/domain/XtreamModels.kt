package fr.streamia.tv.domain

data class ServerCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
)

data class LiveCategory(
    val id: String,
    val name: String,
)

data class LiveChannel(
    val id: Int,
    val name: String,
    val categoryId: String,
    val iconUrl: String?,
    val number: Int,
    val epgChannelId: String? = null,
)

data class AccountInfo(
    val username: String,
    val status: String,
    val expiresAtEpochSeconds: Long?,
    val activeConnections: Int?,
    val maximumConnections: Int?,
)

data class Catalog(
    val categories: List<LiveCategory>,
    val channels: List<LiveChannel>,
    val account: AccountInfo? = null,
) {
    private val channelsByCategory = channels.groupBy(LiveChannel::categoryId)

    fun channelsIn(categoryId: String): List<LiveChannel> =
        if (categoryId == ALL_CATEGORY_ID) channels else channelsByCategory[categoryId].orEmpty()

    companion object {
        const val ALL_CATEGORY_ID = "__all__"
        val AllCategory = LiveCategory(ALL_CATEGORY_ID, "Toutes les chaînes")
    }
}

fun List<LiveChannel>.adjacentTo(currentId: Int, delta: Int): LiveChannel? {
    if (isEmpty()) return null
    val currentIndex = indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
    return this[Math.floorMod(currentIndex + delta, size)]
}

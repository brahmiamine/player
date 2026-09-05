package fr.streamia.tv.ui

import fr.streamia.tv.recommendation.RecommendationRowKind

enum class ContentReturnOrigin {
    Browser,
    Home,
    Search,
    LiveMatches,
}

data class ContentReturnContext(
    val origin: ContentReturnOrigin,
    val homeRowKey: String? = null,
    val itemKey: String? = null,
    val liveMatchKey: String? = null,
) {
    fun destinationScreen(): StreamiaScreen = when (origin) {
        ContentReturnOrigin.Browser -> StreamiaScreen.Browser
        ContentReturnOrigin.Home -> StreamiaScreen.Home
        ContentReturnOrigin.Search -> StreamiaScreen.Search
        ContentReturnOrigin.LiveMatches -> StreamiaScreen.LiveMatches
    }

    companion object {
        fun browser(itemKey: String? = null) = ContentReturnContext(
            origin = ContentReturnOrigin.Browser,
            itemKey = itemKey,
        )

        fun home(rowKey: String, itemKey: String) = ContentReturnContext(
            origin = ContentReturnOrigin.Home,
            homeRowKey = rowKey,
            itemKey = itemKey,
        )

        fun search(itemKey: String) = ContentReturnContext(
            origin = ContentReturnOrigin.Search,
            itemKey = itemKey,
        )

        fun liveMatches(matchKey: String, itemKey: String) = ContentReturnContext(
            origin = ContentReturnOrigin.LiveMatches,
            itemKey = itemKey,
            liveMatchKey = matchKey,
        )
    }
}

internal object HomeRowKey {
    const val Resume = "resume"
    const val Favorites = "favorites"
    const val LiveMatches = "matches-live"
    const val UpcomingMatches = "matches-next"

    fun recommendation(kind: RecommendationRowKind): String = "recommendation:$kind"
}

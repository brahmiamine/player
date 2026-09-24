package fr.streamia.tv.ui

import fr.streamia.tv.recommendation.RecommendationRowKind

enum class ContentReturnOrigin {
    Browser,
    Home,
    Search,
    LiveMatches,
    Epg,
}

/**
 * Carte de l'accueil à refocaliser au retour, pour que « Retour » depuis un écran ouvert via une
 * tuile (TV en direct, Films, Séries, Recherche, Guide TV, Paramètres…) ramène le focus sur cette
 * même tuile plutôt que sur la première rangée.
 */
enum class HomeFocusTarget {
    Live,
    Movies,
    Series,
    Search,
    Guide,
    Settings,
    Refresh,
    LiveMatches,
    ChangePlaylist,
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
        ContentReturnOrigin.Epg -> StreamiaScreen.Epg
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

        fun epg(itemKey: String) = ContentReturnContext(
            origin = ContentReturnOrigin.Epg,
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
    const val LiveMatches = "live-matches"
    const val RecentChannels = "recent-channels"
    const val TvProgrammeNow = "tv-programme-fr-live"
    const val TvProgrammeTonight = "tv-programme-fr-tonight"
    const val BeinSportsNow = "bein-sports-live"
    const val BeinSportsNext = "bein-sports-next"
    const val UkGuideNow = "uk-guide-live"
    const val UkGuideNext = "uk-guide-next"

    fun recommendation(kind: RecommendationRowKind): String = "recommendation:$kind"
}

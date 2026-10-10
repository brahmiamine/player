package fr.streamia.tv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.recommendation.RecommendationRow
import fr.streamia.tv.recommendation.RecommendationRowKind
import fr.streamia.tv.recommendation.RecommendationRowKind.*

// Les rangées de recommandations regroupées : « Pour vous », « Nouveautés », « Tendances » (une bascule par groupe).

internal enum class RecoGroup(val key: String, val title: String, val kinds: List<RecommendationRowKind>) {
    ForYou("reco-for-you", "Pour vous", listOf(RecommendationRowKind.ForYou, Discover, BecauseYouWatched)),
    New("reco-new", "Nouveautés", listOf(RecentlyAdded, RecentReleases)),
    Trending(
        "reco-trending", "Tendances",
        listOf(JustWatchTopMoviesWeek, JustWatchTopSeriesWeek, JustWatchPopularMovies, JustWatchPopularSeries, JustWatchNewMovies, JustWatchNewSeries),
    ),
}

internal fun RecoGroup.rowsIn(all: List<RecommendationRow>): List<RecommendationRow> =
    kinds.mapNotNull { kind -> all.firstOrNull { it.kind == kind } }

/** Groupe d'un type de rangée, pour retrouver sa rangée d'accueil au retour d'un contenu. */
internal fun recoGroupOf(kind: RecommendationRowKind): RecoGroup = RecoGroup.entries.first { kind in it.kinds }

private fun primaryLabel(row: RecommendationRow): String = when (row.kind) {
    RecommendationRowKind.ForYou -> "Pour vous"
    Discover -> "Découvrir"
    BecauseYouWatched -> "Parce que vous avez regardé"
    RecentlyAdded -> "Ajouts récents"
    RecentReleases -> "Sorties récentes"
    JustWatchTopMoviesWeek, JustWatchPopularMovies, JustWatchNewMovies -> "Films"
    JustWatchTopSeriesWeek, JustWatchPopularSeries, JustWatchNewSeries -> "Séries"
}

private fun secondaryLabel(row: RecommendationRow): String? = when (row.kind) {
    JustWatchTopMoviesWeek, JustWatchTopSeriesWeek -> "Cette semaine"
    JustWatchPopularMovies, JustWatchPopularSeries -> "Populaires"
    JustWatchNewMovies, JustWatchNewSeries -> "Nouveautés"
    else -> null
}

@Composable
internal fun RecoGroupRow(
    group: RecoGroup,
    rows: List<RecommendationRow>,
    aiReasons: Map<String, String>,
    restoreRowKey: String?,
    restoreItemKey: String?,
    onOpen: (MediaEntry, String) -> Unit,
) {
    if (rows.isEmpty()) return
    var selectedKind by remember {
        mutableStateOf(rows.firstOrNull { HomeRowKey.recommendation(it.kind) == restoreRowKey }?.kind ?: rows.first().kind)
    }
    val row = rows.firstOrNull { it.kind == selectedKind } ?: rows.first()
    val primary = primaryLabel(row)
    val primaries = rows.map { primaryLabel(it) }.distinct()
    val sameFamily = rows.filter { primaryLabel(it) == primary }
    val seconds = sameFamily.mapNotNull(::secondaryLabel)
    val rowKey = HomeRowKey.recommendation(row.kind)

    Column(Modifier.fillMaxWidth()) {
        TabbedRowHeader(
            title = group.title,
            tabs = primaries,
            selected = primaries.indexOf(primary),
            onSelect = { i ->
                val family = rows.filter { primaryLabel(it) == primaries[i] }
                selectedKind = (family.firstOrNull { secondaryLabel(it) == secondaryLabel(row) } ?: family.first()).kind
            },
            secondTabs = seconds,
            secondSelected = seconds.indexOf(secondaryLabel(row)),
            onSelectSecond = { i -> selectedKind = sameFamily.first { secondaryLabel(it) == seconds[i] }.kind },
        )
        HomeRecommendationRow(
            row = row,
            aiReasons = aiReasons,
            firstFocusRequester = null,
            restoreItemKey = restoreItemKey.takeIf { restoreRowKey == rowKey },
            showTitle = false,
            onOpenRecommendation = { entry -> onOpen(entry, rowKey) },
        )
    }
}

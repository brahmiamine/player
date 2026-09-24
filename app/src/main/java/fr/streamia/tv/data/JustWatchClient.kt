package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaType
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

internal data class TrendingTitle(val type: MediaType, val title: String, val originalTitle: String?, val year: Int?)

/** Sections de la page d'accueil JustWatch reprises sur l'accueil Streamia. */
enum class JustWatchSection(val type: MediaType, val title: String) {
    TopMoviesWeek(MediaType.Movie, "Top 10 des films de la semaine"),
    TopSeriesWeek(MediaType.Series, "Top 10 des séries de la semaine"),
    PopularMovies(MediaType.Movie, "Films populaires"),
    PopularSeries(MediaType.Series, "Séries populaires"),
    NewMovies(MediaType.Movie, "Nouveaux films"),
    NewSeries(MediaType.Series, "Nouvelles séries"),
}

/**
 * JustWatch France (API GraphQL non officielle, sans clé, usage personnel), dans l'ordre JustWatch.
 * « Nouveautés » = arrivées sur une plateforme de streaming ces [NEW_DAYS] derniers jours (pour
 * les séries : nouvelles saisons), pas forcément des sorties récentes.
 */
internal class JustWatchClient {
    fun titles(section: JustWatchSection): List<TrendingTitle> {
        val objectType = if (section.type == MediaType.Series) "SHOW" else "MOVIE"
        return when (section) {
            JustWatchSection.TopMoviesWeek, JustWatchSection.TopSeriesWeek -> edges(
                "streamingCharts(country:FR,first:10,filter:{category:WEEKLY_POPULARITY_SAME_CONTENT_TYPE,objectType:$objectType})" +
                    "{edges{node{$MOVIE_OR_SHOW}}}",
                "streamingCharts",
                section.type,
            )
            JustWatchSection.PopularMovies, JustWatchSection.PopularSeries -> edges(
                "popularTitles(country:FR,first:$POPULAR_COUNT,sortBy:POPULAR,filter:{objectTypes:[$objectType]})" +
                    "{edges{node{$MOVIE_OR_SHOW}}}",
                "popularTitles",
                section.type,
            )
            JustWatchSection.NewMovies, JustWatchSection.NewSeries -> (0L until NEW_DAYS).flatMap { daysAgo ->
                edges(
                    "newTitles(country:FR,date:\"${LocalDate.now().minusDays(daysAgo)}\",first:$NEW_COUNT_PER_DAY," +
                        "filter:{objectTypes:[$objectType]}){edges{node{$MOVIE_OR_SHOW ... on Season{show{$MOVIE_OR_SHOW}}}}}",
                    "newTitles",
                    section.type,
                )
            }.distinctBy { it.title }
        }
    }

    private fun edges(query: String, field: String, type: MediaType): List<TrendingTitle> {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Content-Type", "application/json")
        val body = try {
            connection.outputStream.use { it.write(JSONObject().put("query", "{$query}").toString().toByteArray()) }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val edges = JSONObject(body).getJSONObject("data").getJSONObject(field).getJSONArray("edges")
        return (0 until edges.length()).mapNotNull { i ->
            val node = edges.getJSONObject(i).optJSONObject("node") ?: return@mapNotNull null
            // Une nouvelle saison renvoie la série parente dans « show ».
            val content = (node.optJSONObject("show") ?: node).optJSONObject("content") ?: return@mapNotNull null
            TrendingTitle(
                type = type,
                title = content.optString("title").ifBlank { return@mapNotNull null },
                originalTitle = content.optString("originalTitle").takeIf(String::isNotBlank),
                year = content.optInt("originalReleaseYear").takeIf { it > 0 },
            )
        }
    }

    private companion object {
        const val ENDPOINT = "https://apis.justwatch.com/graphql"
        const val POPULAR_COUNT = 60
        const val NEW_DAYS = 7L
        const val NEW_COUNT_PER_DAY = 100
        const val MOVIE_OR_SHOW = "... on MovieOrShow{content(country:FR,language:\"fr\"){title originalTitle originalReleaseYear}}"
    }
}

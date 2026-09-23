package fr.streamia.tv.recommendation

import android.content.Context
import java.util.zip.GZIPInputStream

/**
 * « Les spectateurs qui ont aimé ce film ont aussi aimé… » : 30 voisins par film, calculés hors
 * ligne à partir des 32 millions de notes MovieLens (ml-32m, usage personnel non commercial —
 * script : tools/movielens_neighbors.py), indexés par TMDB ID. Films uniquement (~11 000).
 */
internal class MovieLensNeighbors(private val load: () -> Map<Int, IntArray>) {
    private val neighbours by lazy { runCatching(load).getOrDefault(emptyMap()) }

    /** TMDB ID des films les plus proches, du plus proche au moins proche. */
    fun of(tmdbId: String?): IntArray? = tmdbId?.trim()?.toIntOrNull()?.let(neighbours::get)

    companion object {
        fun fromAssets(context: Context): MovieLensNeighbors {
            val appContext = context.applicationContext
            return MovieLensNeighbors {
                GZIPInputStream(appContext.assets.open("movielens_similar.txt.gz")).bufferedReader().useLines { lines ->
                    lines.mapNotNull { line ->
                        val source = line.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
                        source to line.substringAfter(':').split(',').mapNotNull(String::toIntOrNull).toIntArray()
                    }.toMap()
                }
            }
        }
    }
}

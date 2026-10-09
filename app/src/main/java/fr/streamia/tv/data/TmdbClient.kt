package fr.streamia.tv.data

import fr.streamia.tv.net.HttpClients
import fr.streamia.tv.BuildConfig
import fr.streamia.tv.domain.MediaType
import org.json.JSONArray
import org.json.JSONObject

/** Titre recommandé par TMDB, à retrouver ensuite dans le catalogue. */
internal data class TmdbTitle(val id: Int, val title: String, val originalTitle: String?, val year: Int?)

/**
 * Ce que TMDB sait d'un contenu, toujours en anglais : sert à comparer les contenus dans une même
 * langue, quel que soit le résumé (arabe, turc…) fourni par le fournisseur IPTV.
 */
internal data class TmdbInfo(
    val overview: String?,
    val genres: List<String>,
    val keywords: List<String>,
    val recommendations: List<TmdbTitle>,
)

/**
 * API TMDB v3 (gratuite pour un usage personnel non commercial, jeton de lecture). Une seule requête
 * par contenu : résumé, genres, mots-clés et recommandations. La liste « similar » de TMDB (simple
 * genre + mots-clés, très bruitée) n'est pas utilisée.
 */
internal class TmdbClient(private val token: String = BuildConfig.TMDB_TOKEN) {
    val enabled: Boolean get() = token.isNotBlank()

    /** `null` si TMDB ne connaît pas cet identifiant (404) ; lève une exception sur erreur réseau. */
    fun info(type: MediaType, tmdbId: String): TmdbInfo? {
        val path = if (type == MediaType.Series) "tv" else "movie"
        val body = HttpClients.execute(
            "$BASE/$path/$tmdbId?language=en-US&append_to_response=recommendations,keywords",
            mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"),
            connectTimeoutMs = 10_000,
            readTimeoutMs = 15_000,
        ).use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw java.io.IOException("TMDB a répondu avec le code ${response.code}.")
            response.body?.bytes()?.toString(Charsets.UTF_8).orEmpty()
        }
        return parseTmdbInfo(JSONObject(body))
    }

    /** Identifiant TMDB du premier résultat pour ce titre (sert à la recherche de sous-titres) ; null si inconnu. */
    fun findId(title: String, year: Int?, series: Boolean): String? {
        val path = if (series) "tv" else "movie"
        val yearParam = year?.let { "&${if (series) "first_air_date_year" else "year"}=$it" }.orEmpty()
        val body = HttpClients.getText(
            "$BASE/search/$path?query=${java.net.URLEncoder.encode(title, "UTF-8")}$yearParam",
            mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"),
            connectTimeoutMs = 8_000,
            readTimeoutMs = 10_000,
        )
        return JSONObject(body).optJSONArray("results")?.optJSONObject(0)?.optInt("id", -1)?.takeIf { it > 0 }?.toString()
    }

    private companion object {
        const val BASE = "https://api.themoviedb.org/3"
    }
}

internal fun parseTmdbInfo(json: JSONObject): TmdbInfo {
    // Films : keywords.keywords ; séries : keywords.results.
    val keywordArray = json.optJSONObject("keywords")?.let { it.optJSONArray("keywords") ?: it.optJSONArray("results") }
    return TmdbInfo(
        overview = json.optString("overview").takeIf(String::isNotBlank),
        genres = json.optJSONArray("genres").names(),
        keywords = keywordArray.names(),
        recommendations = json.optJSONObject("recommendations")?.optJSONArray("results").objects().mapNotNull { item ->
            val id = item.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
            val title = (item.optString("title").ifBlank { item.optString("name") }).takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val original = item.optString("original_title").ifBlank { item.optString("original_name") }
            val date = item.optString("release_date").ifBlank { item.optString("first_air_date") }
            TmdbTitle(id, title, original.takeIf { it.isNotBlank() && it != title }, date.take(4).toIntOrNull())
        },
    )
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.names(): List<String> = objects().mapNotNull { it.optString("name").takeIf(String::isNotBlank) }

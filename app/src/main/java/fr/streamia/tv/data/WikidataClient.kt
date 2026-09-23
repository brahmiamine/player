package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaType
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sagas et franchises depuis Wikidata (gratuit, sans clé) à partir des TMDB ID fournis par le
 * fournisseur IPTV : « fait partie de la série » (P179), « franchise » (P8345) et « d'après » (P144 —
 * remakes et adaptations d'une même œuvre). Les pages de classement (« 100 meilleurs films… »),
 * parfois rangées en P179, sont exclues.
 */
internal class WikidataClient {
    /** TMDB ID → [(Q-id, libellé)] ; les ID absents de Wikidata n'apparaissent pas dans le résultat. */
    fun sagas(type: MediaType, tmdbIds: Collection<String>): Map<String, List<Pair<String, String>>> {
        if (tmdbIds.isEmpty()) return emptyMap()
        val property = if (type == MediaType.Series) "P4983" else "P4947"
        val values = tmdbIds.filter { id -> id.all(Char::isDigit) }.joinToString(" ") { "\"$it\"" }
        val query = """
            SELECT ?tmdb ?group ?groupLabel WHERE {
              VALUES ?tmdb { $values }
              ?item wdt:$property ?tmdb .
              { ?item wdt:P179 ?group } UNION { ?item wdt:P8345 ?group } UNION { ?item wdt:P144 ?group }
              MINUS { ?group wdt:P31 wd:Q13406463 }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,en". }
            }
        """.trimIndent()
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Accept", "application/sparql-results+json")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        // Wikidata exige un User-Agent identifiable.
        connection.setRequestProperty("User-Agent", "StreamiaTV/1.0 (personal Android TV player)")
        val body = try {
            connection.outputStream.use { it.write("query=${URLEncoder.encode(query, "UTF-8")}".toByteArray()) }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val bindings = JSONObject(body).getJSONObject("results").getJSONArray("bindings")
        val result = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for (i in 0 until bindings.length()) {
            val row = bindings.getJSONObject(i)
            val tmdb = row.getJSONObject("tmdb").getString("value")
            val qid = row.getJSONObject("group").getString("value").substringAfterLast('/')
            val label = row.optJSONObject("groupLabel")?.optString("value").orEmpty().ifBlank { qid }
            val groups = result.getOrPut(tmdb) { mutableListOf() }
            if (groups.none { it.first == qid }) groups += qid to label
        }
        return result
    }

    private companion object {
        const val ENDPOINT = "https://query.wikidata.org/sparql"
    }
}

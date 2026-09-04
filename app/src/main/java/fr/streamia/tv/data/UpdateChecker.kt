package fr.streamia.tv.data

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

data class ReleaseInfo(
    val version: String,
    val htmlUrl: String,
    val notes: String,
)

sealed interface UpdateCheckResult {
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class UpdateAvailable(val release: ReleaseInfo, val currentVersion: String) : UpdateCheckResult
    /** Aucune version taguée (v1.2.3) publiée pour l'instant — seule la build continue "latest" existe. */
    data object NoTaggedRelease : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * Interroge l'API publique GitHub (pas d'authentification nécessaire pour un dépôt public) plutôt
 * que `/releases/latest` : la CI republie en continu une release "latest" à chaque push sur main
 * (voir .github/workflows/android.yml), donc l'endpoint "dernière release" renvoie toujours cette
 * build de développement plutôt qu'une vraie version taguée. On liste les releases et on ne garde
 * que celles dont le tag suit `vMAJOR.MINOR.PATCH`, en ignorant brouillons/pré-versions.
 */
class UpdateChecker(private val repository: String = "brahmiamine/player") {

    fun checkForUpdate(currentVersion: String): UpdateCheckResult {
        val releases = runCatching { fetchReleases() }
            .getOrElse { error -> return UpdateCheckResult.Error(error.safeMessage()) }
        val latestTagged = releases
            .mapNotNull { it.toReleaseInfo() }
            .maxWithOrNull(Comparator { a, b -> compareSemVer(a.version, b.version) })
            ?: return UpdateCheckResult.NoTaggedRelease
        return if (compareSemVer(latestTagged.version, currentVersion) > 0) {
            UpdateCheckResult.UpdateAvailable(latestTagged, currentVersion)
        } else {
            UpdateCheckResult.UpToDate(currentVersion)
        }
    }

    private fun fetchReleases(): List<JSONObject> {
        val connection = (URL("https://api.github.com/repos/$repository/releases?per_page=20").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Streamia-TV-UpdateChecker")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("GitHub a répondu avec le code $code.")
            val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val array = JSONArray(body)
            buildList { for (i in 0 until array.length()) add(array.getJSONObject(i)) }
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toReleaseInfo(): ReleaseInfo? {
        if (optBoolean("draft", false) || optBoolean("prerelease", false)) return null
        val tag = optString("tag_name").removePrefix("v")
        if (!tag.matches(Regex("""\d+\.\d+\.\d+"""))) return null
        return ReleaseInfo(
            version = tag,
            htmlUrl = optString("html_url"),
            notes = optString("body").ifBlank { optString("name") },
        )
    }
}

private fun Throwable.safeMessage(): String = when (this) {
    is java.net.UnknownHostException -> "Pas de connexion réseau."
    else -> message ?: "Vérification impossible."
}

/**
 * Compare deux versions `MAJOR.MINOR.PATCH` numériquement (pas lexicographiquement : "1.9.0" doit
 * rester avant "1.10.0"). Renvoie >0 si [a] est plus récente que [b], <0 si plus ancienne, 0 si égales.
 * Un composant manquant ou non numérique compte comme 0, pour rester tolérant à un format inattendu
 * — notamment le suffixe de variante que porte [fr.streamia.tv.BuildConfig.VERSION_NAME] à l'exécution
 * (ex. "1.5.7-optimized" pour la build distribuée, "1.5.7-debug" en debug) : seuls les chiffres en
 * tête de chaque segment comptent, le reste du segment ("7-optimized" → 7) est ignoré.
 */
internal fun compareSemVer(a: String, b: String): Int {
    fun parts(version: String) = version.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val partsA = parts(a)
    val partsB = parts(b)
    for (i in 0 until maxOf(partsA.size, partsB.size)) {
        val diff = (partsA.getOrElse(i) { 0 }) - (partsB.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

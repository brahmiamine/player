package fr.streamia.tv.data

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

data class ReleaseInfo(
    val version: String,
    val htmlUrl: String,
    val notes: String,
)

sealed interface UpdateCheckResult {
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class UpdateAvailable(val release: ReleaseInfo, val currentVersion: String) : UpdateCheckResult
    /** La release "latest" n'existe pas encore ou ne porte pas de numéro de build. */
    data object NoTaggedRelease : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * La CI republie à chaque push réussi sur main la release "latest", avec "build N" dans ses notes
 * (N = versionCode = nombre de commits, voir .github/workflows/android.yml). Une mise à jour est
 * disponible dès que ce N dépasse le versionCode installé : aucun tag ni version à gérer à la main.
 */
class UpdateChecker(private val repository: String = "brahmiamine/player") {

    fun checkForUpdate(currentBuild: Int): UpdateCheckResult {
        val release = runCatching { fetchLatestRelease() }
            .getOrElse { error -> return UpdateCheckResult.Error(error.safeMessage()) }
            ?: return UpdateCheckResult.NoTaggedRelease
        val body = release.optString("body")
        val latestBuild = parseBuildNumber(body) ?: return UpdateCheckResult.NoTaggedRelease
        val current = "build $currentBuild"
        return if (latestBuild > currentBuild) {
            UpdateCheckResult.UpdateAvailable(
                ReleaseInfo(version = "build $latestBuild", htmlUrl = release.optString("html_url"), notes = body),
                current,
            )
        } else {
            UpdateCheckResult.UpToDate(current)
        }
    }

    /** null si la release "latest" n'existe pas (404). */
    private fun fetchLatestRelease(): JSONObject? {
        val connection = (URL("https://api.github.com/repos/$repository/releases/tags/latest").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Streamia-TV-UpdateChecker")
        }
        return try {
            val code = connection.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IllegalStateException("GitHub a répondu avec le code $code.")
            JSONObject(connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}

internal fun parseBuildNumber(notes: String): Int? =
    Regex("""build (\d+)""").find(notes)?.groupValues?.get(1)?.toIntOrNull()

private fun Throwable.safeMessage(): String = when (this) {
    is java.net.UnknownHostException -> "Pas de connexion réseau."
    else -> message ?: "Vérification impossible."
}

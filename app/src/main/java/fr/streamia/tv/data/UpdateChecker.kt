package fr.streamia.tv.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

data class ReleaseInfo(
    val version: String,
    val htmlUrl: String,
    val notes: String,
    /** APK publié dans la release (streamia-tv.apk), null si absent. */
    val apkUrl: String? = null,
)

sealed interface UpdateCheckResult {
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class UpdateAvailable(val release: ReleaseInfo, val currentVersion: String) : UpdateCheckResult
    /** La release "latest" n'existe pas encore ou ne porte pas de numéro de build. */
    data object NoTaggedRelease : UpdateCheckResult
    /** [release] non nul : l'APK est déjà téléchargé, seule l'installation est à refaire. */
    data class Error(val message: String, val release: ReleaseInfo? = null) : UpdateCheckResult
    /** APK téléchargé, en attente de l'autorisation « Installer des applis inconnues ». */
    data class AwaitingInstallPermission(val release: ReleaseInfo) : UpdateCheckResult
    /** Téléchargement en cours ; [progress] entre 0 et 1, null si la taille est inconnue. */
    data class Downloading(val release: ReleaseInfo, val progress: Float?) : UpdateCheckResult
    /** APK téléchargé et prêt : l'installation attend un geste (annulée, ou reprise après relance). */
    data class Downloaded(val release: ReleaseInfo) : UpdateCheckResult
    /** Installation confiée à Android ; [silent] : sans fenêtre de confirmation (Android 12+). */
    data class Installing(val release: ReleaseInfo, val silent: Boolean) : UpdateCheckResult
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
                ReleaseInfo(
                    version = "build $latestBuild",
                    htmlUrl = release.optString("html_url"),
                    notes = body,
                    apkUrl = apkUrl(release),
                ),
                current,
            )
        } else {
            UpdateCheckResult.UpToDate(current)
        }
    }

    /** Télécharge l'APK de [release] dans [target] (fichier temporaire puis renommage). */
    fun downloadApk(release: ReleaseInfo, target: File, onProgress: (Float?) -> Unit = {}) {
        val url = release.apkUrl ?: throw IOException("Aucun APK dans la release " + release.version + ".")
        // browser_download_url redirige vers le stockage GitHub (https → https : suivi automatiquement).
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            useCaches = false
            setRequestProperty("User-Agent", "Streamia-TV-UpdateChecker")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("GitHub a répondu avec le code $code.")
            target.parentFile?.mkdirs()
            val partial = File(target.path + ".part")
            val total = connection.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
            onProgress(if (total == null) null else 0f)
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total != null) {
                            val percent = (copied * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            if (partial.length() == 0L || !partial.renameTo(target)) throw IOException("APK téléchargé invalide.")
        } finally {
            connection.disconnect()
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

internal fun apkUrl(release: JSONObject): String? {
    val assets = release.optJSONArray("assets") ?: return null
    return (0 until assets.length()).asSequence()
        .map { assets.getJSONObject(it) }
        .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
        ?.optString("browser_download_url")
        ?.takeIf(String::isNotBlank)
}

internal fun parseBuildNumber(notes: String): Int? =
    Regex("""build (\d+)""").find(notes)?.groupValues?.get(1)?.toIntOrNull()

private fun Throwable.safeMessage(): String = when (this) {
    is java.net.UnknownHostException -> "Pas de connexion réseau."
    else -> message ?: "Vérification impossible."
}

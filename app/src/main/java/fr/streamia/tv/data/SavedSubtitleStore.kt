package fr.streamia.tv.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Dernier sous-titre externe d'un film ou d'un épisode (cherché en ligne, traduit par l'IA, fichier ou lien) : il est
 * rechargé tout seul à la reprise. Les fichiers sont copiés hors du cache (qui se vide) ; le décalage manuel est noté à part.
 */
class SavedSubtitleStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("saved-subtitles", Context.MODE_PRIVATE)
    private val directory = File(context.applicationContext.filesDir, "saved-subtitles")

    /** [file] : copie locale (null pour un lien), [url] : lien http(s) (null pour un fichier). */
    class Saved(val file: File?, val url: String?, val label: String, val offsetMs: Long)

    fun load(key: String): Saved? {
        val json = runCatching { JSONObject(preferences.getString(key, null) ?: return null) }.getOrNull() ?: return null
        val label = json.optString("label")
        val offset = json.optLong("offset")
        json.optString("url").takeIf(String::isNotBlank)?.let { return Saved(null, it, label, offset) }
        val file = File(json.optString("file")).takeIf { it.isFile } ?: return null.also { clear(key) }
        return Saved(file, null, label, offset)
    }

    /** Copie [source] à l'abri du cache et la retient pour [key] ; renvoie la copie. */
    fun saveFile(key: String, source: File, label: String): File {
        directory.mkdirs()
        prune()
        val copy = File(directory, "${key.hashCode().toUInt()}.${source.extension.ifEmpty { "srt" }}")
        // La source peut déjà être cette copie (reprise puis décalage) : rien à recopier.
        if (source.canonicalPath != copy.canonicalPath) source.copyTo(copy, overwrite = true)
        write(key, JSONObject().put("file", copy.absolutePath).put("label", label).put("offset", 0L))
        return copy
    }

    fun saveUrl(key: String, url: String, label: String) =
        write(key, JSONObject().put("url", url).put("label", label).put("offset", 0L))

    fun saveOffset(key: String, offsetMs: Long) {
        val json = runCatching { JSONObject(preferences.getString(key, null) ?: return) }.getOrNull() ?: return
        write(key, json.put("offset", offsetMs))
    }

    fun clear(key: String) {
        preferences.edit().remove(key).apply()
    }

    private fun write(key: String, json: JSONObject) {
        preferences.edit().putString(key, json.toString()).apply()
    }

    /** Garde les [MAX_FILES] copies les plus récentes ; une entrée dont le fichier a disparu est ignorée au chargement. */
    private fun prune() {
        val files = directory.listFiles()?.sortedByDescending(File::lastModified) ?: return
        files.drop(MAX_FILES).forEach(File::delete)
    }

    private companion object {
        const val MAX_FILES = 150
    }
}

package fr.streamia.tv.data

import java.io.File

/** Un sous-titre : sa ligne de temps d'origine (jamais envoyée à l'IA) et ses lignes de texte. */
internal data class SubtitleCue(val timing: String, val lines: List<String>)

/** Cues d'un fichier SRT ou WebVTT ; les blocs sans ligne de temps (en-tête WEBVTT, NOTE, numéros seuls) sont ignorés. */
internal fun parseCues(text: String): List<SubtitleCue> =
    text.replace("\r\n", "\n").replace('\r', '\n').trim().removePrefix("\uFEFF")
        .split(Regex("\n{2,}"))
        .mapNotNull { block ->
            val lines = block.lines()
            val timingIndex = lines.indexOfFirst { "-->" in it }
            if (timingIndex < 0) return@mapNotNull null
            val body = lines.drop(timingIndex + 1).map(String::trim).filter(String::isNotEmpty)
            if (body.isEmpty()) null else SubtitleCue(lines[timingIndex].trim(), body)
        }

/** Fichier SRT ([vtt] faux) ou WebVTT reconstruit à partir des cues. */
internal fun renderCues(cues: List<SubtitleCue>, vtt: Boolean): String = buildString {
    if (vtt) append("WEBVTT\n\n")
    cues.forEachIndexed { index, cue ->
        if (!vtt) append(index + 1).append('\n')
        append(cue.timing).append('\n')
        cue.lines.forEach { append(it).append('\n') }
        append('\n')
    }
}

/**
 * Lots de cues à traduire en une requête chacun : le plus gros possible (peu de requêtes) tout en gardant
 * la réponse sous la limite de sortie des modèles. Chaque entrée est (indice global, texte sur une ligne).
 */
internal fun packCues(cues: List<SubtitleCue>, maxChars: Int = SUBTITLE_BATCH_CHARS, maxCues: Int = SUBTITLE_BATCH_CUES): List<List<Pair<Int, String>>> {
    val batches = ArrayList<List<Pair<Int, String>>>()
    var current = ArrayList<Pair<Int, String>>()
    var chars = 0
    cues.forEachIndexed { index, cue ->
        val text = cue.lines.joinToString(LINE_BREAK)
        if (current.isNotEmpty() && (chars + text.length > maxChars || current.size >= maxCues)) {
            batches += current
            current = ArrayList()
            chars = 0
        }
        current += (index + 1) to text
        chars += text.length + 6
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

/** Lignes « numéro|traduction » d'une réponse → numéro ↦ lignes du sous-titre (séparées par [LINE_BREAK]). */
internal fun parseTranslatedLines(answer: String): Map<Int, List<String>> {
    val result = HashMap<Int, List<String>>()
    for (line in answer.lineSequence()) {
        val separator = line.indexOf('|')
        if (separator <= 0) continue
        val number = line.substring(0, separator).trim().toIntOrNull() ?: continue
        val text = line.substring(separator + 1).trim()
        if (text.isNotEmpty()) result[number] = text.split(LINE_BREAK).map(String::trim).filter(String::isNotEmpty)
    }
    return result
}

internal const val LINE_BREAK = " // "
internal const val SUBTITLE_BATCH_CHARS = 6_000
internal const val SUBTITLE_BATCH_CUES = 150

/** Accès disque aux traductions de sous-titres : une traduction terminée ou un lot déjà payé n'est jamais redemandé. */
internal class AiSubtitleCache(private val root: File) {
    init {
        root.mkdirs()
        root.listFiles()?.forEach { if (it.lastModified() < System.currentTimeMillis() - TTL_MS) it.deleteRecursively() }
    }

    fun full(key: String): File = File(root, "$key.full")
    fun batch(key: String, index: Int): File = File(File(root, key).also { it.mkdirs() }, "$index.txt")
    fun finish(key: String) = File(root, key).deleteRecursively()

    private companion object {
        const val TTL_MS = 7 * 24 * 3_600_000L
    }
}

package fr.streamia.tv.data

import org.json.JSONObject
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

/**
 * Lignes d'un sous-titre bilingue : la traduction, puis l'original en italique sur une ligne (pour apprendre une
 * langue). Sans différence entre les deux, ou sans original, la traduction seule.
 */
internal fun bilingualLines(translated: List<String>, original: List<String>): List<String> {
    val source = original.joinToString(" ") { it.replace(MARKUP_TAGS, "").trim() }.trim()
    if (source.isEmpty() || translated == original) return translated
    return translated + "<i>$source</i>"
}

private val MARKUP_TAGS = Regex("<[^>]*>|\\{[^}]*\\}")

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

/** Ligne de glossaire que le modèle ajoute après les lignes traduites : « #GLOSSAIRE: Nom=Traduction; Autre=Autre ». */
internal const val GLOSSARY_MARKER = "#GLOSSAIRE:"

/** Paires « nom d'origine ↦ traduction » de la ligne de glossaire d'une réponse ; vide s'il n'y en a pas. */
internal fun parseGlossary(answer: String): Map<String, String> {
    val line = answer.lineSequence().firstOrNull { it.trimStart().startsWith(GLOSSARY_MARKER, ignoreCase = true) } ?: return emptyMap()
    val result = LinkedHashMap<String, String>()
    line.substringAfter(':').split(';', '\u061B').forEach { pair ->
        val name = pair.substringBefore('=').trim()
        val translation = pair.substringAfter('=', "").trim()
        if (name.length in 2..40 && translation.length in 1..40 && name != translation) result[name] = translation
    }
    return result
}

/** Glossaire d'une série (ou d'un film) : les noms propres traduits une fois, reproduits à l'identique d'un épisode et d'un lot à l'autre. */
internal class AiGlossaryStore(private val root: File) {
    init {
        root.mkdirs()
        root.listFiles()?.forEach { if (it.lastModified() < System.currentTimeMillis() - TTL_MS) it.delete() }
    }

    private fun file(key: String) = File(root, key.hashCode().toUInt().toString(16) + ".json")

    @Synchronized
    fun load(key: String): Map<String, String> = runCatching {
        val json = JSONObject(file(key).readText())
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())

    @Synchronized
    fun merge(key: String, additions: Map<String, String>): Map<String, String> {
        if (additions.isEmpty()) return load(key)
        val merged = LinkedHashMap(load(key))
        additions.forEach { (name, translation) -> if (merged.size < MAX_ENTRIES || name in merged) merged.putIfAbsent(name, translation) }
        runCatching { file(key).writeText(JSONObject(merged as Map<*, *>).toString()) }
        return merged
    }

    private companion object {
        const val TTL_MS = 90 * 24 * 3_600_000L
        const val MAX_ENTRIES = 60
    }
}

internal fun glossaryPrompt(glossary: Map<String, String>): String =
    if (glossary.isEmpty()) "" else " Glossaire à respecter exactement pour ces noms : " + glossary.entries.joinToString("; ") { "${it.key}=${it.value}" } + "."

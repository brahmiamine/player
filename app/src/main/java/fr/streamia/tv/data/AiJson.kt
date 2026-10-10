package fr.streamia.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Réponses structurées des fonctions IA : le modèle répond en JSON, lu ici avec tolérance (bloc de code Markdown,
 * texte avant ou après, balises de raisonnement). Un JSON inexploitable donne `null` : l'appelant n'affiche rien
 * plutôt qu'une réponse bancale.
 */
internal fun extractJsonObject(answer: String): JSONObject? {
    val text = answer.replace(THINK_BLOCK, "").trim()
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
}

private val THINK_BLOCK = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

/** Chaînes non vides d'un tableau JSON (les éléments qui ne sont pas du texte sont ignorés), bornées à [max]. */
internal fun JSONObject.stringList(name: String, max: Int = 50): List<String> {
    val array: JSONArray = optJSONArray(name) ?: return emptyList()
    val result = ArrayList<String>()
    for (index in 0 until array.length()) {
        if (result.size >= max) break
        val value = array.opt(index)
        if (value is String || value is Number) value.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
    }
    return result
}

/** Texte sans accent, en minuscules, lettres et chiffres séparés par un espace : base de toutes les comparaisons de titres et de catégories. */
internal fun normalizeForMatch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()
        .replace(NON_ALNUM, " ")
        .trim()
        .replace(SPACES, " ")

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
private val SPACES = Regex(" {2,}")

/** Texte raccourci à [max] caractères, coupé au dernier espace et suivi de « … » s'il a été tronqué. */
internal fun shorten(text: String, max: Int): String {
    val clean = text.replace(SPACES_AND_BREAKS, " ").trim()
    if (clean.length <= max) return clean
    val cut = clean.take(max)
    return cut.substringBeforeLast(' ', cut).trimEnd(',', ';', ':', '.', ' ') + "…"
}

private val SPACES_AND_BREAKS = Regex("\\s+")

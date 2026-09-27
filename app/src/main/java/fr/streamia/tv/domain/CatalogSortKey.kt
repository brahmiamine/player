package fr.streamia.tv.domain

import java.text.Normalizer
import java.util.Locale

private val COMBINING_MARKS = Regex("\\p{M}+")

/**
 * Clé de tri alphabétique « à la française » : accents retirés, ligatures développées, minuscules.
 * Écrite en base avec chaque contenu (colonne indexée `sort_key`) pour que le tri alphabétique des
 * Films/Séries soit une simple lecture d'index, et utilisée pour le tri en mémoire des chaînes :
 * les deux ordres restent identiques.
 */
fun catalogSortKey(name: String): String {
    val ascii = name.all { it.code < 128 }
    val base = if (ascii) name else Normalizer.normalize(name, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
    val expanded = if (ascii) base else base.replace("œ", "oe").replace("æ", "ae").replace("Œ", "OE").replace("Æ", "AE")
    return expanded.lowercase(Locale.FRENCH)
}

/** Note exploitable pour le tri (0 à 10), -1 sinon : les notes absentes ou hors échelle passent en dernier. */
fun catalogRatingRank(rating: Double?): Double = rating?.takeIf { it in 0.0..10.0 } ?: -1.0

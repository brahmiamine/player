package fr.streamia.tv.data

import android.content.Context

/** Dernières recherches ouvertes (plus récente d'abord), pour les proposer quand le champ est vide. */
class RecentSearchStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("recent_searches", Context.MODE_PRIVATE)

    fun load(): List<String> = preferences.getString(KEY, "").orEmpty().split('\n').filter(String::isNotBlank)

    fun add(query: String): List<String> {
        val clean = query.trim().replace('\n', ' ')
        if (clean.length < 2) return load()
        val updated = (listOf(clean) + load().filterNot { it.equals(clean, ignoreCase = true) }).take(MAX)
        preferences.edit().putString(KEY, updated.joinToString("\n")).apply()
        return updated
    }

    fun clear() = preferences.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "queries"
        const val MAX = 8
    }
}

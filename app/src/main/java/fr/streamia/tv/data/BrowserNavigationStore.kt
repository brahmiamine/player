package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.ServerCredentials

/** Persistance légère de la catégorie et du dernier contenu sélectionnés pour chaque section. */
class BrowserNavigationStore(context: Context, credentials: ServerCredentials) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scope = "${credentials.serverUrl}|${credentials.username}".hashCode().toString()

    fun category(type: MediaType): String? = preferences.getString(key(type, "category"), null)
    fun entry(type: MediaType): String? = preferences.getString(key(type, "entry"), null)

    fun saveCategory(type: MediaType, categoryId: String) {
        preferences.edit().putString(key(type, "category"), categoryId).apply()
    }

    fun saveEntry(type: MediaType, entryKey: String) {
        preferences.edit().putString(key(type, "entry"), entryKey).apply()
    }

    private fun key(type: MediaType, value: String) = "$scope:${type.name}:$value"

    private companion object { const val PREFERENCES_NAME = "streamia-browser-navigation-v1" }
}

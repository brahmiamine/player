package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.ServerCredentials

data class NavigationListPosition(val index: Int = 0, val offset: Int = 0)

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

    fun listPosition(type: MediaType, categoryId: String): NavigationListPosition = NavigationListPosition(
        index = preferences.getInt(key(type, "list:$categoryId:index"), 0),
        offset = preferences.getInt(key(type, "list:$categoryId:offset"), 0),
    )

    fun saveListPosition(type: MediaType, categoryId: String, position: NavigationListPosition) {
        preferences.edit()
            .putInt(key(type, "list:$categoryId:index"), position.index)
            .putInt(key(type, "list:$categoryId:offset"), position.offset)
            .apply()
    }

    private fun key(type: MediaType, value: String) = "$scope:${type.name}:$value"

    private companion object { const val PREFERENCES_NAME = "streamia-browser-navigation-v1" }
}

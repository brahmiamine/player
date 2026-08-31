package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.ServerCredentials

data class NavigationListPosition(val index: Int = 0, val offset: Int = 0)
data class LiveNavigationSelection(val categoryId: String, val entryKey: String)

/** Persistance légère de la catégorie et du dernier contenu sélectionnés pour chaque section. */
class BrowserNavigationStore(context: Context, credentials: ServerCredentials) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scope = "${credentials.serverUrl}|${credentials.username}".hashCode().toString()

    fun category(type: MediaType): String? = preferences.getString(key(type, "category"), null)
    fun entry(type: MediaType): String? = preferences.getString(key(type, "entry"), null)

    fun liveSelection(): LiveNavigationSelection? {
        val categoryId = category(MediaType.Live) ?: return null
        val entryKey = entry(MediaType.Live) ?: return null
        return LiveNavigationSelection(categoryId, entryKey)
    }

    fun saveCategory(type: MediaType, categoryId: String) {
        preferences.edit().putString(key(type, "category"), categoryId).apply()
    }

    fun saveEntry(type: MediaType, entryKey: String) {
        preferences.edit().putString(key(type, "entry"), entryKey).apply()
    }

    /** Écrit le couple en une seule transaction afin de ne jamais restaurer une chaîne dans la mauvaise catégorie. */
    fun saveLiveSelection(categoryId: String, entryKey: String) {
        preferences.edit()
            .putString(key(MediaType.Live, "category"), categoryId)
            .putString(key(MediaType.Live, "entry"), entryKey)
            .apply()
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

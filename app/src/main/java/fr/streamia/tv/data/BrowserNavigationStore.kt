package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.ServerCredentials
import org.json.JSONArray

data class NavigationListPosition(val index: Int = 0, val offset: Int = 0)
data class LiveNavigationSelection(val categoryId: String, val entryKey: String)

/**
 * Persistance légère de la catégorie et du dernier contenu sélectionnés pour chaque section.
 *
 * [listPosition]/[saveListPosition] écrivent une paire de clés par catégorie déjà parcourue.
 * Comme les catalogues sont réorganisés au fil du temps (catégories renommées, playlists
 * changées), ces clés ne seraient jamais retirées naturellement : on garde donc une liste MRU
 * (la plus récemment écrite en fin) et on purge les entrées les plus anciennes au-delà de
 * [MAX_TRACKED_LIST_POSITIONS] pour que ce fichier de préférences reste borné dans le temps.
 */
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
        touchTrackedListPosition(type, categoryId)
    }

    /**
     * Met à jour la liste MRU des catégories suivies et purge les positions les plus anciennes
     * dès que leur nombre dépasse [MAX_TRACKED_LIST_POSITIONS], afin que le nombre de clés
     * "list:*" ne croisse jamais indéfiniment avec l'historique des catalogues parcourus.
     */
    private fun touchTrackedListPosition(type: MediaType, categoryId: String) {
        val trackingKey = "${type.name}:$categoryId"
        val tracked = loadTrackedListPositions().toMutableList()
        tracked.remove(trackingKey)
        tracked.add(trackingKey)

        val editor = preferences.edit()
        while (tracked.size > MAX_TRACKED_LIST_POSITIONS) {
            val evicted = tracked.removeAt(0)
            val separatorIndex = evicted.indexOf(':')
            if (separatorIndex <= 0) continue
            val evictedType = runCatching { MediaType.valueOf(evicted.substring(0, separatorIndex)) }.getOrNull() ?: continue
            val evictedCategoryId = evicted.substring(separatorIndex + 1)
            editor.remove(key(evictedType, "list:$evictedCategoryId:index"))
            editor.remove(key(evictedType, "list:$evictedCategoryId:offset"))
        }
        editor.putString(trackedListPositionsKey(), JSONArray(tracked).toString())
        editor.apply()
    }

    private fun loadTrackedListPositions(): List<String> = runCatching {
        val raw = preferences.getString(trackedListPositionsKey(), null) ?: return emptyList()
        val array = JSONArray(raw)
        List(array.length()) { array.optString(it) }.filter(String::isNotBlank)
    }.getOrDefault(emptyList())

    private fun trackedListPositionsKey() = "$scope:list_positions_mru"

    private fun key(type: MediaType, value: String) = "$scope:${type.name}:$value"

    private companion object {
        const val PREFERENCES_NAME = "streamia-browser-navigation-v1"

        /** Nombre maximal de catégories dont on garde la position de liste, au-delà on purge les plus anciennes. */
        const val MAX_TRACKED_LIST_POSITIONS = 50
    }
}

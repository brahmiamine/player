package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistance locale des préférences de navigation. Aucune donnée sensible n'est stockée ici.
 * Les clés sont isolées par profil de playlist afin qu'un abonnement n'influence jamais un autre.
 */
class UserLibraryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutationLock = Any()

    fun snapshot(profileId: String): UserLibrarySnapshot {
        val root = loadRoot(profileId)
        return UserLibrarySnapshot(
            favoriteEntries = root.optJSONArray("favorite_entries").stringSet(),
            favoriteCategories = root.optJSONArray("favorite_categories").stringSet(),
            categoryOrder = root.optJSONObject("category_order").stringListMap(),
            movedEntries = root.optJSONObject("moved_entries").stringMap(),
            history = root.optJSONArray("history").historyList(),
        )
    }

    fun toggleEntryFavorite(profileId: String, entry: MediaEntry): Boolean = mutate(profileId) { root ->
        val set = root.optJSONArray("favorite_entries").stringSet().toMutableSet()
        val added = if (entry.key in set) { set.remove(entry.key); false } else { set.add(entry.key); true }
        root.put("favorite_entries", JSONArray(set.toList()))
        added
    }

    fun toggleCategoryFavorite(profileId: String, category: MediaCategory): Boolean = mutate(profileId) { root ->
        val set = root.optJSONArray("favorite_categories").stringSet().toMutableSet()
        val added = if (category.key in set) { set.remove(category.key); false } else { set.add(category.key); true }
        root.put("favorite_categories", JSONArray(set.toList()))
        added
    }

    fun recordPlayback(
        profileId: String,
        entry: MediaEntry,
        positionMs: Long,
        durationMs: Long,
    ) {
        mutate<Unit>(profileId) { root ->
            val history = root.optJSONArray("history").historyList().toMutableList()
            history.removeAll { it.entry.key == entry.key }
            history.add(
                0,
                PlaybackHistoryItem(
                    entry = entry,
                    positionMs = positionMs.coerceAtLeast(0),
                    durationMs = durationMs.coerceAtLeast(0),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            val trimmed = history.take(MAX_HISTORY)
            root.put("history", JSONArray().apply { trimmed.forEach { put(it.toJson()) } })
        }
    }

    fun clearHistory(profileId: String) {
        mutate<Unit>(profileId) { it.put("history", JSONArray()) }
    }

    fun resumePosition(profileId: String, entryKey: String): Long {
        val item = snapshot(profileId).history.firstOrNull { it.entry.key == entryKey } ?: return 0L
        return item.positionMs.takeIf { item.isResumable() } ?: 0L
    }

    fun setCategoryOrder(profileId: String, type: MediaType, categoryKeys: List<String>) {
        mutate<Unit>(profileId) { root ->
            val order = root.optJSONObject("category_order") ?: JSONObject()
            order.put(type.name, JSONArray(categoryKeys.distinct()))
            root.put("category_order", order)
        }
    }

    fun moveEntries(profileId: String, entryKeys: Set<String>, targetCategoryId: String) {
        if (entryKeys.isEmpty()) return
        mutate<Unit>(profileId) { root ->
            val moved = root.optJSONObject("moved_entries") ?: JSONObject()
            entryKeys.forEach { moved.put(it, targetCategoryId) }
            root.put("moved_entries", moved)
        }
    }

    fun resetEntryMoves(profileId: String, entryKeys: Set<String>) {
        mutate<Unit>(profileId) { root ->
            val moved = root.optJSONObject("moved_entries") ?: JSONObject()
            entryKeys.forEach(moved::remove)
            root.put("moved_entries", moved)
        }
    }

    fun applyToCatalog(profileId: String, catalog: Catalog): Catalog {
        return applyToCatalog(catalog, snapshot(profileId))
    }

    fun applyToCatalog(catalog: Catalog, snapshot: UserLibrarySnapshot): Catalog =
        applyUserLibraryToCatalog(catalog, snapshot)

    private fun loadRoot(profileId: String): JSONObject = runCatching {
        JSONObject(preferences.getString(key(profileId), null) ?: "{}")
    }.getOrDefault(JSONObject())

    private inline fun <T> mutate(profileId: String, block: (JSONObject) -> T): T {
        return synchronized(mutationLock) {
            val root = loadRoot(profileId)
            val result = block(root)
            preferences.edit().putString(key(profileId), root.toString()).apply()
            result
        }
    }

    private fun key(profileId: String): String = "profile_${profileId.replace(Regex("[^A-Za-z0-9._-]"), "_")}" 

    private fun JSONArray?.stringSet(): Set<String> = buildSet {
        val array = this@stringSet ?: return@buildSet
        for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
    }

    private fun JSONObject?.stringMap(): Map<String, String> = buildMap {
        val json = this@stringMap ?: return@buildMap
        json.keys().forEach { key -> json.optString(key).takeIf(String::isNotBlank)?.let { put(key, it) } }
    }

    private fun JSONObject?.stringListMap(): Map<String, List<String>> = buildMap {
        val json = this@stringListMap ?: return@buildMap
        json.keys().forEach { key -> put(key, json.optJSONArray(key).stringSet().toList()) }
    }

    private fun JSONArray?.historyList(): List<PlaybackHistoryItem> = buildList {
        val array = this@historyList ?: return@buildList
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val type = runCatching { MediaType.valueOf(json.optString("type")) }.getOrNull() ?: continue
            val id = json.optInt("id", -1)
            val name = json.optString("name")
            if (id <= 0 || name.isBlank()) continue
            add(
                PlaybackHistoryItem(
                    entry = MediaEntry(
                        id = id,
                        name = name,
                        displayName = json.optString("display_name").ifBlank { name },
                        type = type,
                        categoryId = json.optString("category_id", "0"),
                        iconUrl = json.optString("icon").takeIf(String::isNotBlank),
                        number = json.optInt("number", 0),
                        extension = json.optString("extension", type.defaultExtension),
                        tvgId = json.optString("tvg_id").takeIf(String::isNotBlank),
                        plot = json.optString("plot").takeIf(String::isNotBlank),
                        rating = json.optString("rating").toDoubleOrNull(),
                        playable = json.optBoolean("playable", type != MediaType.Series),
                    ),
                    positionMs = json.optLong("position_ms", 0),
                    durationMs = json.optLong("duration_ms", 0),
                    updatedAt = json.optLong("updated_at", 0),
                ),
            )
        }
    }

    private fun PlaybackHistoryItem.toJson(): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("name", entry.name)
        put("display_name", entry.displayName)
        put("type", entry.type.name)
        put("category_id", entry.categoryId)
        put("icon", entry.iconUrl ?: "")
        put("number", entry.number)
        put("extension", entry.extension)
        put("tvg_id", entry.tvgId ?: "")
        put("plot", entry.plot ?: "")
        put("rating", entry.rating?.toString() ?: "")
        put("playable", entry.playable)
        put("position_ms", positionMs)
        put("duration_ms", durationMs)
        put("updated_at", updatedAt)
    }

    private companion object {
        const val PREFERENCES_NAME = "streamia-user-library-v1"
        const val MAX_HISTORY = 200
    }
}

data class UserLibrarySnapshot(
    val favoriteEntries: Set<String> = emptySet(),
    val favoriteCategories: Set<String> = emptySet(),
    val categoryOrder: Map<String, List<String>> = emptyMap(),
    val movedEntries: Map<String, String> = emptyMap(),
    val history: List<PlaybackHistoryItem> = emptyList(),
)

fun UserLibrarySnapshot.hasSameCatalogLayoutAs(other: UserLibrarySnapshot): Boolean =
    categoryOrder == other.categoryOrder && movedEntries == other.movedEntries

/**
 * Empreinte stable des seuls champs qui changent réellement la structure d'un [Catalog] une fois
 * passé par [applyUserLibraryToCatalog] (ordre des catégories, chaînes déplacées) — les favoris
 * n'y touchent pas et n'ont donc pas besoin d'invalider un catalogue déjà résolu mis en cache.
 * Sert de clé de validité à [CatalogCache.saveResolved]/[CatalogCache.loadResolved] : tant que
 * cette empreinte n'a pas changé depuis l'enregistrement, le catalogue déjà résolu reste correct
 * et peut être réutilisé tel quel sans repasser par [applyUserLibraryToCatalog].
 */
fun UserLibrarySnapshot.catalogLayoutFingerprint(): String {
    val orderPart = categoryOrder.toSortedMap().entries.joinToString(";") { (type, ids) -> "$type=${ids.joinToString(",")}" }
    val movedPart = movedEntries.toSortedMap().entries.joinToString(";") { (key, destination) -> "$key>$destination" }
    return "$orderPart|$movedPart"
}

/**
 * Applique les déplacements d'entrées et le tri des catégories d'un [UserLibrarySnapshot] à un
 * [Catalog]. Extraite en fonction de haut niveau (plutôt que méthode de [UserLibraryStore]) afin
 * de rester testable sans dépendance Android : [UserLibraryStore] a besoin d'un [android.content.Context]
 * réel pour ses SharedPreferences, alors que cette logique de fusion est pure.
 */
fun applyUserLibraryToCatalog(catalog: Catalog, snapshot: UserLibrarySnapshot): Catalog {
    // Rien à personnaliser : éviter de reconstruire un Catalog (et de refaire tous ses index
    // internes) quand ni le tri des catégories ni le déplacement d'entrées n'ont été utilisés.
    if (snapshot.movedEntries.isEmpty() && snapshot.categoryOrder.isEmpty()) return catalog

    val movedEntries = if (snapshot.movedEntries.isEmpty()) {
        catalog.entries
    } else {
        catalog.entries.map { entry ->
            snapshot.movedEntries[entry.key]?.let { destination -> entry.copy(categoryId = destination) } ?: entry
        }
    }
    val orderedCategories = MediaType.entries.flatMap { type ->
        val categories = catalog.categoriesFor(type)
        val preferred = snapshot.categoryOrder[type.name].orEmpty()
        if (preferred.isEmpty()) categories
        else {
            val byKey = categories.associateBy(MediaCategory::key)
            buildList {
                preferred.forEach { byKey[it]?.let(::add) }
                categories.filterNot { it.key in preferred }.forEach(::add)
            }
        }
    }
    return Catalog(orderedCategories, movedEntries, catalog.account)
}

data class PlaybackHistoryItem(
    val entry: MediaEntry,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Une lecture n'est reprenable que si elle a assez avancé (>= 30 s) sans être quasiment terminée
 * (à moins de 30 s de la fin). Logique partagée entre [UserLibraryStore.resumePosition] (reprise
 * exacte d'un contenu déjà ouvert) et la rangée « Reprendre la lecture » de l'accueil.
 */
fun PlaybackHistoryItem.isResumable(): Boolean {
    if (durationMs > 0 && positionMs >= durationMs - 30_000) return false
    return positionMs >= 30_000
}

package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.MIN_VOD_HISTORY_POSITION_MS
import fr.streamia.tv.domain.shouldRecordPlaybackInHistory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Préférences de navigation par profil (favoris, masqués, verrouillés, vus, ordre des catégories,
 * déplacements, historique). Aucune donnée sensible n'est stockée ici. Les données sont isolées par
 * profil de playlist afin qu'un abonnement n'influence jamais un autre.
 *
 * Stockage en SQLite ([LibraryDatabase]) : chaque action écrit **une ligne**. Avant, tout le JSON
 * du profil (historique de 200 contenus compris) était relu, réécrit et le fichier de préférences
 * de tous les profils réenregistré à chaque zap, toutes les 15 s d'un film et à chaque favori. Les
 * anciennes données SharedPreferences sont reprises une fois, au premier accès au profil.
 */
class UserLibraryStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences by lazy { appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }
    private val database get() = LibraryDatabase.get(appContext)

    /**
     * Instantané partagé par tout le processus, tenu à jour à chaque écriture (sans relecture).
     */
    fun snapshot(profileId: String): UserLibrarySnapshot =
        snapshots[profileId] ?: synchronized(mutationLock) {
            snapshots.getOrPut(profileId) { loadSnapshot(profileId) }
        }

    private fun loadSnapshot(profileId: String): UserLibrarySnapshot {
        if (!database.isMigrated(profileId)) {
            // Reprise unique des anciennes préférences JSON (laissées en place : un retour à une
            // version précédente de l'app les retrouve).
            database.replaceAll(profileId, parseLegacySnapshot(loadLegacyRoot(profileId)))
        }
        return database.read(profileId)
    }

    fun toggleEntryFavorite(profileId: String, entry: MediaEntry): Boolean =
        toggle(profileId, LibraryDatabase.FAVORITE_ENTRY, entry.key, { it.favoriteEntries }) { snapshot, set -> snapshot.copy(favoriteEntries = set) }

    fun toggleCategoryFavorite(profileId: String, category: MediaCategory): Boolean =
        toggle(profileId, LibraryDatabase.FAVORITE_CATEGORY, category.key, { it.favoriteCategories }) { snapshot, set -> snapshot.copy(favoriteCategories = set) }

    fun toggleEntryHidden(profileId: String, entry: MediaEntry): Boolean =
        toggle(profileId, LibraryDatabase.HIDDEN_ENTRY, entry.key, { it.hiddenEntries }) { snapshot, set -> snapshot.copy(hiddenEntries = set) }

    fun toggleCategoryHidden(profileId: String, category: MediaCategory): Boolean =
        toggle(profileId, LibraryDatabase.HIDDEN_CATEGORY, category.key, { it.hiddenCategories }) { snapshot, set -> snapshot.copy(hiddenCategories = set) }

    fun toggleCategoryLocked(profileId: String, category: MediaCategory): Boolean =
        toggle(profileId, LibraryDatabase.LOCKED_CATEGORY, category.key, { it.lockedCategories }) { snapshot, set -> snapshot.copy(lockedCategories = set) }

    fun toggleEntryWatched(profileId: String, entry: MediaEntry): Boolean =
        toggle(profileId, LibraryDatabase.WATCHED_ENTRY, entry.key, { it.watchedEntries }) { snapshot, set -> snapshot.copy(watchedEntries = set) }

    /**
     * Chaîne vue en vraie 4K : ajoutée une seule fois (jamais retirée par une nouvelle lecture).
     * `false` si elle y était déjà, sans aucune écriture.
     */
    fun addUhdEntry(profileId: String, entryKey: String): Boolean = synchronized(mutationLock) {
        val current = snapshot(profileId)
        if (entryKey in current.uhdEntries) return@synchronized false
        database.setFlag(profileId, LibraryDatabase.UHD_ENTRY, entryKey, present = true)
        snapshots[profileId] = current.copy(uhdEntries = LinkedHashSet(current.uhdEntries).apply { add(entryKey) })
        true
    }

    private fun toggle(
        profileId: String,
        kind: String,
        key: String,
        read: (UserLibrarySnapshot) -> Set<String>,
        write: (UserLibrarySnapshot, Set<String>) -> UserLibrarySnapshot,
    ): Boolean = synchronized(mutationLock) {
        val current = snapshot(profileId)
        val set = LinkedHashSet(read(current))
        val added = set.add(key)
        if (!added) set.remove(key)
        database.setFlag(profileId, kind, key, added)
        snapshots[profileId] = write(current, set)
        added
    }

    fun recordPlayback(
        profileId: String,
        entry: MediaEntry,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (!shouldRecordPlaybackInHistory(entry.type, positionMs)) return
        synchronized(mutationLock) {
            val current = snapshot(profileId)
            val item = PlaybackHistoryItem(
                entry = entry,
                positionMs = positionMs.coerceAtLeast(0),
                durationMs = durationMs.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis(),
            )
            val history = (listOf(item) + current.history.filterNot { it.entry.key == entry.key })
            val kept = history.take(MAX_HISTORY)
            val dropped = history.drop(MAX_HISTORY).map { it.entry.key }
            database.recordHistory(profileId, item, item.toJson().toString(), dropped)
            snapshots[profileId] = current.copy(history = kept)
        }
    }

    fun clearHistory(profileId: String, type: MediaType? = null) {
        synchronized(mutationLock) {
            val current = snapshot(profileId)
            database.clearHistory(profileId, type)
            snapshots[profileId] = current.copy(history = historyAfterClearingType(current.history, type))
        }
    }

    fun resumePosition(profileId: String, entryKey: String): Long {
        val item = snapshot(profileId).history.firstOrNull { it.entry.key == entryKey } ?: return 0L
        return item.positionMs.takeIf { item.isResumable() } ?: 0L
    }

    fun setCategoryOrder(profileId: String, type: MediaType, categoryKeys: List<String>) {
        synchronized(mutationLock) {
            val current = snapshot(profileId)
            val keys = categoryKeys.distinct()
            database.setCategoryOrder(profileId, type.name, JSONArray(keys).toString())
            snapshots[profileId] = current.copy(categoryOrder = current.categoryOrder + (type.name to keys))
        }
    }

    fun moveEntries(profileId: String, entryKeys: Set<String>, targetCategoryId: String) {
        if (entryKeys.isEmpty()) return
        synchronized(mutationLock) {
            val current = snapshot(profileId)
            database.moveEntries(profileId, entryKeys, targetCategoryId)
            snapshots[profileId] = current.copy(movedEntries = current.movedEntries + entryKeys.associateWith { targetCategoryId })
        }
    }

    fun resetEntryMoves(profileId: String, entryKeys: Set<String>) {
        synchronized(mutationLock) {
            val current = snapshot(profileId)
            database.resetEntryMoves(profileId, entryKeys)
            snapshots[profileId] = current.copy(movedEntries = current.movedEntries - entryKeys)
        }
    }

    fun applyToCatalog(profileId: String, catalog: Catalog): Catalog {
        return applyToCatalog(catalog, snapshot(profileId))
    }

    fun applyToCatalog(catalog: Catalog, snapshot: UserLibrarySnapshot): Catalog =
        applyUserLibraryToCatalog(catalog, snapshot)

    /**
     * Bloc JSON d'un profil (favoris, catégories masquées/verrouillées, ordre, historique…), au
     * format historique, pour une sauvegarde — jamais d'identifiant de connexion ici.
     */
    fun exportRaw(profileId: String): JSONObject = snapshot(profileId).toLegacyJson()

    /** Remplace entièrement les préférences d'un profil par un bloc exporté via [exportRaw]. */
    fun importRaw(profileId: String, raw: JSONObject) {
        synchronized(mutationLock) {
            val imported = parseLegacySnapshot(raw)
            database.replaceAll(profileId, imported)
            snapshots[profileId] = database.read(profileId)
        }
    }

    private fun loadLegacyRoot(profileId: String): JSONObject = runCatching {
        JSONObject(preferences.getString(key(profileId), null) ?: "{}")
    }.getOrDefault(JSONObject())

    private fun key(profileId: String): String = "profile_${profileId.replace(UNSAFE_KEY_CHARS, "_")}"

    private companion object {
        val mutationLock = Any()
        val snapshots = java.util.concurrent.ConcurrentHashMap<String, UserLibrarySnapshot>()
        const val PREFERENCES_NAME = "streamia-user-library-v1"
        const val MAX_HISTORY = 200
    }
}

/** Instantané lu depuis l'ancien format JSON (préférences, sauvegardes). */
internal fun parseLegacySnapshot(root: JSONObject): UserLibrarySnapshot = UserLibrarySnapshot(
    favoriteEntries = root.optJSONArray("favorite_entries").stringSet(),
    favoriteCategories = root.optJSONArray("favorite_categories").stringSet(),
    hiddenEntries = root.optJSONArray("hidden_entries").stringSet(),
    hiddenCategories = root.optJSONArray("hidden_categories").stringSet(),
    lockedCategories = root.optJSONArray("locked_categories").stringSet(),
    watchedEntries = root.optJSONArray("watched_entries").stringSet(),
    uhdEntries = root.optJSONArray("uhd_entries").stringSet(),
    categoryOrder = root.optJSONObject("category_order").stringListMap(),
    movedEntries = root.optJSONObject("moved_entries").stringMap(),
    history = root.optJSONArray("history").historyList(),
)

internal fun UserLibrarySnapshot.toLegacyJson(): JSONObject = JSONObject().apply {
    put("favorite_entries", JSONArray(favoriteEntries.toList()))
    put("favorite_categories", JSONArray(favoriteCategories.toList()))
    put("hidden_entries", JSONArray(hiddenEntries.toList()))
    put("hidden_categories", JSONArray(hiddenCategories.toList()))
    put("locked_categories", JSONArray(lockedCategories.toList()))
    put("watched_entries", JSONArray(watchedEntries.toList()))
    put("uhd_entries", JSONArray(uhdEntries.toList()))
    put("category_order", JSONObject().apply { categoryOrder.forEach { (type, keys) -> put(type, JSONArray(keys)) } })
    put("moved_entries", JSONObject().apply { movedEntries.forEach { (key, category) -> put(key, category) } })
    put("history", JSONArray().apply { history.forEach { put(it.toJson()) } })
}

internal fun JSONArray?.stringSet(): Set<String> = buildSet {
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
        array.optJSONObject(index)?.let(::historyItemFromJson)?.let(::add)
    }
}

/** Un élément d'historique au format JSON (colonne `entry_json` en base, sauvegardes). */
internal fun historyItemFromJson(json: JSONObject): PlaybackHistoryItem? {
    val type = runCatching { MediaType.valueOf(json.optString("type")) }.getOrNull() ?: return null
    val id = json.optInt("id", -1)
    val name = json.optString("name")
    if (id <= 0 || name.isBlank()) return null
    return PlaybackHistoryItem(
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
    )
}

internal fun PlaybackHistoryItem.toJson(): JSONObject = JSONObject().apply {
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

data class UserLibrarySnapshot(
    val favoriteEntries: Set<String> = emptySet(),
    val favoriteCategories: Set<String> = emptySet(),
    val hiddenEntries: Set<String> = emptySet(),
    val hiddenCategories: Set<String> = emptySet(),
    val lockedCategories: Set<String> = emptySet(),
    val watchedEntries: Set<String> = emptySet(),
    /** Chaînes Direct mesurées en 3840 × 2160 à la lecture, dans l'ordre de découverte. */
    val uhdEntries: Set<String> = emptySet(),
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
 */
fun UserLibrarySnapshot.catalogLayoutFingerprint(): String {
    val orderPart = categoryOrder.toSortedMap().entries.joinToString(";") { (type, ids) -> "$type=${ids.joinToString(",")}" }
    val movedPart = movedEntries.toSortedMap().entries.joinToString(";") { (key, destination) -> "$key>$destination" }
    return "$orderPart|$movedPart"
}

/**
 * Applique les déplacements d'entrées et le tri des catégories d'un [UserLibrarySnapshot] à un
 * [Catalog]. Extraite en fonction de haut niveau (plutôt que méthode de [UserLibraryStore]) afin
 * de rester testable sans dépendance Android.
 */
fun applyUserLibraryToCatalog(catalog: Catalog, snapshot: UserLibrarySnapshot): Catalog {
    // Rien à personnaliser : éviter de reconstruire un Catalog (et de refaire ses index internes)
    // quand ni le tri des catégories ni le déplacement d'entrées n'ont été utilisés.
    if (snapshot.movedEntries.isEmpty() && snapshot.categoryOrder.isEmpty()) return catalog

    // Seules les entrées réellement déplacées sont recopiées, et seules leurs sections perdent leurs
    // index : les autres (des dizaines de milliers de chaînes Direct) gardent ceux du catalogue
    // d'origine au lieu d'être réindexées, souvent sur le thread principal, à chaque page chargée.
    val changedTypes = HashSet<MediaType>()
    var movedEntries: MutableList<MediaEntry>? = null
    if (snapshot.movedEntries.isNotEmpty()) {
        catalog.entries.forEachIndexed { index, entry ->
            val destination = snapshot.movedEntries[entry.key]
            if (destination != null && destination != entry.categoryId) {
                val target = movedEntries ?: catalog.entries.toMutableList().also { movedEntries = it }
                target[index] = entry.copy(categoryId = destination)
                changedTypes += entry.type
            }
        }
    }
    val orderedCategories = if (snapshot.categoryOrder.values.all { it.isEmpty() }) {
        catalog.categories
    } else {
        MediaType.entries.flatMap { type ->
            val categories = catalog.categoriesFor(type)
            val preferred = snapshot.categoryOrder[type.name].orEmpty()
            if (preferred.isEmpty()) categories
            else {
                val byKey = categories.associateBy(MediaCategory::key)
                val preferredSet = preferred.toHashSet()
                buildList {
                    preferred.forEach { byKey[it]?.let(::add) }
                    categories.filterNot { it.key in preferredSet }.forEach(::add)
                }
            }
        }
    }
    val adjustedCategoryCounts = if (catalog.categoryCounts.isEmpty() || changedTypes.isEmpty()) {
        catalog.categoryCounts
    } else {
        catalog.categoryCounts.toMutableMap().apply {
            catalog.entries.forEach { entry ->
                val destination = snapshot.movedEntries[entry.key] ?: return@forEach
                if (destination == entry.categoryId) return@forEach
                val sourceKey = Catalog.categoryKey(entry.type, entry.categoryId)
                val destinationKey = Catalog.categoryKey(entry.type, destination)
                this[sourceKey] = ((this[sourceKey] ?: 0) - 1).coerceAtLeast(0)
                this[destinationKey] = (this[destinationKey] ?: 0) + 1
            }
        }
    }
    if (movedEntries == null && orderedCategories == catalog.categories) return catalog
    return catalog.withCustomLayout(
        categories = orderedCategories,
        entries = movedEntries ?: catalog.entries,
        categoryCounts = adjustedCategoryCounts,
        changedTypes = changedTypes,
    )
}

internal fun historyAfterClearingType(
    history: List<PlaybackHistoryItem>,
    type: MediaType?,
): List<PlaybackHistoryItem> =
    if (type == null) emptyList() else history.filterNot { it.entry.type == type }

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
 * Une lecture VOD devient reprenable après 5 s de lecture, sans conserver dans « Reprendre »
 * un contenu quasiment terminé (à moins de 30 s de la fin). Logique partagée entre
 * [UserLibraryStore.resumePosition] et la rangée « Reprendre la lecture » de l'accueil.
 */
fun PlaybackHistoryItem.isResumable(): Boolean {
    if (durationMs > 0 && positionMs >= durationMs - 30_000) return false
    return positionMs >= MIN_VOD_HISTORY_POSITION_MS
}

private val UNSAFE_KEY_CHARS = Regex("[^A-Za-z0-9._-]")

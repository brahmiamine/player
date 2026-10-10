package fr.streamia.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import fr.streamia.tv.domain.MediaType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bibliothèque utilisateur par profil (voir [UserLibraryStore]) : une ligne par favori, masquage,
 * verrouillage, contenu vu, déplacement et élément d'historique. Une action n'écrit que sa ligne.
 * Une seule instance par processus (connexions et cache SQLite partagés entre les stores).
 */
internal class LibraryDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Ordre d'ajout conservé par le rowid (favoris affichés dans l'ordre où ils ont été ajoutés).
        db.execSQL(
            """
            CREATE TABLE library_flags (
                profile_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                item_key TEXT NOT NULL,
                UNIQUE (profile_id, kind, item_key)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE library_moves (
                profile_id TEXT NOT NULL,
                entry_key TEXT NOT NULL,
                category_id TEXT NOT NULL,
                PRIMARY KEY (profile_id, entry_key)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE library_history (
                profile_id TEXT NOT NULL,
                entry_key TEXT NOT NULL,
                media_type TEXT NOT NULL,
                entry_json TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                PRIMARY KEY (profile_id, entry_key)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_library_history_order ON library_history(profile_id, sequence DESC)")
        db.execSQL(
            """
            CREATE TABLE library_meta (
                profile_id TEXT NOT NULL,
                name TEXT NOT NULL,
                value TEXT NOT NULL,
                PRIMARY KEY (profile_id, name)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == newVersion) { "Unsupported library database migration $oldVersion -> $newVersion" }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) runCatching { db.execSQL("PRAGMA synchronous = NORMAL") }
    }

    fun isMigrated(profileId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM library_meta WHERE profile_id = ? AND name = ?",
        arrayOf(profileId, META_MIGRATED),
    ).use { it.moveToFirst() }

    fun read(profileId: String): UserLibrarySnapshot {
        val db = readableDatabase
        val flags = HashMap<String, LinkedHashSet<String>>()
        db.rawQuery("SELECT kind, item_key FROM library_flags WHERE profile_id = ? ORDER BY rowid", arrayOf(profileId)).use { cursor ->
            while (cursor.moveToNext()) flags.getOrPut(cursor.getString(0)) { LinkedHashSet() } += cursor.getString(1)
        }
        val moves = HashMap<String, String>()
        db.rawQuery("SELECT entry_key, category_id FROM library_moves WHERE profile_id = ?", arrayOf(profileId)).use { cursor ->
            while (cursor.moveToNext()) moves[cursor.getString(0)] = cursor.getString(1)
        }
        val order = HashMap<String, List<String>>()
        db.rawQuery("SELECT name, value FROM library_meta WHERE profile_id = ? AND name LIKE 'order:%'", arrayOf(profileId)).use { cursor ->
            while (cursor.moveToNext()) {
                val keys = runCatching { JSONArray(cursor.getString(1)).stringSet().toList() }.getOrDefault(emptyList())
                order[cursor.getString(0).removePrefix("order:")] = keys
            }
        }
        val history = ArrayList<PlaybackHistoryItem>()
        db.rawQuery("SELECT entry_json FROM library_history WHERE profile_id = ? ORDER BY sequence DESC", arrayOf(profileId)).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { historyItemFromJson(JSONObject(cursor.getString(0))) }.getOrNull()?.let(history::add)
            }
        }
        return UserLibrarySnapshot(
            favoriteEntries = flags[FAVORITE_ENTRY].orEmpty(),
            favoriteCategories = flags[FAVORITE_CATEGORY].orEmpty(),
            hiddenEntries = flags[HIDDEN_ENTRY].orEmpty(),
            hiddenCategories = flags[HIDDEN_CATEGORY].orEmpty(),
            lockedCategories = flags[LOCKED_CATEGORY].orEmpty(),
            watchedEntries = flags[WATCHED_ENTRY].orEmpty(),
            uhdEntries = flags[UHD_ENTRY].orEmpty(),
            categoryOrder = order,
            movedEntries = moves,
            history = history,
        )
    }

    fun setFlag(profileId: String, kind: String, key: String, present: Boolean) {
        val db = writableDatabase
        if (present) {
            db.insertWithOnConflict(
                "library_flags",
                null,
                ContentValues().apply {
                    put("profile_id", profileId)
                    put("kind", kind)
                    put("item_key", key)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        } else {
            db.delete("library_flags", "profile_id = ? AND kind = ? AND item_key = ?", arrayOf(profileId, kind, key))
        }
    }

    /** Élément en tête d'historique, et retrait de ceux qui dépassent la limite. */
    fun recordHistory(profileId: String, item: PlaybackHistoryItem, json: String, dropped: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val next = db.rawQuery("SELECT COALESCE(MAX(sequence), 0) + 1 FROM library_history WHERE profile_id = ?", arrayOf(profileId))
                .use { if (it.moveToFirst()) it.getLong(0) else 1L }
            db.insertWithOnConflict(
                "library_history",
                null,
                ContentValues().apply {
                    put("profile_id", profileId)
                    put("entry_key", item.entry.key)
                    put("media_type", item.entry.type.name)
                    put("entry_json", json)
                    put("sequence", next)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            dropped.forEach { key -> db.delete("library_history", "profile_id = ? AND entry_key = ?", arrayOf(profileId, key)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearHistory(profileId: String, type: MediaType?) {
        if (type == null) {
            writableDatabase.delete("library_history", "profile_id = ?", arrayOf(profileId))
        } else {
            writableDatabase.delete("library_history", "profile_id = ? AND media_type = ?", arrayOf(profileId, type.name))
        }
    }

    fun setCategoryOrder(profileId: String, type: String, json: String) {
        putMeta(writableDatabase, profileId, "order:$type", json)
    }

    fun moveEntries(profileId: String, entryKeys: Set<String>, categoryId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            entryKeys.forEach { key ->
                db.insertWithOnConflict(
                    "library_moves",
                    null,
                    ContentValues().apply {
                        put("profile_id", profileId)
                        put("entry_key", key)
                        put("category_id", categoryId)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun resetEntryMoves(profileId: String, entryKeys: Set<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            entryKeys.forEach { key -> db.delete("library_moves", "profile_id = ? AND entry_key = ?", arrayOf(profileId, key)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Remplace toutes les données d'un profil (reprise des anciennes préférences, restauration). */
    fun replaceAll(profileId: String, snapshot: UserLibrarySnapshot) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("library_flags", "profile_id = ?", arrayOf(profileId))
            db.delete("library_moves", "profile_id = ?", arrayOf(profileId))
            db.delete("library_history", "profile_id = ?", arrayOf(profileId))
            db.delete("library_meta", "profile_id = ?", arrayOf(profileId))
            listOf(
                FAVORITE_ENTRY to snapshot.favoriteEntries,
                FAVORITE_CATEGORY to snapshot.favoriteCategories,
                HIDDEN_ENTRY to snapshot.hiddenEntries,
                HIDDEN_CATEGORY to snapshot.hiddenCategories,
                LOCKED_CATEGORY to snapshot.lockedCategories,
                WATCHED_ENTRY to snapshot.watchedEntries,
                UHD_ENTRY to snapshot.uhdEntries,
            ).forEach { (kind, keys) -> keys.forEach { setFlag(profileId, kind, it, present = true) } }
            snapshot.movedEntries.forEach { (key, category) ->
                db.insertWithOnConflict(
                    "library_moves",
                    null,
                    ContentValues().apply {
                        put("profile_id", profileId)
                        put("entry_key", key)
                        put("category_id", category)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            snapshot.categoryOrder.forEach { (type, keys) -> putMeta(db, profileId, "order:$type", JSONArray(keys).toString()) }
            val size = snapshot.history.size
            snapshot.history.forEachIndexed { index, item ->
                db.insertWithOnConflict(
                    "library_history",
                    null,
                    ContentValues().apply {
                        put("profile_id", profileId)
                        put("entry_key", item.entry.key)
                        put("media_type", item.entry.type.name)
                        put("entry_json", item.toJson().toString())
                        // Le plus récent (index 0) garde le numéro le plus grand.
                        put("sequence", (size - index).toLong())
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            putMeta(db, profileId, META_MIGRATED, "1")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun putMeta(db: SQLiteDatabase, profileId: String, name: String, value: String) {
        db.insertWithOnConflict(
            "library_meta",
            null,
            ContentValues().apply {
                put("profile_id", profileId)
                put("name", name)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    companion object {
        const val FAVORITE_ENTRY = "favorite_entry"
        const val FAVORITE_CATEGORY = "favorite_category"
        const val HIDDEN_ENTRY = "hidden_entry"
        const val HIDDEN_CATEGORY = "hidden_category"
        const val LOCKED_CATEGORY = "locked_category"
        const val WATCHED_ENTRY = "watched_entry"
        /** Chaîne Direct vue en vraie résolution 3840 × 2160 (catégorie « UHD 4K »). */
        const val UHD_ENTRY = "uhd_entry"
        private const val META_MIGRATED = "migrated"
        private const val DATABASE_NAME = "library-v1.db"
        private const val DATABASE_VERSION = 1

        @Volatile private var instance: LibraryDatabase? = null

        fun get(context: Context): LibraryDatabase = instance ?: synchronized(this) {
            instance ?: LibraryDatabase(context.applicationContext).also { instance = it }
        }
    }
}

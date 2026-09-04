package fr.streamia.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import fr.streamia.tv.domain.AccountInfo
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.isVisualSeparator

/**
 * Disk-backed provider catalogue.
 *
 * The old JSON cache had to deserialize every media row before a category could be shown. This
 * store keeps the provider catalogue normalized and indexed so startup can read only categories,
 * counts and a tiny set of useful entries, then fetch browser rows in bounded pages.
 */
internal class CatalogDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE catalog_profiles (
                profile_id TEXT PRIMARY KEY NOT NULL,
                saved_at INTEGER NOT NULL,
                account_username TEXT,
                account_status TEXT,
                account_expires_at INTEGER,
                account_active_connections INTEGER,
                account_maximum_connections INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE catalog_categories (
                profile_id TEXT NOT NULL,
                media_type TEXT NOT NULL,
                category_id TEXT NOT NULL,
                name TEXT NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY (profile_id, media_type, category_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE catalog_entries (
                profile_id TEXT NOT NULL,
                media_type TEXT NOT NULL,
                media_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                display_name TEXT NOT NULL,
                category_id TEXT NOT NULL,
                icon_url TEXT,
                number INTEGER NOT NULL,
                extension TEXT NOT NULL,
                tvg_id TEXT,
                plot TEXT,
                rating REAL,
                playable INTEGER NOT NULL,
                added_at INTEGER,
                navigable INTEGER NOT NULL,
                PRIMARY KEY (profile_id, media_type, media_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX idx_catalog_category ON catalog_entries(profile_id, media_type, category_id, navigable, number, media_id)",
        )
        db.execSQL(
            "CREATE INDEX idx_catalog_section ON catalog_entries(profile_id, media_type, navigable, number, media_id)",
        )
        db.execSQL(
            "CREATE INDEX idx_catalog_recent ON catalog_entries(profile_id, media_type, navigable, added_at DESC)",
        )
        db.execSQL(
            "CREATE INDEX idx_catalog_tvg ON catalog_entries(profile_id, media_type, tvg_id)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first SQLite catalogue. Future versions must use explicit migrations;
        // never wipe a valid provider cache during an application upgrade.
        check(oldVersion == newVersion) { "Unsupported catalog database migration $oldVersion -> $newVersion" }
    }

    fun hasProfile(profileId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM catalog_profiles WHERE profile_id = ? LIMIT 1",
        arrayOf(profileId),
    ).use(Cursor::moveToFirst)

    /** Replaces one profile atomically from a catalogue already fully in memory (used by M3U imports). */
    fun replace(profileId: String, catalog: Catalog) {
        val session = beginReplace(profileId)
        try {
            session.writeCategories(catalog.categories)
            session.writeEntries(catalog.entries)
            session.commit(catalog.account)
        } catch (error: Throwable) {
            session.abort()
            throw error
        }
    }

    /**
     * Starts a transactional replace of one profile's catalogue without requiring the caller to
     * hold every row in memory at once: [ReplaceSession.writeCategories]/[ReplaceSession.writeEntries]
     * can be called repeatedly with small batches as a provider response streams in, and only
     * [ReplaceSession.commit] makes the new data visible to readers — [ReplaceSession.abort] (or an
     * exception escaping before `commit`) rolls the whole transaction back, leaving the previous
     * valid catalogue untouched.
     */
    fun beginReplace(profileId: String): ReplaceSession {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("catalog_entries", "profile_id = ?", arrayOf(profileId))
            db.delete("catalog_categories", "profile_id = ?", arrayOf(profileId))
            db.delete("catalog_profiles", "profile_id = ?", arrayOf(profileId))
        } catch (error: Throwable) {
            db.endTransaction()
            throw error
        }
        return ReplaceSession(db, profileId)
    }

    inner class ReplaceSession internal constructor(private val db: SQLiteDatabase, val profileId: String) : CatalogWriteSink {
        private var categoryStatement: android.database.sqlite.SQLiteStatement? = null
        private var entryStatement: android.database.sqlite.SQLiteStatement? = null
        private val categoryPositions = HashMap<String, Int>()
        private var nextCategoryPosition = 0
        private var finished = false

        /**
         * `INSERT OR REPLACE` rather than a plain `INSERT`: a network retry re-parses a section from
         * scratch (see [XtreamClient.fetchEntriesStreaming]), so a row already written by a partial
         * first attempt must be safely overwritten by the retry instead of tripping the primary key.
         *
         * [categoryPositions] keeps the position a category was first assigned rather than always
         * incrementing: without it, a category rewritten later in the same session (the M3U fallback
         * can rewrite one already written from broken provider metadata, see
         * [XtreamClient.loadCatalogOnIo]) would jump to whatever position the counter had reached by
         * then, reordering it in [loadCategories]' `ORDER BY position`.
         */
        override fun writeCategories(categories: List<MediaCategory>) {
            check(!finished) { "ReplaceSession already finished" }
            if (categories.isEmpty()) return
            val statement = categoryStatement ?: db.compileStatement(
                "INSERT OR REPLACE INTO catalog_categories(profile_id, media_type, category_id, name, position) VALUES(?,?,?,?,?)",
            ).also { categoryStatement = it }
            categories.forEach { category ->
                val key = "${category.type.name}:${category.id}"
                val position = categoryPositions.getOrPut(key) { nextCategoryPosition++ }
                statement.clearBindings()
                statement.bindString(1, profileId)
                statement.bindString(2, category.type.name)
                statement.bindString(3, category.id)
                statement.bindString(4, category.name)
                statement.bindLong(5, position.toLong())
                statement.executeInsert()
            }
        }

        override fun writeEntries(entries: List<MediaEntry>) {
            check(!finished) { "ReplaceSession already finished" }
            if (entries.isEmpty()) return
            val statement = entryStatement ?: db.compileStatement(
                """
                INSERT OR REPLACE INTO catalog_entries(
                    profile_id, media_type, media_id, name, display_name, category_id, icon_url,
                    number, extension, tvg_id, plot, rating, playable, added_at, navigable
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).also { entryStatement = it }
            entries.forEach { entry ->
                statement.clearBindings()
                statement.bindString(1, profileId)
                statement.bindString(2, entry.type.name)
                statement.bindLong(3, entry.id.toLong())
                statement.bindString(4, entry.name)
                statement.bindString(5, entry.displayName)
                statement.bindString(6, entry.categoryId)
                statement.bindNullableString(7, entry.iconUrl)
                statement.bindLong(8, entry.number.toLong())
                statement.bindString(9, entry.extension)
                statement.bindNullableString(10, entry.tvgId)
                statement.bindNullableString(11, entry.plot)
                statement.bindNullableDouble(12, entry.rating)
                statement.bindLong(13, if (entry.playable) 1 else 0)
                statement.bindNullableLong(14, entry.addedAtEpochSeconds)
                statement.bindLong(15, if (entry.isVisualSeparator()) 0 else 1)
                statement.executeInsert()
            }
        }

        /** Writes the profile row and commits. Nothing written by this session is visible before this. */
        fun commit(account: AccountInfo?) {
            check(!finished) { "ReplaceSession already finished" }
            finished = true
            try {
                val profileValues = ContentValues().apply {
                    put("profile_id", profileId)
                    put("saved_at", System.currentTimeMillis())
                    account?.let {
                        put("account_username", it.username)
                        put("account_status", it.status)
                        putNullable("account_expires_at", it.expiresAtEpochSeconds)
                        putNullable("account_active_connections", it.activeConnections)
                        putNullable("account_maximum_connections", it.maximumConnections)
                    }
                }
                check(db.insertOrThrow("catalog_profiles", null, profileValues) != -1L)
                db.setTransactionSuccessful()
            } finally {
                categoryStatement?.close()
                entryStatement?.close()
                db.endTransaction()
            }
        }

        /** Rolls back everything written by this session, leaving the previous valid catalogue in place. */
        fun abort() {
            if (finished) return
            finished = true
            categoryStatement?.close()
            entryStatement?.close()
            db.endTransaction()
        }
    }

    /** Taille du fichier SQLite sur disque, pour l'écran diagnostics — 0 si le fichier n'existe pas encore. */
    fun fileSizeBytes(): Long = runCatching { java.io.File(readableDatabase.path).length() }.getOrDefault(0L)

    fun delete(profileId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("catalog_entries", "profile_id = ?", arrayOf(profileId))
            db.delete("catalog_categories", "profile_id = ?", arrayOf(profileId))
            db.delete("catalog_profiles", "profile_id = ?", arrayOf(profileId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadLightweight(
        profileId: String,
        extraEntryKeys: Set<String> = emptySet(),
        recentPerType: Int = DEFAULT_RECENT_PER_TYPE,
    ): Catalog? {
        if (!hasProfile(profileId)) return null
        val categories = loadCategories(profileId)
        val entries = LinkedHashMap<String, MediaEntry>()
        MediaType.entries.forEach { type ->
            loadRecent(profileId, type, recentPerType).forEach { entries[it.key] = it }
        }
        loadEntriesByKeys(profileId, extraEntryKeys).forEach { entries[it.key] = it }
        return Catalog(
            categories = categories,
            entries = entries.values.toList(),
            account = loadAccount(profileId),
            totalCounts = loadTotalCounts(profileId),
            categoryCounts = loadCategoryCounts(profileId),
        )
    }

    fun loadFull(profileId: String): Catalog? {
        if (!hasProfile(profileId)) return null
        val entries = readableDatabase.rawQuery(
            """
            SELECT ${ENTRY_COLUMNS.joinToString()} FROM catalog_entries
            WHERE profile_id = ? AND navigable = 1
            ORDER BY CASE media_type WHEN 'Live' THEN 0 WHEN 'Movie' THEN 1 ELSE 2 END, number, media_id
            """.trimIndent(),
            arrayOf(profileId),
        ).use(::readEntries)
        return Catalog(loadCategories(profileId), entries, loadAccount(profileId))
    }

    fun loadCategoryPage(
        profileId: String,
        type: MediaType,
        categoryId: String,
        offset: Int,
        limit: Int,
    ): List<MediaEntry> {
        if (limit <= 0) return emptyList()
        val all = categoryId == Catalog.ALL_CATEGORY_ID
        val whereCategory = if (all) "" else " AND category_id = ?"
        val args = buildList {
            add(profileId)
            add(type.name)
            if (!all) add(categoryId)
            add(limit.coerceAtMost(MAX_PAGE_SIZE).toString())
            add(offset.coerceAtLeast(0).toString())
        }.toTypedArray()
        return readableDatabase.rawQuery(
            """
            SELECT ${ENTRY_COLUMNS.joinToString()} FROM catalog_entries
            WHERE profile_id = ? AND media_type = ? AND navigable = 1$whereCategory
            ORDER BY number, media_id
            LIMIT ? OFFSET ?
            """.trimIndent(),
            args,
        ).use(::readEntries)
    }

    /** Reads the next/previous provider row directly from the index and wraps at section/category ends. */
    fun loadAdjacent(
        profileId: String,
        current: MediaEntry,
        categoryId: String,
        delta: Int,
    ): MediaEntry? {
        if (delta == 0) return current
        val all = categoryId == Catalog.ALL_CATEGORY_ID
        val categoryClause = if (all) "" else " AND category_id = ?"
        val forward = delta > 0
        val comparison = if (forward) ">" else "<"
        val idComparison = if (forward) ">" else "<"
        val direction = if (forward) "ASC" else "DESC"
        val args = buildList {
            add(profileId)
            add(current.type.name)
            if (!all) add(categoryId)
            add(current.number.toString())
            add(current.number.toString())
            add(current.id.toString())
        }.toTypedArray()
        val adjacent = readableDatabase.rawQuery(
            """
            SELECT ${ENTRY_COLUMNS.joinToString()} FROM catalog_entries
            WHERE profile_id = ? AND media_type = ? AND navigable = 1$categoryClause
              AND (number $comparison ? OR (number = ? AND media_id $idComparison ?))
            ORDER BY number $direction, media_id $direction
            LIMIT 1
            """.trimIndent(),
            args,
        ).use { cursor -> if (cursor.moveToFirst()) readEntry(cursor) else null }
        if (adjacent != null) return adjacent

        val wrapArgs = buildList {
            add(profileId)
            add(current.type.name)
            if (!all) add(categoryId)
        }.toTypedArray()
        return readableDatabase.rawQuery(
            """
            SELECT ${ENTRY_COLUMNS.joinToString()} FROM catalog_entries
            WHERE profile_id = ? AND media_type = ? AND navigable = 1$categoryClause
            ORDER BY number $direction, media_id $direction
            LIMIT 1
            """.trimIndent(),
            wrapArgs,
        ).use { cursor -> if (cursor.moveToFirst()) readEntry(cursor) else null }
    }

    fun loadType(profileId: String, type: MediaType): List<MediaEntry> = readableDatabase.rawQuery(
        """
        SELECT ${ENTRY_COLUMNS.joinToString()} FROM catalog_entries
        WHERE profile_id = ? AND media_type = ? AND navigable = 1
        ORDER BY number, media_id
        """.trimIndent(),
        arrayOf(profileId, type.name),
    ).use(::readEntries)

    fun search(profileId: String, query: String, type: MediaType? = null, limit: Int = 500): List<MediaEntry> {
        val needle = "%${query.trim()}%"
        if (query.isBlank()) return emptyList()
        val typeClause = if (type == null) "" else " AND media_type = ?"
        val args = buildList {
            add(profileId)
            if (type != null) add(type.name)
            add(needle)
            add(needle)
            add(needle)
            add(limit.coerceIn(1, MAX_SEARCH_RESULTS).toString())
        }.toTypedArray()
        return readableDatabase.rawQuery(
            """
            SELECT ${ENTRY_COLUMNS.joinToString()} FROM catalog_entries
            WHERE profile_id = ? AND navigable = 1$typeClause
              AND (name LIKE ? COLLATE NOCASE OR display_name LIKE ? COLLATE NOCASE OR tvg_id LIKE ? COLLATE NOCASE)
            ORDER BY number, media_id
            LIMIT ?
            """.trimIndent(),
            args,
        ).use(::readEntries)
    }

    fun loadEntriesByKeys(profileId: String, keys: Set<String>): List<MediaEntry> {
        if (keys.isEmpty()) return emptyList()
        val result = LinkedHashMap<String, MediaEntry>()
        keys.forEach { key ->
            val separator = key.indexOf(':')
            if (separator <= 0) return@forEach
            val type = MediaType.entries.firstOrNull { it.name == key.substring(0, separator) } ?: return@forEach
            val id = key.substring(separator + 1).toIntOrNull() ?: return@forEach
            readableDatabase.rawQuery(
                """
                SELECT ${ENTRY_COLUMNS.joinToString()} FROM catalog_entries
                WHERE profile_id = ? AND media_type = ? AND media_id = ? AND navigable = 1 LIMIT 1
                """.trimIndent(),
                arrayOf(profileId, type.name, id.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) readEntry(cursor).let { result[it.key] = it }
            }
        }
        return result.values.toList()
    }

    private fun loadRecent(profileId: String, type: MediaType, limit: Int): List<MediaEntry> {
        if (limit <= 0) return emptyList()
        return readableDatabase.rawQuery(
            """
            SELECT ${ENTRY_COLUMNS.joinToString()} FROM catalog_entries
            WHERE profile_id = ? AND media_type = ? AND navigable = 1
            ORDER BY COALESCE(added_at, 0) DESC, number DESC, media_id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(profileId, type.name, limit.toString()),
        ).use(::readEntries)
    }

    private fun loadCategories(profileId: String): List<MediaCategory> = readableDatabase.rawQuery(
        """
        SELECT category_id, name, media_type FROM catalog_categories
        WHERE profile_id = ? ORDER BY position
        """.trimIndent(),
        arrayOf(profileId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val type = cursor.getString(2).toMediaType()
                add(MediaCategory(cursor.getString(0), cursor.getString(1), type))
            }
        }
    }

    private fun loadTotalCounts(profileId: String): Map<MediaType, Int> = readableDatabase.rawQuery(
        """
        SELECT media_type, COUNT(*) FROM catalog_entries
        WHERE profile_id = ? AND navigable = 1 GROUP BY media_type
        """.trimIndent(),
        arrayOf(profileId),
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) put(cursor.getString(0).toMediaType(), cursor.getInt(1))
        }
    }

    private fun loadCategoryCounts(profileId: String): Map<String, Int> = readableDatabase.rawQuery(
        """
        SELECT media_type, category_id, COUNT(*) FROM catalog_entries
        WHERE profile_id = ? AND navigable = 1 GROUP BY media_type, category_id
        """.trimIndent(),
        arrayOf(profileId),
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val type = cursor.getString(0).toMediaType()
                put(Catalog.categoryKey(type, cursor.getString(1)), cursor.getInt(2))
            }
        }
    }

    private fun loadAccount(profileId: String): AccountInfo? = readableDatabase.rawQuery(
        """
        SELECT account_username, account_status, account_expires_at,
               account_active_connections, account_maximum_connections
        FROM catalog_profiles WHERE profile_id = ? LIMIT 1
        """.trimIndent(),
        arrayOf(profileId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val username = cursor.nullableString(0) ?: return@use null
        val status = cursor.nullableString(1) ?: return@use null
        AccountInfo(
            username = username,
            status = status,
            expiresAtEpochSeconds = cursor.nullableLong(2),
            activeConnections = cursor.nullableInt(3),
            maximumConnections = cursor.nullableInt(4),
        )
    }

    private fun readEntries(cursor: Cursor): List<MediaEntry> = buildList {
        while (cursor.moveToNext()) add(readEntry(cursor))
    }

    private fun readEntry(cursor: Cursor): MediaEntry = MediaEntry(
        id = cursor.getInt(0),
        name = cursor.getString(1),
        displayName = cursor.getString(2),
        type = cursor.getString(3).toMediaType(),
        categoryId = cursor.getString(4),
        iconUrl = cursor.nullableString(5),
        number = cursor.getInt(6),
        extension = cursor.getString(7),
        tvgId = cursor.nullableString(8),
        plot = cursor.nullableString(9),
        rating = cursor.nullableDouble(10),
        playable = cursor.getInt(11) != 0,
        addedAtEpochSeconds = cursor.nullableLong(12),
    )

    private fun String.toMediaType(): MediaType = MediaType.entries.firstOrNull { it.name == this } ?: MediaType.Live

    private fun ContentValues.putNullable(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: Int?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun android.database.sqlite.SQLiteStatement.bindNullableString(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }

    private fun android.database.sqlite.SQLiteStatement.bindNullableLong(index: Int, value: Long?) {
        if (value == null) bindNull(index) else bindLong(index, value)
    }

    private fun android.database.sqlite.SQLiteStatement.bindNullableDouble(index: Int, value: Double?) {
        if (value == null) bindNull(index) else bindDouble(index, value)
    }

    private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.nullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun Cursor.nullableInt(index: Int): Int? = if (isNull(index)) null else getInt(index)
    private fun Cursor.nullableDouble(index: Int): Double? = if (isNull(index)) null else getDouble(index)

    private companion object {
        const val DATABASE_NAME = "catalog-v5.db"
        const val DATABASE_VERSION = 1
        const val DEFAULT_RECENT_PER_TYPE = 8
        const val MAX_PAGE_SIZE = 500
        const val MAX_SEARCH_RESULTS = 1_000
        val ENTRY_COLUMNS = listOf(
            "media_id",
            "name",
            "display_name",
            "media_type",
            "category_id",
            "icon_url",
            "number",
            "extension",
            "tvg_id",
            "plot",
            "rating",
            "playable",
            "added_at",
        )
    }
}
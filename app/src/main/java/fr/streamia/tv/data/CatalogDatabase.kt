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
import fr.streamia.tv.domain.catalogRatingRank
import fr.streamia.tv.domain.catalogSortKey
import fr.streamia.tv.domain.isVisualSeparator

/**
 * Disk-backed provider catalogue.
 *
 * The old JSON cache had to deserialize every media row before a category could be shown. This
 * store keeps the provider catalogue normalized and indexed so startup can read only categories,
 * counts and a tiny set of useful entries, then fetch browser rows in bounded pages.
 */
internal class CatalogDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, CatalogSchema.DATABASE_NAME, null, CatalogSchema.DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = CatalogSchema.create(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = CatalogSchema.upgrade(db, oldVersion)

    @Volatile private var searchTableChecked: Boolean? = null

    private fun hasSearchTable(db: SQLiteDatabase): Boolean =
        searchTableChecked ?: CatalogSchema.searchAvailable(db).also { searchTableChecked = it }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // WAL : NORMAL suffit (seule la dernière transaction peut être perdue sur coupure de courant,
        // et le catalogue se retélécharge) et évite une synchronisation disque par validation.
        if (!db.isReadOnly) runCatching { db.execSQL("PRAGMA synchronous = NORMAL") }
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
            // Écriture en masse : les index de lecture sont retirés puis reconstruits en une passe
            // au commit (un tri par index), au lieu d'être tenus à jour à chaque insertion. Tout se
            // passe dans la transaction : un abandon les restaure, et les lecteurs (WAL) continuent
            // de voir l'ancien catalogue et ses index jusqu'au commit.
            CatalogSchema.dropSecondaryIndexes(db)
            runCatching { db.execSQL("PRAGMA cache_size = $BULK_CACHE_SIZE") }
            replaceProfileRows(db, profileId)
        } catch (error: Throwable) {
            db.endTransaction()
            restoreCacheSize(db)
            throw error
        }
        return ReplaceSession(db, profileId, hasSearchTable(db))
    }

    /**
     * Comme [deleteProfileRows], mais sans rien supprimer ligne par ligne quand aucun autre profil
     * n'a de catalogue (le cas courant) : table vidée d'un coup, index plein texte recréé vide.
     * Retirer 200 000 lignes d'un index FTS une par une coûtait autant que de les y écrire.
     */
    private fun replaceProfileRows(db: SQLiteDatabase, profileId: String) {
        if (hasOtherProfileEntries(db, profileId)) {
            deleteProfileRows(db, profileId)
            return
        }
        if (hasSearchTable(db)) {
            runCatching { CatalogSchema.recreateSearchIndex(db) }
            searchTableChecked = CatalogSchema.searchAvailable(db)
        }
        db.execSQL("DELETE FROM catalog_entries")
        db.delete("catalog_categories", "profile_id = ?", arrayOf(profileId))
        db.delete("catalog_counts", "profile_id = ?", arrayOf(profileId))
        db.delete("catalog_profiles", "profile_id = ?", arrayOf(profileId))
    }

    /** Deux lectures de la clé primaire (profils avant et après [profileId]) : pas de parcours de table. */
    private fun hasOtherProfileEntries(db: SQLiteDatabase, profileId: String): Boolean =
        listOf("<", ">").any { op ->
            db.rawQuery("SELECT 1 FROM catalog_entries WHERE profile_id $op ? LIMIT 1", arrayOf(profileId)).use(Cursor::moveToFirst)
        }

    private fun restoreCacheSize(db: SQLiteDatabase) {
        runCatching { db.execSQL("PRAGMA cache_size = $DEFAULT_CACHE_SIZE") }
    }

    private fun deleteProfileRows(db: SQLiteDatabase, profileId: String) {
        if (hasSearchTable(db)) runCatching { db.delete(SEARCH_TABLE, "profile_id = ?", arrayOf(profileId)) }
        db.delete("catalog_entries", "profile_id = ?", arrayOf(profileId))
        db.delete("catalog_categories", "profile_id = ?", arrayOf(profileId))
        db.delete("catalog_counts", "profile_id = ?", arrayOf(profileId))
        db.delete("catalog_profiles", "profile_id = ?", arrayOf(profileId))
    }

    inner class ReplaceSession internal constructor(
        private val db: SQLiteDatabase,
        val profileId: String,
        private val indexSearch: Boolean,
    ) : CatalogWriteSink {
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
                INSERT INTO catalog_entries(
                    profile_id, media_type, media_id, name, display_name, category_id, icon_url,
                    number, extension, tvg_id, plot, rating, playable, added_at, navigable,
                    sort_key, rating_rank, added_rank
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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
                statement.bindString(16, catalogSortKey(entry.displayName))
                statement.bindDouble(17, catalogRatingRank(entry.rating))
                statement.bindLong(18, entry.addedAtEpochSeconds ?: -1L)
                try {
                    statement.executeInsert()
                } catch (_: android.database.sqlite.SQLiteConstraintException) {
                    // Même entrée réécrite par une nouvelle tentative réseau (la section est
                    // reparsée depuis le début) : l'ancienne ligne est retirée avant de réécrire.
                    // L'index plein texte n'est rempli qu'au commit : rien à y retirer.
                    removeEntry(entry)
                    statement.executeInsert()
                }
            }
        }

        private fun removeEntry(entry: MediaEntry) {
            db.delete("catalog_entries", "profile_id = ? AND media_type = ? AND media_id = ?", arrayOf(profileId, entry.type.name, entry.id.toString()))
        }

        /** Index plein texte rempli en une seule requête une fois toutes les entrées écrites. */
        private fun indexForSearch() {
            runCatching {
                db.execSQL(
                    "INSERT INTO $SEARCH_TABLE(docid, name, display_name, tvg_id, profile_id) " +
                        "SELECT rowid, name, display_name, tvg_id, profile_id FROM catalog_entries WHERE profile_id = ?",
                    arrayOf(profileId),
                )
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
                // Index reconstruits d'abord : le calcul des comptes qui suit s'appuie dessus.
                CatalogSchema.createSecondaryIndexes(db)
                db.execSQL(
                    """
                    INSERT INTO catalog_counts(profile_id, media_type, category_id, entry_count)
                    SELECT profile_id, media_type, category_id, COUNT(*) FROM catalog_entries
                    WHERE profile_id = ? AND navigable = 1 GROUP BY media_type, category_id
                    """.trimIndent(),
                    arrayOf(profileId),
                )
                if (indexSearch) indexForSearch()
                db.setTransactionSuccessful()
            } finally {
                categoryStatement?.close()
                entryStatement?.close()
                db.endTransaction()
                restoreCacheSize(db)
            }
        }

        /** Rolls back everything written by this session, leaving the previous valid catalogue in place. */
        fun abort() {
            if (finished) return
            finished = true
            categoryStatement?.close()
            entryStatement?.close()
            db.endTransaction()
            restoreCacheSize(db)
            // L'index plein texte a pu être recréé puis annulé avec la transaction : on revérifiera.
            searchTableChecked = null
        }
    }

    /** Taille du fichier SQLite sur disque, pour l'écran diagnostics — 0 si le fichier n'existe pas encore. */
    fun fileSizeBytes(): Long = runCatching { java.io.File(readableDatabase.path).length() }.getOrDefault(0L)

    fun delete(profileId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            deleteProfileRows(db, profileId)
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
        val counts = loadCounts(profileId)
        return Catalog(
            categories = categories,
            entries = entries.values.toList(),
            account = loadAccount(profileId),
            totalCounts = counts.totals,
            categoryCounts = counts.byCategory,
        )
    }

    /**
     * Page d'une catégorie (ou de « Tout »), déjà triée par un index. [afterKey] : clé de la dernière
     * entrée déjà affichée pour cette catégorie et ce tri. La page suivante part alors de sa position
     * dans l'index (pagination par curseur) : son coût ne grandit plus avec la profondeur, comme
     * avec `OFFSET` qui relisait toutes les lignes précédentes. Sans curseur exploitable
     * (première page, entrée disparue), repli sur [offset].
     */
    fun loadCategoryPage(
        profileId: String,
        type: MediaType,
        categoryId: String,
        offset: Int,
        limit: Int,
        order: VodSortOrder = VodSortOrder.Provider,
        afterKey: String? = null,
    ): List<MediaEntry> {
        if (limit <= 0) return emptyList()
        val all = categoryId == Catalog.ALL_CATEGORY_ID
        val sort = SortSpec.of(order)
        val cursor = afterKey?.let { cursorFor(profileId, type, it, sort) }
        val whereCategory = if (all) "" else " AND category_id = ?"
        val keyset = when {
            cursor == null -> ""
            sort.descending -> " AND ${sort.column} <= ? AND (${sort.column} < ? OR media_id > ?)"
            else -> " AND ${sort.column} >= ? AND (${sort.column} > ? OR media_id > ?)"
        }
        val args = buildList {
            add(profileId)
            add(type.name)
            if (!all) add(categoryId)
            if (cursor != null) {
                add(cursor.first)
                add(cursor.first)
                add(cursor.second.toString())
            }
            add(limit.coerceAtMost(MAX_PAGE_SIZE).toString())
            add(if (cursor != null) "0" else offset.coerceAtLeast(0).toString())
        }.toTypedArray()
        return readableDatabase.rawQuery(
            """
            SELECT ${LIST_COLUMNS.joinToString()} FROM catalog_entries
            WHERE profile_id = ? AND media_type = ? AND navigable = 1$whereCategory$keyset
            ORDER BY ${sort.column}${if (sort.descending) " DESC" else ""}, media_id
            LIMIT ? OFFSET ?
            """.trimIndent(),
            args,
        ).use(::readEntries)
    }

    /** Valeur de tri et identifiant de l'entrée [key], lus en base (donc toujours cohérents avec l'index). */
    private fun cursorFor(profileId: String, type: MediaType, key: String, sort: SortSpec): Pair<String, Int>? {
        val id = key.substringAfter(':', "").toIntOrNull() ?: return null
        if (!key.startsWith("${type.name}:")) return null
        return readableDatabase.rawQuery(
            "SELECT ${sort.column}, media_id FROM catalog_entries WHERE profile_id = ? AND media_type = ? AND media_id = ? AND navigable = 1",
            arrayOf(profileId, type.name, id.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) to cursor.getInt(1) else null }
    }

    /** Colonne indexée et sens de chaque tri (départage par `media_id` croissant). */
    private class SortSpec(val column: String, val descending: Boolean) {
        companion object {
            fun of(order: VodSortOrder): SortSpec = when (order) {
                VodSortOrder.Provider -> SortSpec("number", descending = false)
                VodSortOrder.Alphabetical -> SortSpec("sort_key", descending = false)
                // Sans date d'ajout / note : -1, donc en fin de liste.
                VodSortOrder.RecentlyAdded -> SortSpec("added_rank", descending = true)
                VodSortOrder.Rating -> SortSpec("rating_rank", descending = true)
            }
        }
    }

    fun loadType(profileId: String, type: MediaType): List<MediaEntry> = readableDatabase.rawQuery(
        """
        SELECT ${LIST_COLUMNS.joinToString()} FROM catalog_entries
        WHERE profile_id = ? AND media_type = ? AND navigable = 1
        ORDER BY number, media_id
        """.trimIndent(),
        arrayOf(profileId, type.name),
    ).use(::readEntries)

    fun search(profileId: String, query: String, type: MediaType? = null, limit: Int = 500): List<MediaEntry> {
        // Recherche indexée par préfixe de mots d'abord ; repli sur la sous-chaîne (LIKE) si elle ne
        // trouve rien (fragment au milieu d'un mot) ou si FTS est indisponible sur l'appareil.
        val matchQuery = ftsMatchQuery(query)
        if (matchQuery != null) {
            runCatching { searchIndexed(profileId, matchQuery, type, limit) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return searchLike(profileId, query, type, limit)
    }

    /**
     * Contenus de certaines catégories (liste vide pour un type = tout le type), les mieux notés ou les plus récents
     * d'abord : base de la recherche en langage naturel, qui choisit les catégories d'après leur nom. Tout reste dans
     * l'index (pas de parcours du catalogue en mémoire) ; au plus [MAX_SEARCH_RESULTS] lignes.
     */
    fun searchInCategories(profileId: String, categories: Map<MediaType, Set<String>>, sort: SearchSort, limit: Int): List<MediaEntry> {
        if (categories.isEmpty()) return emptyList()
        val clauses = ArrayList<String>()
        val args = ArrayList<String>().apply { add(profileId) }
        categories.forEach { (type, ids) ->
            if (ids.isEmpty()) {
                clauses += "(media_type = ?)"
                args += type.name
            } else {
                val bounded = ids.take(MAX_CATEGORY_IDS_PER_TYPE)
                clauses += "(media_type = ? AND category_id IN (${bounded.joinToString(",") { "?" }}))"
                args += type.name
                args += bounded
            }
        }
        args += limit.coerceIn(1, MAX_SEARCH_RESULTS).toString()
        val order = if (sort == SearchSort.Recent) "added_rank DESC" else "rating_rank DESC"
        return readableDatabase.rawQuery(
            """
            SELECT ${LIST_COLUMNS.joinToString()} FROM catalog_entries
            WHERE profile_id = ? AND navigable = 1 AND (${clauses.joinToString(" OR ")})
            ORDER BY $order, media_id
            LIMIT ?
            """.trimIndent(),
            args.toTypedArray(),
        ).use(::readEntries)
    }

    private fun searchIndexed(profileId: String, matchQuery: String, type: MediaType?, limit: Int): List<MediaEntry> {
        if (!hasSearchTable(readableDatabase)) return emptyList()
        val typeClause = if (type == null) "" else " AND media_type = ?"
        val args = buildList {
            add(matchQuery)
            add(profileId)
            add(profileId)
            if (type != null) add(type.name)
            add(limit.coerceIn(1, MAX_SEARCH_RESULTS).toString())
        }.toTypedArray()
        return readableDatabase.rawQuery(
            """
            SELECT ${LIST_COLUMNS.joinToString()} FROM catalog_entries
            WHERE rowid IN (SELECT docid FROM $SEARCH_TABLE WHERE $SEARCH_TABLE MATCH ? AND profile_id = ?)
              AND profile_id = ? AND navigable = 1$typeClause
            ORDER BY number, media_id
            LIMIT ?
            """.trimIndent(),
            args,
        ).use(::readEntries)
    }

    private fun searchLike(profileId: String, query: String, type: MediaType?, limit: Int): List<MediaEntry> {
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
            SELECT ${LIST_COLUMNS.joinToString()} FROM catalog_entries
            WHERE profile_id = ? AND navigable = 1$typeClause
              AND (name LIKE ? COLLATE NOCASE OR display_name LIKE ? COLLATE NOCASE OR tvg_id LIKE ? COLLATE NOCASE)
            ORDER BY number, media_id
            LIMIT ?
            """.trimIndent(),
            args,
        ).use(::readEntries)
    }

    /**
     * Entrées demandées, dans l'ordre des clés. Lues par lots (`media_id IN (…)`, [KEYS_PER_QUERY]
     * par requête) et non plus une requête par clé : l'index de recommandations en demande des
     * dizaines de milliers (fiches enrichies, dont le nombre grandit chaque jour), l'ouverture
     * d'une liste des centaines (favoris, historique).
     */
    fun loadEntriesByKeys(profileId: String, keys: Set<String>): List<MediaEntry> {
        if (keys.isEmpty()) return emptyList()
        val requested = keys.mapNotNull { key ->
            val separator = key.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            val type = MediaType.entries.firstOrNull { it.name == key.substring(0, separator) } ?: return@mapNotNull null
            val id = key.substring(separator + 1).toIntOrNull() ?: return@mapNotNull null
            type to id
        }
        val found = HashMap<Pair<MediaType, Int>, MediaEntry>(requested.size * 2)
        requested.groupBy({ it.first }, { it.second }).forEach { (type, ids) ->
            ids.distinct().chunked(KEYS_PER_QUERY).forEach { chunk ->
                readableDatabase.rawQuery(
                    """
                    SELECT ${ENTRY_COLUMNS.joinToString()} FROM catalog_entries
                    WHERE profile_id = ? AND media_type = ? AND navigable = 1
                      AND media_id IN (${chunk.joinToString(",") { "?" }})
                    """.trimIndent(),
                    arrayOf(profileId, type.name) + chunk.map(Int::toString),
                ).use { cursor ->
                    while (cursor.moveToNext()) readEntry(cursor).let { found[it.type to it.id] = it }
                }
            }
        }
        val result = LinkedHashMap<String, MediaEntry>()
        requested.forEach { key -> found[key]?.let { result[it.key] = it } }
        return result.values.toList()
    }

    fun loadHomeRecommendationCandidates(profileId: String, type: MediaType, limit: Int): List<MediaEntry> =
        loadRecent(profileId, type, limit)

    /**
     * Pool de fiche détail centré sur le média : la majorité des candidats provient de
     * sa catégorie fournisseur et de sa position voisine dans la playlist. Les requêtes
     * utilisent idx_catalog_category, donc elles restent rapides sur un très gros catalogue.
     */
    fun loadSimilarityCandidates(profileId: String, source: MediaEntry, limit: Int): List<MediaEntry> {
        if (limit <= 0) return emptyList()
        val categoryTarget = (limit * 3 / 4).coerceAtLeast(1)
        val forward = loadCategoryNeighbors(profileId, source, forward = true, limit = categoryTarget)
        val backward = loadCategoryNeighbors(profileId, source, forward = false, limit = categoryTarget)
        val categoryCandidates = interleave(forward, backward, categoryTarget)
        val seen = categoryCandidates.mapTo(mutableSetOf()) { it.key }
        return buildList {
            addAll(categoryCandidates)
            loadRecent(profileId, source.type, limit).forEach { candidate ->
                if (candidate.key != source.key && seen.add(candidate.key)) add(candidate)
            }
        }.take(limit)
    }

    private fun loadCategoryNeighbors(
        profileId: String,
        source: MediaEntry,
        forward: Boolean,
        limit: Int,
    ): List<MediaEntry> {
        if (limit <= 0) return emptyList()
        val comparison = if (forward) ">" else "<"
        val direction = if (forward) "ASC" else "DESC"
        return readableDatabase.rawQuery(
            """
            SELECT ${ENTRY_COLUMNS.joinToString()} FROM catalog_entries
            WHERE profile_id = ? AND media_type = ? AND category_id = ? AND navigable = 1
              AND (number $comparison ? OR (number = ? AND media_id $comparison ?))
            ORDER BY number $direction, media_id $direction
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                profileId,
                source.type.name,
                source.categoryId,
                source.number.toString(),
                source.number.toString(),
                source.id.toString(),
                limit.toString(),
            ),
        ).use(::readEntries)
    }

    private fun interleave(
        first: List<MediaEntry>,
        second: List<MediaEntry>,
        limit: Int,
    ): List<MediaEntry> {
        val result = ArrayList<MediaEntry>(limit)
        var index = 0
        while (result.size < limit && (index < first.size || index < second.size)) {
            if (index < first.size) result += first[index]
            if (result.size < limit && index < second.size) result += second[index]
            index += 1
        }
        return result
    }

    private fun loadRecent(profileId: String, type: MediaType, limit: Int): List<MediaEntry> {
        if (limit <= 0) return emptyList()
        return readableDatabase.rawQuery(
            """
            SELECT ${ENTRY_COLUMNS.joinToString()} FROM catalog_entries
            WHERE profile_id = ? AND media_type = ? AND navigable = 1
            -- Pas de COALESCE : il empêchait d'utiliser idx_catalog_recent et obligeait à trier toute
            -- la section (des centaines de milliers de lignes) pour 40 résultats. Les NULL restent
            -- en dernier avec DESC, comme avant.
            ORDER BY added_at DESC, number DESC, media_id DESC
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

    private class CatalogCounts(val totals: Map<MediaType, Int>, val byCategory: Map<String, Int>)

    /**
     * Comptes par catégorie écrits au remplacement du catalogue ([catalog_counts]) : une lecture de
     * quelques centaines de lignes au lieu d'un parcours de l'index entier à chaque ouverture.
     */
    private fun loadCounts(profileId: String): CatalogCounts {
        fun read(sql: String) = readableDatabase.rawQuery(sql, arrayOf(profileId)).use { cursor ->
            val totals = HashMap<MediaType, Int>()
            val byCategory = HashMap<String, Int>()
            while (cursor.moveToNext()) {
                val type = cursor.getString(0).toMediaType()
                val count = cursor.getInt(2)
                byCategory[Catalog.categoryKey(type, cursor.getString(1))] = count
                totals[type] = (totals[type] ?: 0) + count
            }
            CatalogCounts(totals, byCategory)
        }
        val stored = read("SELECT media_type, category_id, entry_count FROM catalog_counts WHERE profile_id = ?")
        if (stored.byCategory.isNotEmpty()) return stored
        return read(
            "SELECT media_type, category_id, COUNT(*) FROM catalog_entries WHERE profile_id = ? AND navigable = 1 GROUP BY media_type, category_id",
        )
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

    private companion object {
        const val SEARCH_TABLE = CatalogSchema.SEARCH_TABLE

        /** Cache de pages (en Kio, valeur négative) du seul remplacement d'un catalogue : 32 Mo. */
        private const val BULK_CACHE_SIZE = -32768
        private const val DEFAULT_CACHE_SIZE = -2000
        const val DEFAULT_RECENT_PER_TYPE = 8
        const val MAX_PAGE_SIZE = 500
        const val MAX_SEARCH_RESULTS = 1_000
        const val MAX_CATEGORY_IDS_PER_TYPE = 300
        /** Sous la limite historique de 999 paramètres par requête SQLite (2 déjà pris). */
        const val KEYS_PER_QUERY = 500
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

        /**
         * Lignes de liste (pages, Direct, recherche) : sans le résumé, lu seulement pour une fiche
         * ou les recommandations — des pages bien plus légères à lire et à garder en mémoire.
         */
        val LIST_COLUMNS = ENTRY_COLUMNS.map { if (it == "plot") "NULL AS plot" else it }
    }
}
/**
 * Requête FTS « chaque mot commence par… » : `tf1 hd` → `tf1* hd*`. Ne garde que lettres et
 * chiffres (aucune syntaxe FTS injectable), `null` si rien d'exploitable.
 */
internal fun ftsMatchQuery(query: String): String? =
    query.lowercase()
        .split(FTS_WORD_SEPARATOR)
        .filter(String::isNotBlank)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ") { "$it*" }

/** Séparateur de mots de la recherche, compilé une fois (appelé à chaque frappe). */
private val FTS_WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

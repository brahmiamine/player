package fr.streamia.tv.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * Schéma de la base du catalogue fournisseur : création et migrations explicites (une mise à jour
 * de l'application ne doit jamais effacer un cache fournisseur valide).
 */
internal object CatalogSchema {
    const val DATABASE_NAME = "catalog-v5.db"
    const val DATABASE_VERSION = 3
    const val SEARCH_TABLE = "catalog_search"
    private const val LEGACY_SEARCH_TABLE = "catalog_entries_fts"

    fun create(db: SQLiteDatabase) {
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
                sort_key TEXT NOT NULL DEFAULT '',
                rating_rank REAL NOT NULL DEFAULT -1,
                added_rank INTEGER NOT NULL DEFAULT -1,
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
        createSortIndexes(db)
        createCountsTable(db)
        createSearchIndex(db)
    }

    fun upgrade(db: SQLiteDatabase, oldVersion: Int) {
        // Explicit migrations only: never wipe a valid provider cache during an application upgrade.
        if (oldVersion < 3) migrateToSortedPaging(db)
    }

    /**
     * Version 3 : colonnes de tri indexées (alphabétique, note, ajout), comptes par catégorie
     * pré-calculés et index plein texte tenu à jour ligne par ligne. La clé alphabétique migrée est
     * une approximation SQL (`lower`) : la prochaine actualisation du catalogue écrit la clé exacte.
     */
    private fun migrateToSortedPaging(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE catalog_entries ADD COLUMN sort_key TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE catalog_entries ADD COLUMN rating_rank REAL NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE catalog_entries ADD COLUMN added_rank INTEGER NOT NULL DEFAULT -1")
        db.execSQL(
            """
            UPDATE catalog_entries SET
                sort_key = lower(display_name),
                rating_rank = CASE WHEN rating IS NULL OR rating < 0 OR rating > 10 THEN -1 ELSE rating END,
                added_rank = COALESCE(added_at, -1)
            """.trimIndent(),
        )
        createSortIndexes(db)
        createCountsTable(db)
        db.execSQL(
            """
            INSERT INTO catalog_counts(profile_id, media_type, category_id, entry_count)
            SELECT profile_id, media_type, category_id, COUNT(*) FROM catalog_entries
            WHERE navigable = 1 GROUP BY profile_id, media_type, category_id
            """.trimIndent(),
        )
        runCatching { db.execSQL("DROP TABLE IF EXISTS $LEGACY_SEARCH_TABLE") }
        createSearchIndex(db)
        if (searchAvailable(db)) {
            runCatching {
                db.execSQL(
                    "INSERT INTO $SEARCH_TABLE(docid, name, display_name, tvg_id, profile_id) " +
                        "SELECT rowid, name, display_name, tvg_id, profile_id FROM catalog_entries",
                )
            }
        }
    }

    /**
     * Un index par tri et par portée (catégorie ou « Tout ») : chaque page est une lecture d'index
     * à partir de la dernière ligne affichée, au lieu de trier toute la section à chaque page.
     */
    private fun createSortIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_alpha_cat ON catalog_entries(profile_id, media_type, category_id, navigable, sort_key, media_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_alpha ON catalog_entries(profile_id, media_type, navigable, sort_key, media_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_rating_cat ON catalog_entries(profile_id, media_type, category_id, navigable, rating_rank DESC, media_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_rating ON catalog_entries(profile_id, media_type, navigable, rating_rank DESC, media_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_added_cat ON catalog_entries(profile_id, media_type, category_id, navigable, added_rank DESC, media_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_added ON catalog_entries(profile_id, media_type, navigable, added_rank DESC, media_id)")
    }

    /** Comptes par catégorie écrits au remplacement du catalogue : l'ouverture ne parcourt plus toute la table. */
    private fun createCountsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS catalog_counts (
                profile_id TEXT NOT NULL,
                media_type TEXT NOT NULL,
                category_id TEXT NOT NULL,
                entry_count INTEGER NOT NULL,
                PRIMARY KEY (profile_id, media_type, category_id)
            )
            """.trimIndent(),
        )
    }

    /**
     * Index plein texte FTS4 tenu à jour au fil de l'écriture (docid = rowid de l'entrée), avec le
     * profil en colonne non indexée : remplacer un profil ne supprime que ses lignes. L'ancien index
     * à contenu externe devait être reconstruit en entier — tous les profils — à chaque actualisation.
     * Accents ignorés quand le tokenizer unicode61 est disponible ; sans FTS, la recherche reste sur `LIKE`.
     */
    private fun createSearchIndex(db: SQLiteDatabase) {
        val columns = "name, display_name, tvg_id, profile_id, notindexed=profile_id"
        runCatching {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS $SEARCH_TABLE USING fts4($columns, tokenize=unicode61 \"remove_diacritics=1\")")
        }.recoverCatching {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS $SEARCH_TABLE USING fts4($columns)")
        }
    }

    fun searchAvailable(db: SQLiteDatabase): Boolean = runCatching {
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(SEARCH_TABLE)).use(Cursor::moveToFirst)
    }.getOrDefault(false)
}

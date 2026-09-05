package fr.streamia.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import fr.streamia.tv.domain.MediaDetails
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.recommendation.ContentFeatures
import fr.streamia.tv.recommendation.RecommendationFeedback
import fr.streamia.tv.recommendation.RecommendationFeedbackKind

/**
 * Données propres au moteur de recommandation.
 *
 * Cette base est volontairement séparée du catalogue fournisseur : elle peut évoluer, être purgée
 * ou reconstruite sans invalider le cache Xtream/M3U ni forcer un nouvel import massif.
 */
internal class RecommendationStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE recommendation_feedback (
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
                kind TEXT NOT NULL,
                occurred_at INTEGER NOT NULL,
                PRIMARY KEY (profile_id, media_type, media_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE recommendation_features (
                profile_id TEXT NOT NULL,
                media_type TEXT NOT NULL,
                media_id INTEGER NOT NULL,
                plot TEXT,
                genre TEXT,
                cast_members TEXT,
                director TEXT,
                release_date TEXT,
                country TEXT,
                rating REAL,
                tmdb_id TEXT,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (profile_id, media_type, media_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX idx_recommendation_feedback_profile_time ON recommendation_feedback(profile_id, occurred_at DESC)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == newVersion) {
            "Unsupported recommendation database migration $oldVersion -> $newVersion"
        }
    }

    fun feedback(profileId: String): Map<String, RecommendationFeedback> =
        readableDatabase.rawQuery(
            """
            SELECT
                media_id, name, display_name, media_type, category_id, icon_url, number, extension,
                tvg_id, plot, rating, playable, added_at, kind, occurred_at
            FROM recommendation_feedback
            WHERE profile_id = ?
            ORDER BY occurred_at DESC, media_type, media_id
            """.trimIndent(),
            arrayOf(profileId),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val entry = readEntry(cursor)
                    val kind = runCatching {
                        RecommendationFeedbackKind.valueOf(cursor.getString(13))
                    }.getOrNull() ?: continue
                    put(
                        entry.key,
                        RecommendationFeedback(
                            entry = entry,
                            kind = kind,
                            occurredAtMillis = cursor.getLong(14),
                        ),
                    )
                }
            }
        }

    fun feedbackFor(profileId: String, entry: MediaEntry): RecommendationFeedbackKind? =
        readableDatabase.rawQuery(
            """
            SELECT kind
            FROM recommendation_feedback
            WHERE profile_id = ? AND media_type = ? AND media_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(profileId, entry.type.name, entry.id.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            runCatching { RecommendationFeedbackKind.valueOf(cursor.getString(0)) }.getOrNull()
        }

    /**
     * Un second clic sur le même choix l'annule ; cliquer sur l'opposé le remplace atomiquement.
     */
    fun toggleFeedback(
        profileId: String,
        entry: MediaEntry,
        kind: RecommendationFeedbackKind,
        nowMillis: Long = System.currentTimeMillis(),
    ): RecommendationFeedbackKind? {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val current = feedbackForOnDb(db, profileId, entry)
            val selected = if (current == kind) {
                db.delete(
                    "recommendation_feedback",
                    "profile_id = ? AND media_type = ? AND media_id = ?",
                    arrayOf(profileId, entry.type.name, entry.id.toString()),
                )
                null
            } else {
                val values = ContentValues().apply {
                    put("profile_id", profileId)
                    put("media_type", entry.type.name)
                    put("media_id", entry.id)
                    put("name", entry.name)
                    put("display_name", entry.displayName)
                    put("category_id", entry.categoryId)
                    putNullable("icon_url", entry.iconUrl)
                    put("number", entry.number)
                    put("extension", entry.extension)
                    putNullable("tvg_id", entry.tvgId)
                    putNullable("plot", entry.plot)
                    putNullable("rating", entry.rating)
                    put("playable", if (entry.playable) 1 else 0)
                    putNullable("added_at", entry.addedAtEpochSeconds)
                    put("kind", kind.name)
                    put("occurred_at", nowMillis)
                }
                check(
                    db.insertWithOnConflict(
                        "recommendation_feedback",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    ) != -1L,
                )
                kind
            }
            db.setTransactionSuccessful()
            selected
        } finally {
            db.endTransaction()
        }
    }

    /** Enrichit uniquement les contenus dont la fiche a réellement été chargée. */
    fun saveDetails(profileId: String, details: MediaDetails) {
        val values = ContentValues().apply {
            put("profile_id", profileId)
            put("media_type", details.media.type.name)
            put("media_id", details.media.id)
            putNullable("plot", details.plot ?: details.media.plot)
            putNullable("genre", details.genre)
            putNullable("cast_members", details.cast)
            putNullable("director", details.director)
            putNullable("release_date", details.releaseDate)
            putNullable("country", details.country)
            putNullable("rating", details.rating ?: details.media.rating)
            putNullable("tmdb_id", details.tmdbId)
            put("updated_at", System.currentTimeMillis())
        }
        check(
            writableDatabase.insertWithOnConflict(
                "recommendation_features",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            ) != -1L,
        )
    }

    /**
     * La table contient seulement les fiches déjà consultées : la charger intégralement reste
     * borné par l'usage réel, contrairement au catalogue fournisseur.
     */
    fun features(profileId: String): Map<String, StoredRecommendationFeatures> =
        readableDatabase.rawQuery(
            """
            SELECT media_type, media_id, plot, genre, cast_members, director, release_date, country,
                   rating, tmdb_id, updated_at
            FROM recommendation_features
            WHERE profile_id = ?
            """.trimIndent(),
            arrayOf(profileId),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val type = cursor.getString(0).toMediaType()
                    val mediaId = cursor.getInt(1)
                    put(
                        "${type.name}:$mediaId",
                        StoredRecommendationFeatures(
                            plot = cursor.nullableString(2),
                            genre = cursor.nullableString(3),
                            cast = cursor.nullableString(4),
                            director = cursor.nullableString(5),
                            releaseDate = cursor.nullableString(6),
                            country = cursor.nullableString(7),
                            rating = cursor.nullableDouble(8),
                            tmdbId = cursor.nullableString(9),
                            updatedAtMillis = cursor.getLong(10),
                        ),
                    )
                }
            }
        }

    fun delete(profileId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("recommendation_feedback", "profile_id = ?", arrayOf(profileId))
            db.delete("recommendation_features", "profile_id = ?", arrayOf(profileId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun fileSizeBytes(): Long =
        runCatching { java.io.File(readableDatabase.path).length() }.getOrDefault(0L)

    private fun feedbackForOnDb(
        db: SQLiteDatabase,
        profileId: String,
        entry: MediaEntry,
    ): RecommendationFeedbackKind? = db.rawQuery(
        """
        SELECT kind
        FROM recommendation_feedback
        WHERE profile_id = ? AND media_type = ? AND media_id = ?
        LIMIT 1
        """.trimIndent(),
        arrayOf(profileId, entry.type.name, entry.id.toString()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        runCatching { RecommendationFeedbackKind.valueOf(cursor.getString(0)) }.getOrNull()
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

    private fun String.toMediaType(): MediaType =
        MediaType.entries.firstOrNull { it.name == this } ?: MediaType.Movie

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: Double?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun Cursor.nullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.nullableDouble(index: Int): Double? =
        if (isNull(index)) null else getDouble(index)

    private fun Cursor.nullableLong(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private companion object {
        const val DATABASE_NAME = "recommendations-v1.db"
        const val DATABASE_VERSION = 1
    }
}

internal data class StoredRecommendationFeatures(
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val releaseDate: String?,
    val country: String?,
    val rating: Double?,
    val tmdbId: String?,
    val updatedAtMillis: Long,
) {
    fun merge(entry: MediaEntry): ContentFeatures = ContentFeatures(
        entry = entry,
        plot = plot ?: entry.plot,
        genre = genre,
        cast = cast,
        director = director,
        country = country,
        releaseDate = releaseDate,
        rating = rating ?: entry.rating,
        tmdbId = tmdbId,
    )
}

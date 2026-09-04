package fr.streamia.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import fr.streamia.tv.domain.EpgChannel
import fr.streamia.tv.domain.EpgGuide
import fr.streamia.tv.domain.EpgNowContext
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.withTimeOffset
import java.util.Locale

internal class EpgDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE epg_profiles (
                profile_id TEXT PRIMARY KEY NOT NULL,
                synced_at INTEGER NOT NULL,
                source_url TEXT,
                min_start INTEGER,
                max_end INTEGER,
                program_count INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE epg_channels (
                profile_id TEXT NOT NULL,
                channel_id TEXT NOT NULL,
                lookup_id TEXT NOT NULL,
                display_name TEXT,
                lookup_name TEXT,
                icon_url TEXT,
                PRIMARY KEY (profile_id, channel_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE epg_programs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                profile_id TEXT NOT NULL,
                channel_id TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT,
                start_time INTEGER,
                end_time INTEGER,
                category TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX idx_epg_channel_lookup_id ON epg_channels(profile_id, lookup_id)",
        )
        db.execSQL(
            "CREATE INDEX idx_epg_channel_lookup_name ON epg_channels(profile_id, lookup_name)",
        )
        db.execSQL(
            "CREATE INDEX idx_epg_program_channel_time ON epg_programs(profile_id, channel_id, start_time, end_time)",
        )
        db.execSQL(
            "CREATE INDEX idx_epg_program_window ON epg_programs(profile_id, start_time, end_time)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == newVersion) { "Unsupported EPG database migration $oldVersion -> $newVersion" }
    }

    fun metadata(profileId: String): EpgCacheMetadata? = readableDatabase.rawQuery(
        """
        SELECT synced_at, source_url, min_start, max_end, program_count
        FROM epg_profiles
        WHERE profile_id = ?
        LIMIT 1
        """.trimIndent(),
        arrayOf(profileId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        EpgCacheMetadata(
            syncedAtMillis = cursor.getLong(0),
            sourceUrl = cursor.nullableString(1),
            minStartEpochSeconds = cursor.nullableLong(2),
            maxEndEpochSeconds = cursor.nullableLong(3),
            programCount = cursor.getInt(4),
        )
    }

    fun beginReplace(profileId: String): ReplaceSession {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("epg_programs", "profile_id = ?", arrayOf(profileId))
            db.delete("epg_channels", "profile_id = ?", arrayOf(profileId))
            db.delete("epg_profiles", "profile_id = ?", arrayOf(profileId))
        } catch (error: Throwable) {
            db.endTransaction()
            throw error
        }
        return ReplaceSession(db, profileId)
    }

    inner class ReplaceSession internal constructor(
        private val db: SQLiteDatabase,
        val profileId: String,
    ) : EpgWriteSink {
        private var channelStatement: android.database.sqlite.SQLiteStatement? = null
        private var programStatement: android.database.sqlite.SQLiteStatement? = null
        private var finished = false
        private var programCount = 0
        private var minStart: Long? = null
        private var maxEnd: Long? = null

        override fun writeChannel(channel: EpgChannel) {
            check(!finished) { "ReplaceSession already finished" }
            val statement = channelStatement ?: db.compileStatement(
                """
                INSERT OR REPLACE INTO epg_channels(
                    profile_id, channel_id, lookup_id, display_name, lookup_name, icon_url
                ) VALUES(?,?,?,?,?,?)
                """.trimIndent(),
            ).also { channelStatement = it }
            statement.clearBindings()
            statement.bindString(1, profileId)
            statement.bindString(2, channel.channelId)
            statement.bindString(3, channel.channelId.lookupKey())
            statement.bindNullableString(4, channel.displayName)
            statement.bindNullableString(5, channel.displayName?.lookupKey())
            statement.bindNullableString(6, channel.iconUrl)
            statement.executeInsert()
        }

        override fun writePrograms(programs: List<EpgProgram>) {
            check(!finished) { "ReplaceSession already finished" }
            if (programs.isEmpty()) return
            val statement = programStatement ?: db.compileStatement(
                """
                INSERT INTO epg_programs(
                    profile_id, channel_id, title, description, start_time, end_time, category
                ) VALUES(?,?,?,?,?,?,?)
                """.trimIndent(),
            ).also { programStatement = it }
            programs.forEach { program ->
                val channelId = program.channelId?.takeIf(String::isNotBlank) ?: return@forEach
                val start = program.startEpochSeconds ?: return@forEach
                val end = program.endEpochSeconds ?: return@forEach
                if (end <= start) return@forEach

                statement.clearBindings()
                statement.bindString(1, profileId)
                statement.bindString(2, channelId)
                statement.bindString(3, program.title)
                statement.bindNullableString(4, program.description)
                statement.bindLong(5, start)
                statement.bindLong(6, end)
                statement.bindNullableString(7, program.category)
                statement.executeInsert()
                programCount += 1
                minStart = minStart?.let { minOf(it, start) } ?: start
                maxEnd = maxEnd?.let { maxOf(it, end) } ?: end
            }
        }

        fun commit(sourceUrl: String?) {
            check(!finished) { "ReplaceSession already finished" }
            finished = true
            try {
                val values = ContentValues().apply {
                    put("profile_id", profileId)
                    put("synced_at", System.currentTimeMillis())
                    putNullable("source_url", sourceUrl)
                    putNullable("min_start", minStart)
                    putNullable("max_end", maxEnd)
                    put("program_count", programCount)
                }
                check(db.insertOrThrow("epg_profiles", null, values) != -1L)
                db.setTransactionSuccessful()
            } finally {
                channelStatement?.close()
                programStatement?.close()
                db.endTransaction()
            }
        }

        fun abort() {
            if (finished) return
            finished = true
            channelStatement?.close()
            programStatement?.close()
            db.endTransaction()
        }

        val writtenProgramCount: Int
            get() = programCount
    }

    fun loadNowContext(
        profileId: String,
        entry: MediaEntry,
        nowEpochSeconds: Long,
        offsetHours: Int,
    ): EpgNowContext? {
        val channelId = resolveChannelId(profileId, entry) ?: return null
        val shiftSeconds = offsetHours * 3_600L
        val sourceNow = nowEpochSeconds - shiftSeconds

        val previous = queryProgram(
            """
            SELECT title, description, start_time, end_time, channel_id, category
            FROM epg_programs
            WHERE profile_id = ? AND channel_id = ?
              AND start_time IS NOT NULL AND end_time IS NOT NULL
              AND end_time > start_time
              AND end_time <= ?
            ORDER BY end_time DESC, start_time DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(profileId, channelId, sourceNow.toString()),
        )
        val current = queryProgram(
            """
            SELECT title, description, start_time, end_time, channel_id, category
            FROM epg_programs
            WHERE profile_id = ? AND channel_id = ?
              AND start_time IS NOT NULL AND end_time IS NOT NULL
              AND end_time > start_time
              AND start_time <= ? AND end_time > ?
            ORDER BY start_time DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(profileId, channelId, sourceNow.toString(), sourceNow.toString()),
        )
        val next = queryProgram(
            """
            SELECT title, description, start_time, end_time, channel_id, category
            FROM epg_programs
            WHERE profile_id = ? AND channel_id = ?
              AND start_time IS NOT NULL AND end_time IS NOT NULL
              AND end_time > start_time
              AND start_time > ?
            ORDER BY start_time ASC
            LIMIT 1
            """.trimIndent(),
            arrayOf(profileId, channelId, sourceNow.toString()),
        )

        return EpgNowContext(
            previous = previous?.withTimeOffset(offsetHours),
            current = current?.withTimeOffset(offsetHours),
            next = next?.withTimeOffset(offsetHours),
        )
    }

    fun loadGuide(
        profileId: String,
        displayStartEpochSeconds: Long,
        displayEndEpochSeconds: Long,
        offsetHours: Int,
    ): EpgGuide {
        val shiftSeconds = offsetHours * 3_600L
        val sourceStart = displayStartEpochSeconds - shiftSeconds
        val sourceEnd = displayEndEpochSeconds - shiftSeconds
        val channels = LinkedHashMap<String, MutableChannel>()

        readableDatabase.rawQuery(
            """
            SELECT
                p.channel_id,
                c.display_name,
                c.icon_url,
                p.title,
                p.description,
                p.start_time,
                p.end_time,
                p.category
            FROM epg_programs p
            LEFT JOIN epg_channels c
              ON c.profile_id = p.profile_id AND c.channel_id = p.channel_id
            WHERE p.profile_id = ?
              AND p.start_time IS NOT NULL AND p.end_time IS NOT NULL
              AND p.end_time > p.start_time
              AND p.end_time > ? AND p.start_time < ?
            ORDER BY p.channel_id, p.start_time
            """.trimIndent(),
            arrayOf(profileId, sourceStart.toString(), sourceEnd.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val channelId = cursor.getString(0)
                val target = channels.getOrPut(channelId) {
                    MutableChannel(
                        channelId = channelId,
                        displayName = cursor.nullableString(1),
                        iconUrl = cursor.nullableString(2),
                    )
                }
                target.programs += EpgProgram(
                    title = cursor.getString(3),
                    description = cursor.nullableString(4),
                    startEpochSeconds = cursor.nullableLong(5),
                    endEpochSeconds = cursor.nullableLong(6),
                    channelId = channelId,
                    category = cursor.nullableString(7),
                ).withTimeOffset(offsetHours)
            }
        }

        return EpgGuide(
            channels = channels.mapValues { (_, value) ->
                EpgChannel(
                    channelId = value.channelId,
                    displayName = value.displayName,
                    iconUrl = value.iconUrl,
                    programs = value.programs,
                )
            },
        )
    }

    fun delete(profileId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("epg_programs", "profile_id = ?", arrayOf(profileId))
            db.delete("epg_channels", "profile_id = ?", arrayOf(profileId))
            db.delete("epg_profiles", "profile_id = ?", arrayOf(profileId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun fileSizeBytes(): Long = runCatching { java.io.File(readableDatabase.path).length() }.getOrDefault(0L)

    private fun resolveChannelId(profileId: String, entry: MediaEntry): String? {
        val candidates = buildList {
            entry.tvgId?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            entry.name.trim().takeIf(String::isNotBlank)?.let(::add)
            entry.displayName.trim().takeIf(String::isNotBlank)?.let(::add)
        }.distinct()

        candidates.forEach { candidate ->
            readableDatabase.rawQuery(
                """
                SELECT channel_id
                FROM epg_channels
                WHERE profile_id = ? AND lookup_id = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(profileId, candidate.lookupKey()),
            ).use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        }

        candidates.forEach { candidate ->
            readableDatabase.rawQuery(
                """
                SELECT channel_id
                FROM epg_channels
                WHERE profile_id = ? AND lookup_name = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(profileId, candidate.lookupKey()),
            ).use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        }
        return null
    }

    private fun queryProgram(sql: String, args: Array<String>): EpgProgram? =
        readableDatabase.rawQuery(sql, args).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            EpgProgram(
                title = cursor.getString(0),
                description = cursor.nullableString(1),
                startEpochSeconds = cursor.nullableLong(2),
                endEpochSeconds = cursor.nullableLong(3),
                channelId = cursor.nullableString(4),
                category = cursor.nullableString(5),
            )
        }

    private data class MutableChannel(
        val channelId: String,
        val displayName: String?,
        val iconUrl: String?,
        val programs: MutableList<EpgProgram> = ArrayList(),
    )

    private fun String.lookupKey(): String = trim().lowercase(Locale.ROOT)

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun android.database.sqlite.SQLiteStatement.bindNullableString(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }

    private fun android.database.sqlite.SQLiteStatement.bindNullableLong(index: Int, value: Long?) {
        if (value == null) bindNull(index) else bindLong(index, value)
    }

    private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.nullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)

    private companion object {
        const val DATABASE_NAME = "epg-v1.db"
        const val DATABASE_VERSION = 1
    }
}

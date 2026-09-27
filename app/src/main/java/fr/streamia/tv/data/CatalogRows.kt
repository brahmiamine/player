package fr.streamia.tv.data

import android.content.ContentValues
import android.database.Cursor
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType

// Lecture et écriture des lignes du catalogue : colonnes nullables des curseurs et des requêtes préparées.

internal fun readEntries(cursor: Cursor): List<MediaEntry> = buildList {
    while (cursor.moveToNext()) add(readEntry(cursor))
}

internal fun readEntry(cursor: Cursor): MediaEntry = MediaEntry(
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

internal fun String.toMediaType(): MediaType = MediaType.entries.firstOrNull { it.name == this } ?: MediaType.Live

internal fun ContentValues.putNullable(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}

internal fun ContentValues.putNullable(key: String, value: Int?) {
    if (value == null) putNull(key) else put(key, value)
}

internal fun android.database.sqlite.SQLiteStatement.bindNullableString(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindString(index, value)
}

internal fun android.database.sqlite.SQLiteStatement.bindNullableLong(index: Int, value: Long?) {
    if (value == null) bindNull(index) else bindLong(index, value)
}

internal fun android.database.sqlite.SQLiteStatement.bindNullableDouble(index: Int, value: Double?) {
    if (value == null) bindNull(index) else bindDouble(index, value)
}

internal fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)
internal fun Cursor.nullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
internal fun Cursor.nullableInt(index: Int): Int? = if (isNull(index)) null else getInt(index)
internal fun Cursor.nullableDouble(index: Int): Double? = if (isNull(index)) null else getDouble(index)

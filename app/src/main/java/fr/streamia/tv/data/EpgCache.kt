package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.EpgChannel
import fr.streamia.tv.domain.EpgGuide
import fr.streamia.tv.domain.EpgNowContext
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class EpgCacheMetadata(
    val syncedAtMillis: Long,
    val sourceUrl: String?,
    val minStartEpochSeconds: Long?,
    val maxEndEpochSeconds: Long?,
    val programCount: Int,
) {
    fun isFreshAt(nowMillis: Long, maxAgeMillis: Long): Boolean =
        programCount > 0 &&
            maxAgeMillis > 0 &&
            nowMillis >= syncedAtMillis &&
            nowMillis - syncedAtMillis < maxAgeMillis
}

internal interface EpgWriteSink {
    fun writeChannel(channel: EpgChannel)
    fun writePrograms(programs: List<EpgProgram>)
}

class EpgCache(context: Context) {
    private val database = EpgDatabase(context.applicationContext)

    internal fun metadataOnIo(profileId: String): EpgCacheMetadata? = database.metadata(profileId)

    internal fun beginReplaceOnIo(profileId: String): EpgDatabase.ReplaceSession =
        database.beginReplace(profileId)

    internal fun commitReplaceOnIo(session: EpgDatabase.ReplaceSession, sourceUrl: String?) {
        session.commit(sourceUrl)
    }

    internal fun abortReplaceOnIo(session: EpgDatabase.ReplaceSession) {
        session.abort()
    }

    suspend fun metadata(profileId: String): EpgCacheMetadata? = withContext(Dispatchers.IO) {
        database.metadata(profileId)
    }

    suspend fun nowContext(
        profileId: String,
        entry: MediaEntry,
        nowEpochSeconds: Long,
        offsetHours: Int,
    ): EpgNowContext? = withContext(Dispatchers.IO) {
        database.loadNowContext(profileId, entry, nowEpochSeconds, offsetHours)
    }

    suspend fun guide(
        profileId: String,
        displayStartEpochSeconds: Long,
        displayEndEpochSeconds: Long,
        offsetHours: Int,
    ): EpgGuide = withContext(Dispatchers.IO) {
        database.loadGuide(
            profileId = profileId,
            displayStartEpochSeconds = displayStartEpochSeconds,
            displayEndEpochSeconds = displayEndEpochSeconds,
            offsetHours = offsetHours,
        )
    }

    suspend fun clear(profileId: String) = withContext(Dispatchers.IO) {
        database.delete(profileId)
    }

    fun databaseFileSizeBytes(): Long = database.fileSizeBytes()
}

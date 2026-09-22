package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.beinsports.BeinGuideRows
import fr.streamia.tv.beinsports.BeinGuideSelector
import fr.streamia.tv.beinsports.BeinSportsTvGuideParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class BeinSportsGuideFetchResult(
    val rows: BeinGuideRows,
    val fetchedAtEpochMillis: Long,
    val fromCache: Boolean,
)

internal class BeinSportsGuideRepository(context: Context) {
    private val client = BeinSportsClient()
    private val cache = BeinSportsGuideCache(context)

    suspend fun loadGuide(
        forceRefresh: Boolean,
        maxAgeMillis: Long,
    ): BeinSportsGuideFetchResult = withContext(Dispatchers.IO) {
        val today = LocalDate.now(MENA_ZONE).toString()
        val cached = cache.load()?.takeIf { it.localDate == today }
        val fresh =
            cached != null && System.currentTimeMillis() - cached.fetchedAtEpochMillis < maxAgeMillis

        if (!forceRefresh && fresh && cached != null) {
            return@withContext cached.toFetchResult(fromCache = true)
        }

        val refreshed = runCatching {
            BeinSportsTvGuideParser.parse(client.fetchTvGuideHtml())
        }.mapCatching { schedules ->
            schedules.takeIf { it.isNotEmpty() }
                ?: throw IOException("Aucun programme beIN SPORTS n'a été trouvé dans la grille.")
        }

        refreshed.getOrNull()?.let { schedules ->
            val fetchedAt = System.currentTimeMillis()
            cache.save(schedules, today, fetchedAt)
            return@withContext BeinSportsGuideFetchResult(
                rows = BeinGuideSelector.select(schedules, LocalTime.now(MENA_ZONE)),
                fetchedAtEpochMillis = fetchedAt,
                fromCache = false,
            )
        }

        if (cached != null) {
            return@withContext cached.toFetchResult(fromCache = true)
        }

        throw refreshed.exceptionOrNull()
            ?: IOException("Impossible de récupérer la grille beIN SPORTS.")
    }

    private fun CachedBeinGuideData.toFetchResult(fromCache: Boolean): BeinSportsGuideFetchResult =
        BeinSportsGuideFetchResult(
            rows = BeinGuideSelector.select(schedules, LocalTime.now(MENA_ZONE)),
            fetchedAtEpochMillis = fetchedAtEpochMillis,
            fromCache = fromCache,
        )

    private companion object {
        val MENA_ZONE: ZoneId = ZoneId.of("Asia/Qatar")
    }
}

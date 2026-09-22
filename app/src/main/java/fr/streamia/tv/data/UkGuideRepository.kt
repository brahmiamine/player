package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.ukguide.UkGuideParser
import fr.streamia.tv.ukguide.UkGuideRows
import fr.streamia.tv.ukguide.UkGuideSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalTime
import java.time.ZoneId

data class UkGuideFetchResult(
    val rows: UkGuideRows,
    val fetchedAtEpochMillis: Long,
    val fromCache: Boolean,
)

internal class UkGuideRepository(context: Context) {
    private val client = UkGuideClient()
    private val cache = UkGuideCache(context)

    suspend fun loadGuide(
        forceRefresh: Boolean,
        maxAgeMillis: Long,
    ): UkGuideFetchResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cache.load()?.takeIf { now - it.fetchedAtEpochMillis in 0 until FALLBACK_MAX_AGE_MS }
        val fresh = cached != null && now - cached.fetchedAtEpochMillis < maxAgeMillis

        if (!forceRefresh && fresh && cached != null) {
            return@withContext cached.toFetchResult(fromCache = true)
        }

        val refreshed = runCatching { UkGuideParser.parse(client.fetchGuideHtml()) }
            .mapCatching { schedules ->
                schedules.takeIf { it.isNotEmpty() }
                    ?: throw IOException("Aucun programme UK n'a été trouvé dans la grille tvguideuk.com.")
            }

        refreshed.getOrNull()?.let { schedules ->
            val fetchedAt = System.currentTimeMillis()
            cache.save(schedules, fetchedAt)
            return@withContext UkGuideFetchResult(
                rows = UkGuideSelector.select(schedules, nowInUk()),
                fetchedAtEpochMillis = fetchedAt,
                fromCache = false,
            )
        }

        if (cached != null) {
            return@withContext cached.toFetchResult(fromCache = true)
        }

        throw refreshed.exceptionOrNull() ?: IOException("Impossible de récupérer la grille TV UK.")
    }

    private fun CachedUkGuideData.toFetchResult(fromCache: Boolean): UkGuideFetchResult =
        UkGuideFetchResult(
            rows = UkGuideSelector.select(schedules, nowInUk()),
            fetchedAtEpochMillis = fetchedAtEpochMillis,
            fromCache = fromCache,
        )

    private fun nowInUk(): LocalTime = LocalTime.now(UK_ZONE)

    private companion object {
        val UK_ZONE: ZoneId = ZoneId.of("Europe/London")

        /** Au-delà, une grille en horaires d'horloge locale (sans date) risque de désigner le
         * mauvais programme pour le créneau courant : mieux vaut échouer que servir du faux. */
        const val FALLBACK_MAX_AGE_MS = 3 * 60 * 60_000L
    }
}

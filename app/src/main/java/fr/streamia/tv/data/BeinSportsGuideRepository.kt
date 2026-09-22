package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.beinsports.BeinGuideRows
import fr.streamia.tv.beinsports.BeinGuideSelector
import fr.streamia.tv.beinsports.BeinSportsTvGuideParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.time.Instant

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
        val now = System.currentTimeMillis()
        val cached = cache.load()?.takeIf { now - it.fetchedAtEpochMillis in 0 until FALLBACK_MAX_AGE_MS }
        val fresh = cached != null && now - cached.fetchedAtEpochMillis < maxAgeMillis

        if (!forceRefresh && fresh && cached != null) {
            return@withContext cached.toFetchResult(fromCache = true)
        }

        val refreshed = runCatching {
            val channels = BeinSportsTvGuideParser.parseChannels(client.fetchChannelsJson())
            if (channels.isEmpty()) throw IOException("Aucune chaîne beIN SPORTS n'a été trouvée.")
            val from = Instant.ofEpochMilli(now)
            val eventsJson = client.fetchEventsJson(
                channelIds = channels.map { it.id },
                from = from,
                to = from.plus(GUIDE_WINDOW),
            )
            BeinSportsTvGuideParser.parseEvents(eventsJson, channels)
        }.mapCatching { schedules ->
            schedules.takeIf { it.isNotEmpty() }
                ?: throw IOException("Aucun programme beIN SPORTS n'a été trouvé dans la grille.")
        }

        refreshed.getOrNull()?.let { schedules ->
            val fetchedAt = System.currentTimeMillis()
            cache.save(schedules, fetchedAt)
            return@withContext BeinSportsGuideFetchResult(
                rows = BeinGuideSelector.select(schedules, fetchedAt),
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
            rows = BeinGuideSelector.select(schedules, System.currentTimeMillis()),
            fetchedAtEpochMillis = fetchedAtEpochMillis,
            fromCache = fromCache,
        )

    private companion object {
        /** Fenêtre demandée à l'EPG : couvre le direct et les programmes suivants de la journée. */
        val GUIDE_WINDOW: Duration = Duration.ofHours(24)

        /** Au-delà, un cache ne couvre plus assez de la fenêtre pour servir de repli. */
        const val FALLBACK_MAX_AGE_MS = 12 * 60 * 60 * 1000L
    }
}

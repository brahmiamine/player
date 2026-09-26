package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.beinsports.BeinChannelSchedule
import fr.streamia.tv.beinsports.BeinGuideChannel
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
    private val channelsFile = java.io.File(context.applicationContext.filesDir, CHANNELS_FILE_NAME)

    /**
     * Liste des chaînes beIN, qui ne change presque jamais : gardée [CHANNELS_MAX_AGE_MS] sur
     * disque, elle évite une requête (un aller-retour complet avant même de demander la grille)
     * à chaque actualisation de la grille, toutes les 30 minutes.
     */
    private fun cachedGuideChannels(): List<BeinGuideChannel>? = runCatching {
        channelsFile.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() in 0 until CHANNELS_MAX_AGE_MS }
            ?.readText()
            ?.let(BeinSportsTvGuideParser::parseChannels)
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun fetchGuideChannels(): List<BeinGuideChannel> {
        val json = client.fetchChannelsJson()
        val channels = BeinSportsTvGuideParser.parseChannels(json)
        if (channels.isEmpty()) throw IOException("Aucune chaîne beIN SPORTS n'a été trouvée.")
        runCatching { channelsFile.writeText(json) }
        return channels
    }

    private fun fetchSchedules(from: Instant): List<BeinChannelSchedule> {
        fun schedulesFor(channels: List<BeinGuideChannel>) = BeinSportsTvGuideParser.parseEvents(
            client.fetchEventsJson(channelIds = channels.map { it.id }, from = from, to = from.plus(GUIDE_WINDOW)),
            channels,
        )
        // Grille vide avec la liste gardée sur disque (chaînes renommées ou renumérotées) :
        // nouvel essai avec la liste relue sur le site.
        cachedGuideChannels()?.let { cached -> schedulesFor(cached).takeIf { it.isNotEmpty() }?.let { return it } }
        return schedulesFor(fetchGuideChannels())
    }

    /** Cache assez récent pour que [loadGuide] ne contacte aucun site. */
    suspend fun hasFreshCache(maxAgeMillis: Long): Boolean = withContext(Dispatchers.IO) {
        val age = cache.load()?.let { System.currentTimeMillis() - it.fetchedAtEpochMillis } ?: return@withContext false
        age in 0 until minOf(maxAgeMillis, FALLBACK_MAX_AGE_MS)
    }

    /** Grille déjà sur disque encore exploitable (même limite que le repli) : affichée tout de suite. */
    suspend fun cached(): BeinSportsGuideFetchResult? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache.load()?.takeIf { now - it.fetchedAtEpochMillis in 0 until FALLBACK_MAX_AGE_MS }?.toFetchResult(fromCache = true)
    }

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
            fetchSchedules(Instant.ofEpochMilli(now))
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

        const val CHANNELS_FILE_NAME = "bein-sports-channels.json"
        const val CHANNELS_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}

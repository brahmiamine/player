package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.tvprogramme.FallbackGuide
import fr.streamia.tv.tvprogramme.ProgrammeTelevisionOrgParser
import fr.streamia.tv.tvprogramme.ProgrammeTvNetParser
import fr.streamia.tv.tvprogramme.TvProgrammeNowItem
import fr.streamia.tv.tvprogramme.TvProgrammeNowParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class TvProgrammeNowFetchResult(
    val programmes: List<TvProgrammeNowItem>,
    val fetchedAtEpochMillis: Long,
    val fromCache: Boolean,
)

internal class TvProgrammeNowRepository(context: Context) {
    private val client = TvProgrammeClient()
    private val cache = TvProgrammeNowCache(context)
    @Volatile private var lastFailureAtEpochMillis = 0L

    /**
     * Le cache contient toute la grille : « en ce moment » est recalculé localement à chaque appel,
     * et le site n'est re-téléchargé que quand le cache a plus de [maxAgeMillis] ou ne couvre plus
     * l'heure actuelle. Après un échec (403 anti-scraping, réseau), on attend [FAILURE_BACKOFF_MS]
     * avant de réessayer au lieu d'insister toutes les 2 minutes.
     */
    /** Cache assez récent (et couvrant l'heure actuelle) pour que [loadNow] ne contacte aucun site. */
    suspend fun hasFreshCache(maxAgeMillis: Long): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        isFresh(cache.load(), now, maxAgeMillis)
    }

    private fun isFresh(cached: CachedTvProgrammeNowData?, now: Long, maxAgeMillis: Long) =
        cached != null && now - cached.fetchedAtEpochMillis < maxAgeMillis &&
            TvProgrammeNowParser.onAir(cached.programmes, now).isNotEmpty()

    /**
     * Grille déjà sur disque, quel que soit son âge, si elle couvre l'heure actuelle : affichée
     * tout de suite pendant que [loadNow] la retélécharge (le « en ce moment » est recalculé ici).
     */
    suspend fun cached(): TvProgrammeNowFetchResult? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cache.load() ?: return@withContext null
        TvProgrammeNowParser.onAir(cached.programmes, now).takeIf { it.isNotEmpty() }
            ?.let { TvProgrammeNowFetchResult(it, cached.fetchedAtEpochMillis, fromCache = true) }
    }

    /** Réseau revenu : l'attente après un échec n'a plus lieu d'être. */
    fun clearFailureBackoff() {
        lastFailureAtEpochMillis = 0L
    }

    suspend fun loadNow(forceRefresh: Boolean, maxAgeMillis: Long): TvProgrammeNowFetchResult =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = cache.load()
            val cachedNow = cached?.let { TvProgrammeNowParser.onAir(it.programmes, now) }.orEmpty()
            val fromCache = cached?.let { TvProgrammeNowFetchResult(cachedNow, it.fetchedAtEpochMillis, fromCache = true) }
            val fresh = isFresh(cached, now, maxAgeMillis)

            if (!forceRefresh && fresh) return@withContext fromCache!!
            if (!forceRefresh && now - lastFailureAtEpochMillis < FAILURE_BACKOFF_MS) {
                return@withContext fromCache?.takeIf { cachedNow.isNotEmpty() }
                    ?: throw IOException("Programme TV en direct indisponible, nouvel essai plus tard.")
            }

            val refreshed = fetchSchedule(now)

            refreshed.getOrNull()?.let { schedule ->
                cache.save(schedule, now)
                return@withContext TvProgrammeNowFetchResult(TvProgrammeNowParser.onAir(schedule, now), now, fromCache = false)
            }

            lastFailureAtEpochMillis = now
            fromCache?.takeIf { cachedNow.isNotEmpty() }?.let { return@withContext it }

            throw refreshed.exceptionOrNull()
                ?: IOException("Impossible de récupérer les programmes TV français en direct.")
        }

    /** Premier site qui donne au moins un programme en cours : tv-programme.com puis les secours. */
    private fun fetchSchedule(now: Long): Result<List<TvProgrammeNowItem>> {
        var failure: Throwable? = null
        SOURCES.forEach { (url, parse) ->
            runCatching { parse(client.fetch(url), now) }
                .onSuccess { if (TvProgrammeNowParser.onAir(it, now).isNotEmpty()) return Result.success(it) }
                .onFailure { failure = it }
        }
        return Result.failure(failure ?: IOException("Aucun programme TV en direct n'a été trouvé."))
    }

    private companion object {
        const val FAILURE_BACKOFF_MS = 10 * 60_000L
        val SOURCES: List<Pair<String, (String, Long) -> List<TvProgrammeNowItem>>> = listOf(
            TvProgrammeClient.NOW_URL to TvProgrammeNowParser::parseSchedule,
            ProgrammeTvNetParser.NOW_URL to { html, now -> FallbackGuide.schedule(ProgrammeTvNetParser.slots(html), now) },
            ProgrammeTelevisionOrgParser.NOW_URL to { html, now -> FallbackGuide.schedule(ProgrammeTelevisionOrgParser.slots(html), now) },
        )
    }
}

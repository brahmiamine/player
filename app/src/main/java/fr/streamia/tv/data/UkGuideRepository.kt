package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.ukguide.UkChannelSchedule
import fr.streamia.tv.ukguide.UkGuideParser
import fr.streamia.tv.ukguide.UkGuideRows
import fr.streamia.tv.ukguide.UkGuideSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

data class UkGuideFetchResult(
    val rows: UkGuideRows,
    val fetchedAtEpochMillis: Long,
    val fromCache: Boolean,
)

internal class UkGuideRepository(context: Context) {
    private val client = UkGuideClient()
    private val cache = UkGuideCache(context)

    /** Cache assez récent pour que [loadGuide] ne contacte aucun site. */
    suspend fun hasFreshCache(maxAgeMillis: Long): Boolean = withContext(Dispatchers.IO) {
        val age = cache.load()?.let { System.currentTimeMillis() - it.fetchedAtEpochMillis } ?: return@withContext false
        age in 0 until minOf(maxAgeMillis, FALLBACK_MAX_AGE_MS)
    }

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

        val refreshed = runCatching { fetchAllSchedules() }
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

    /**
     * La page d'accueil ne rend que les toutes premières chaînes (défilement infini côté client) :
     * on pagine plutôt l'API `guide-fragment.php` du site pour chaque genre affiché dans sa
     * navigation (Sports, Kids, Movies…), afin de couvrir toute la grille et pas seulement les
     * quelques chaînes visibles à l'écran — sans quoi une chaîne de la playlist de l'utilisateur en
     * dehors de ce premier lot ne serait jamais associée.
     */
    private fun fetchAllSchedules(): List<UkChannelSchedule> {
        val homepageHtml = client.fetchGuideHtml()
        val categories = UkGuideParser.parseCategories(homepageHtml)
        if (categories.isEmpty()) {
            // Repli si la navigation par genre a changé de forme : au moins les chaînes déjà
            // rendues côté serveur restent exploitables plutôt que d'échouer entièrement.
            return UkGuideParser.parse(homepageHtml)
        }

        val schedulesByChannel = linkedMapOf<String, UkChannelSchedule>()
        var requestBudget = MAX_FRAGMENT_REQUESTS
        for (category in categories) {
            var offset = 0
            while (requestBudget > 0) {
                requestBudget--
                val page = runCatching {
                    UkGuideParser.parseFragment(client.fetchCategoryFragmentJson(category, offset, PAGE_LIMIT))
                }.getOrNull() ?: break

                page.schedules.forEach { schedule ->
                    schedulesByChannel.putIfAbsent(schedule.channelName.lowercase(Locale.ROOT), schedule)
                }

                if (!page.hasMore || page.nextOffset <= offset) break
                offset = page.nextOffset
            }
        }
        return schedulesByChannel.values.toList()
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

        /** Nombre de chaînes par page, aligné sur le maximum que le site s'accorde lui-même
         * (`Math.min(24, …)` dans son propre code de défilement infini). */
        const val PAGE_LIMIT = 24

        /** Garde-fou contre une pagination qui ne se termine jamais (ex. `hasMore` toujours vrai) :
         * la grille entière tient en une quinzaine de requêtes, cette limite ne mord donc qu'en cas
         * de réponse inattendue du site. */
        const val MAX_FRAGMENT_REQUESTS = 60

        /** Au-delà, une grille en horaires d'horloge locale (sans date) risque de désigner le
         * mauvais programme pour le créneau courant : mieux vaut échouer que servir du faux. */
        const val FALLBACK_MAX_AGE_MS = 3 * 60 * 60_000L
    }
}

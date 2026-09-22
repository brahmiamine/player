package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.tvprogramme.TvProgrammeNowItem
import fr.streamia.tv.tvprogramme.TvProgrammeNowParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

data class TvProgrammeNowFetchResult(
    val programmes: List<TvProgrammeNowItem>,
    val fetchedAtEpochMillis: Long,
    val fromCache: Boolean,
)

internal class TvProgrammeNowRepository(context: Context) {
    private val client = TvProgrammeClient()
    private val cache = TvProgrammeNowCache(context)

    suspend fun loadNow(forceRefresh: Boolean, maxAgeMillis: Long): TvProgrammeNowFetchResult =
        withContext(Dispatchers.IO) {
            val today = LocalDate.now(PARIS_ZONE).toString()
            val cached = cache.load()?.takeIf { it.localDate == today }
            val fresh = cached != null && System.currentTimeMillis() - cached.fetchedAtEpochMillis < maxAgeMillis

            if (!forceRefresh && fresh && cached != null) {
                return@withContext TvProgrammeNowFetchResult(
                    programmes = cached.programmes,
                    fetchedAtEpochMillis = cached.fetchedAtEpochMillis,
                    fromCache = true,
                )
            }

            val refreshed = runCatching { TvProgrammeNowParser.parse(client.fetchNowHtml()) }
                .mapCatching { programmes ->
                    programmes.takeIf { it.isNotEmpty() }
                        ?: throw IOException("Aucun programme TV en direct n'a été trouvé dans la page.")
                }

            refreshed.getOrNull()?.let { programmes ->
                val fetchedAt = System.currentTimeMillis()
                cache.save(programmes, today, fetchedAt)
                return@withContext TvProgrammeNowFetchResult(programmes, fetchedAt, fromCache = false)
            }

            if (cached != null) {
                return@withContext TvProgrammeNowFetchResult(
                    programmes = cached.programmes,
                    fetchedAtEpochMillis = cached.fetchedAtEpochMillis,
                    fromCache = true,
                )
            }

            throw refreshed.exceptionOrNull()
                ?: IOException("Impossible de récupérer les programmes TV français en direct.")
        }

    private companion object {
        val PARIS_ZONE: ZoneId = ZoneId.of("Europe/Paris")
    }
}

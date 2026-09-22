package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.tvprogramme.TvProgrammeItem
import fr.streamia.tv.tvprogramme.TvProgrammeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

data class TvProgrammeFetchResult(
    val programmes: List<TvProgrammeItem>,
    val fetchedAtEpochMillis: Long,
    val fromCache: Boolean,
)

internal class TvProgrammeRepository(context: Context) {
    private val client = TvProgrammeClient()
    private val cache = TvProgrammeCache(context)

    suspend fun loadTonight(forceRefresh: Boolean, maxAgeMillis: Long): TvProgrammeFetchResult =
        withContext(Dispatchers.IO) {
            val today = LocalDate.now(ZoneId.of("Europe/Paris")).toString()
            val cached = cache.load()?.takeIf { it.localDate == today }
            val fresh = cached != null && System.currentTimeMillis() - cached.fetchedAtEpochMillis < maxAgeMillis

            if (!forceRefresh && fresh && cached != null) {
                return@withContext TvProgrammeFetchResult(
                    programmes = cached.programmes,
                    fetchedAtEpochMillis = cached.fetchedAtEpochMillis,
                    fromCache = true,
                )
            }

            val refreshed = runCatching { TvProgrammeParser.parse(client.fetchTonightHtml()) }
                .mapCatching { programmes ->
                    programmes.takeIf { it.isNotEmpty() }
                        ?: throw IOException("Aucun programme TV n'a été trouvé dans la page.")
                }

            refreshed.getOrNull()?.let { programmes ->
                val fetchedAt = System.currentTimeMillis()
                cache.save(programmes, today, fetchedAt)
                return@withContext TvProgrammeFetchResult(programmes, fetchedAt, fromCache = false)
            }

            if (cached != null) {
                return@withContext TvProgrammeFetchResult(
                    programmes = cached.programmes,
                    fetchedAtEpochMillis = cached.fetchedAtEpochMillis,
                    fromCache = true,
                )
            }

            throw refreshed.exceptionOrNull() ?: IOException("Impossible de récupérer le programme TV de ce soir.")
        }
}

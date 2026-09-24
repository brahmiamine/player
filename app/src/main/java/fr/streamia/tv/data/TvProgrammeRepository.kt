package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.tvprogramme.FallbackGuide
import fr.streamia.tv.tvprogramme.ProgrammeTelevisionOrgParser
import fr.streamia.tv.tvprogramme.ProgrammeTvNetParser
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

            val refreshed = fetchTonight()

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

    /** Premier site qui donne des programmes : tv-programme.com puis les secours. */
    private fun fetchTonight(): Result<List<TvProgrammeItem>> {
        var failure: Throwable? = null
        SOURCES.forEach { (url, parse) ->
            runCatching { parse(client.fetch(url)) }
                .onSuccess { if (it.isNotEmpty()) return Result.success(it) }
                .onFailure { failure = it }
        }
        return Result.failure(failure ?: IOException("Aucun programme TV n'a été trouvé."))
    }

    private companion object {
        val SOURCES: List<Pair<String, (String) -> List<TvProgrammeItem>>> = listOf(
            TvProgrammeClient.TONIGHT_URL to TvProgrammeParser::parse,
            ProgrammeTvNetParser.TONIGHT_URL to { html -> FallbackGuide.tonight(ProgrammeTvNetParser.slots(html)) },
            ProgrammeTelevisionOrgParser.TONIGHT_URL to { html -> FallbackGuide.tonight(ProgrammeTelevisionOrgParser.slots(html)) },
        )
    }
}

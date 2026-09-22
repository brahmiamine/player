package fr.streamia.tv.tvprogramme

import fr.streamia.tv.domain.MediaEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Programme actuellement diffusé sur une chaîne selon tv-programme.com. Les bornes sont des
 * instants absolus (le site expose désormais `data-starttime`/`data-endtime` en secondes UTC) :
 * la détection « en cours » ne dépend donc plus d'une comparaison d'heures locales ni d'une
 * hypothèse sur le passage de minuit.
 */
data class TvProgrammeNowItem(
    val channelName: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val title: String,
    val imageUrl: String? = null,
) {
    val startTime: String
        get() = formatClock(startEpochMillis)

    val endTime: String
        get() = formatClock(endEpochMillis)

    val timeRangeLabel: String
        get() = "$startTime - $endTime"

    fun isOnAirAt(nowEpochMillis: Long): Boolean =
        endEpochMillis > startEpochMillis && nowEpochMillis >= startEpochMillis && nowEpochMillis < endEpochMillis

    fun progressAt(nowEpochMillis: Long): Float? {
        if (!isOnAirAt(nowEpochMillis)) return null
        val total = endEpochMillis - startEpochMillis
        val elapsed = nowEpochMillis - startEpochMillis
        return (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
}

data class ResolvedTvProgrammeNowItem(
    val programme: TvProgrammeNowItem,
    val channel: MediaEntry,
) {
    val fingerprint: String =
        channel.key + ":" + programme.startEpochMillis + ":" + programme.title.lowercase().hashCode()
}

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val PARIS_ZONE: ZoneId = ZoneId.of("Europe/Paris")

private fun formatClock(epochMillis: Long): String =
    CLOCK_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(PARIS_ZONE))

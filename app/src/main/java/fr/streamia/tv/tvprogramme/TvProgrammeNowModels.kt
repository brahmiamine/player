package fr.streamia.tv.tvprogramme

import fr.streamia.tv.domain.MediaEntry
import java.time.LocalTime

/** Programme actuellement diffusé sur une chaîne selon tv-programme.com. */
data class TvProgrammeNowItem(
    val channelName: String,
    val startTime: String,
    val endTime: String?,
    val title: String,
    val imageUrl: String? = null,
) {
    val timeRangeLabel: String
        get() = endTime?.let { "$startTime - $it" } ?: startTime

    fun progressAt(now: LocalTime): Float? {
        val start = startTime.toLocalTimeOrNull() ?: return null
        val end = endTime?.toLocalTimeOrNull() ?: return null
        val totalMinutes = minutesForward(start, end)
        if (totalMinutes <= 0) return null

        val elapsedMinutes = minutesForward(start, now)
        if (elapsedMinutes > totalMinutes) return null
        return (elapsedMinutes.toFloat() / totalMinutes.toFloat()).coerceIn(0f, 1f)
    }
}

data class ResolvedTvProgrammeNowItem(
    val programme: TvProgrammeNowItem,
    val channel: MediaEntry,
) {
    val fingerprint: String =
        channel.key + ":" + programme.startTime + ":" + programme.title.lowercase().hashCode()
}

private fun String.toLocalTimeOrNull(): LocalTime? =
    runCatching { LocalTime.parse(this) }.getOrNull()

private fun minutesForward(start: LocalTime, end: LocalTime): Int {
    val startMinutes = start.hour * 60 + start.minute
    val endMinutes = end.hour * 60 + end.minute
    return (endMinutes - startMinutes + MINUTES_PER_DAY) % MINUTES_PER_DAY
}

private const val MINUTES_PER_DAY = 24 * 60

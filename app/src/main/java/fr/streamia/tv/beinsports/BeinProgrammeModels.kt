package fr.streamia.tv.beinsports

import fr.streamia.tv.domain.MediaEntry
import java.time.LocalTime

data class BeinProgrammeItem(
    val channelName: String,
    val category: String?,
    val title: String,
    val startTime: String,
    val endTime: String,
    val isLive: Boolean = false,
    val imageUrl: String? = null,
) {
    val timeRangeLabel: String
        get() = "$startTime - $endTime"

    fun isOnAirAt(now: LocalTime): Boolean {
        val start = startTime.toLocalTimeOrNull() ?: return false
        val end = endTime.toLocalTimeOrNull() ?: return false
        if (start == end) return false
        return if (start < end) {
            now >= start && now < end
        } else {
            now >= start || now < end
        }
    }

    fun progressAt(now: LocalTime): Float? {
        if (!isOnAirAt(now)) return null
        val start = startTime.toLocalTimeOrNull() ?: return null
        val end = endTime.toLocalTimeOrNull() ?: return null
        val total = minutesForward(start, end)
        if (total <= 0) return null
        val elapsed = minutesForward(start, now)
        return (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
}

data class BeinChannelSchedule(
    val channelName: String,
    val programmes: List<BeinProgrammeItem>,
)

data class BeinGuideRows(
    val current: List<BeinProgrammeItem>,
    val next: List<BeinProgrammeItem>,
)

data class ResolvedBeinProgrammeItem(
    val programme: BeinProgrammeItem,
    val channel: MediaEntry,
) {
    val fingerprint: String =
        channel.key + ":" + programme.startTime + ":" + programme.title.lowercase().hashCode()
}

object BeinGuideSelector {
    fun select(
        schedules: List<BeinChannelSchedule>,
        now: LocalTime,
    ): BeinGuideRows {
        val current = mutableListOf<BeinProgrammeItem>()
        val next = mutableListOf<BeinProgrammeItem>()

        schedules.forEach { schedule ->
            val onAir = schedule.programmes.firstOrNull { it.isOnAirAt(now) }
            if (onAir != null) current += onAir

            val upcoming = schedule.programmes
                .asSequence()
                .filterNot { it === onAir }
                .mapNotNull { programme ->
                    val start = programme.startTime.toLocalTimeOrNull() ?: return@mapNotNull null
                    val delta = minutesForward(now, start)
                    if (delta in 1..MAX_NEXT_LOOKAHEAD_MINUTES) programme to delta else null
                }
                .minByOrNull { (_, delta) -> delta }
                ?.first

            if (upcoming != null) next += upcoming
        }

        return BeinGuideRows(current = current, next = next)
    }

    private const val MAX_NEXT_LOOKAHEAD_MINUTES = 12 * 60
}

private fun String.toLocalTimeOrNull(): LocalTime? =
    runCatching { LocalTime.parse(this) }.getOrNull()

private fun minutesForward(start: LocalTime, end: LocalTime): Int {
    val startMinutes = start.hour * 60 + start.minute
    val endMinutes = end.hour * 60 + end.minute
    return (endMinutes - startMinutes + MINUTES_PER_DAY) % MINUTES_PER_DAY
}

private const val MINUTES_PER_DAY = 24 * 60

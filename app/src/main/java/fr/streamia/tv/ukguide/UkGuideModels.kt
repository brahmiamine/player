package fr.streamia.tv.ukguide

import fr.streamia.tv.domain.MediaEntry
import java.time.LocalTime

/**
 * Programme d'une chaîne britannique selon tvguideuk.com. Le site n'expose que des heures
 * d'horloge locale (Europe/London), sans date : le programme en cours et le suivant sont donc
 * déterminés par comparaison d'heures, avec gestion explicite du passage de minuit (ex. un
 * programme "22:40-00:45").
 */
data class UkProgrammeItem(
    val channelName: String,
    val startTime: String,
    val endTime: String,
    val title: String,
    val imageUrl: String? = null,
) {
    val timeRangeLabel: String
        get() = "$startTime - $endTime"

    fun isOnAirAt(now: LocalTime): Boolean =
        isInsideInterval(now, startTime.toLocalTimeOrThrow(), endTime.toLocalTimeOrThrow())

    fun progressAt(now: LocalTime): Float? {
        if (!isOnAirAt(now)) return null
        val start = startTime.toLocalTimeOrThrow()
        val end = endTime.toLocalTimeOrThrow()
        val totalMinutes = minutesForward(start, end)
        if (totalMinutes <= 0) return null
        val elapsedMinutes = minutesForward(start, now)
        return (elapsedMinutes.toFloat() / totalMinutes.toFloat()).coerceIn(0f, 1f)
    }

    private fun isInsideInterval(now: LocalTime, start: LocalTime, end: LocalTime): Boolean =
        if (start <= end) now >= start && now < end else now >= start || now < end

    private fun minutesForward(start: LocalTime, end: LocalTime): Int {
        val startMinutes = start.hour * 60 + start.minute
        val endMinutes = end.hour * 60 + end.minute
        return (endMinutes - startMinutes + MINUTES_PER_DAY) % MINUTES_PER_DAY
    }

    private fun String.toLocalTimeOrThrow(): LocalTime {
        val (hour, minute) = split(':').map(String::toInt)
        return LocalTime.of(hour, minute)
    }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

data class UkChannelSchedule(
    val channelName: String,
    val programmes: List<UkProgrammeItem>,
)

data class UkGuideRows(
    val current: List<UkProgrammeItem>,
    val next: List<UkProgrammeItem>,
)

data class ResolvedUkProgrammeItem(
    val programme: UkProgrammeItem,
    val channel: MediaEntry,
) {
    val fingerprint: String =
        channel.key + ":" + programme.startTime + ":" + programme.title.lowercase().hashCode()
}

object UkGuideSelector {
    /**
     * Par chaîne : le programme dont le créneau [début, fin[ contient [now], puis celui qui le
     * suit immédiatement dans la grille (pas nécessairement "après maintenant" au sens large,
     * seulement le créneau suivant connu pour cette chaîne).
     */
    fun select(schedules: List<UkChannelSchedule>, now: LocalTime): UkGuideRows {
        val current = mutableListOf<UkProgrammeItem>()
        val next = mutableListOf<UkProgrammeItem>()

        schedules.forEach { schedule ->
            val programmes = schedule.programmes
            val currentIndex = programmes.indexOfFirst { it.isOnAirAt(now) }
            if (currentIndex < 0) return@forEach
            current += programmes[currentIndex]
            programmes.getOrNull(currentIndex + 1)?.let { next += it }
        }

        return UkGuideRows(current = current, next = next)
    }
}

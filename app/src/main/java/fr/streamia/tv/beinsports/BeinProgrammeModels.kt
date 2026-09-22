package fr.streamia.tv.beinsports

import fr.streamia.tv.domain.MediaEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Programme de la grille beIN SPORTS. Les bornes sont des instants absolus (UTC) fournis par
 * l'EPG : la détection « en cours / suivant » ne dépend donc ni du fuseau MENA ni du passage de
 * minuit, et l'affichage se fait dans le fuseau de l'appareil.
 */
data class BeinProgrammeItem(
    val channelName: String,
    val category: String?,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val isLive: Boolean = false,
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
        channel.key + ":" + programme.startEpochMillis + ":" + programme.title.lowercase().hashCode()
}

object BeinGuideSelector {
    /**
     * Par chaîne : le programme dont la plage [début, fin[ contient [nowEpochMillis], puis le
     * programme qui démarre ensuite (le plus proche début strictement postérieur à maintenant).
     */
    fun select(
        schedules: List<BeinChannelSchedule>,
        nowEpochMillis: Long,
    ): BeinGuideRows {
        val current = mutableListOf<BeinProgrammeItem>()
        val next = mutableListOf<BeinProgrammeItem>()

        schedules.forEach { schedule ->
            val onAir = schedule.programmes
                .filter { it.isOnAirAt(nowEpochMillis) }
                .maxByOrNull { it.startEpochMillis }
            if (onAir != null) current += onAir

            val upcoming = schedule.programmes
                .asSequence()
                .filter { it !== onAir && it.endEpochMillis > it.startEpochMillis }
                .filter { it.startEpochMillis > nowEpochMillis }
                .filter { it.startEpochMillis - nowEpochMillis <= MAX_NEXT_LOOKAHEAD_MILLIS }
                .minByOrNull { it.startEpochMillis }

            if (upcoming != null) next += upcoming
        }

        return BeinGuideRows(current = current, next = next)
    }

    private const val MAX_NEXT_LOOKAHEAD_MILLIS = 12 * 60 * 60 * 1000L
}

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatClock(epochMillis: Long): String =
    CLOCK_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

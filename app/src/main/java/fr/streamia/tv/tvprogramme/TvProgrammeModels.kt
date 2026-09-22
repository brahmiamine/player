package fr.streamia.tv.tvprogramme

import fr.streamia.tv.domain.MediaEntry

/** Programme du soir tel qu'extrait de tv-programme.com. */
data class TvProgrammeItem(
    val channelName: String,
    val time: String,
    val title: String,
    val imageUrl: String? = null,
)

/** Programme associé à une chaîne réellement disponible dans la playlist active. */
data class ResolvedTvProgrammeItem(
    val programme: TvProgrammeItem,
    val channel: MediaEntry,
) {
    val fingerprint: String =
        channel.key + ":" + programme.time + ":" + programme.title.lowercase().hashCode()
}

package fr.streamia.tv.player

import fr.streamia.tv.domain.MediaType

data class PlaybackTransportPreference(
    val scheme: String? = null,
    val liveExtension: String? = null,
)

object PlaybackUrlStrategy {
    fun candidates(
        initialUrl: String,
        type: MediaType,
        preference: PlaybackTransportPreference = PlaybackTransportPreference(),
    ): List<String> = listOf(initialUrl)
}

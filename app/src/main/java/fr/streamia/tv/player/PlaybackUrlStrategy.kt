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
    ): List<String> {
        val currentScheme = initialUrl.substringBefore("://").lowercase().takeIf { it == "http" || it == "https" }
            ?: return listOf(initialUrl)
        val schemes = buildList {
            preference.scheme?.lowercase()?.takeIf { it == "http" || it == "https" }?.let(::add)
            add(currentScheme)
            add(if (currentScheme == "http") "https" else "http")
        }.distinct()

        if (type != MediaType.Live) {
            return schemes.map { scheme -> replaceScheme(initialUrl, scheme) }.distinct()
        }

        val currentExtension = liveExtension(initialUrl)
        val extensions = buildList {
            preference.liveExtension?.lowercase()?.takeIf { it == "ts" || it == "m3u8" }?.let(::add)
            currentExtension?.let(::add)
            when (currentExtension) {
                "ts" -> add("m3u8")
                "m3u8" -> add("ts")
                else -> {
                    add("ts")
                    add("m3u8")
                }
            }
        }.distinct()

        return buildList {
            for (scheme in schemes) {
                for (extension in extensions) {
                    add(replaceLiveExtension(replaceScheme(initialUrl, scheme), extension))
                }
            }
        }.distinct()
    }

    fun liveExtension(url: String): String? = LIVE_EXTENSION
        .find(url)
        ?.groupValues
        ?.getOrNull(1)
        ?.lowercase()

    private fun replaceScheme(url: String, scheme: String): String =
        url.replaceFirst(SCHEME, "$scheme://")

    private fun replaceLiveExtension(url: String, extension: String): String =
        if (LIVE_EXTENSION.containsMatchIn(url)) {
            url.replace(LIVE_EXTENSION) { ".${extension}${it.groupValues[2]}" }
        } else {
            url
        }

    private val SCHEME = Regex("(?i)^https?://")
    private val LIVE_EXTENSION = Regex("(?i)\\.(ts|m3u8)([?#]|$)")
}

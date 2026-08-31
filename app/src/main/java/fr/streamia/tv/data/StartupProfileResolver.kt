package fr.streamia.tv.data

fun resolveStartupProfileId(
    availableProfileIds: List<String>,
    playbackProfileId: String?,
    activeProfileId: String?,
    autoOpenDisabled: Boolean,
): String? {
    if (autoOpenDisabled) return null
    val available = availableProfileIds.toSet()
    return playbackProfileId?.takeIf(available::contains)
        ?: activeProfileId?.takeIf(available::contains)
        ?: availableProfileIds.firstOrNull()
}

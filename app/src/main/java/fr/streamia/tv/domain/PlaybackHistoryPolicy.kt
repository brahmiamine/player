package fr.streamia.tv.domain

/**
 * A VOD item becomes meaningful history after five seconds of actual playback.
 * Live TV keeps its existing history behavior because it has no resumable position.
 */
const val MIN_VOD_HISTORY_POSITION_MS = 5_000L

fun shouldRecordPlaybackInHistory(type: MediaType, positionMs: Long): Boolean =
    type == MediaType.Live || positionMs >= MIN_VOD_HISTORY_POSITION_MS

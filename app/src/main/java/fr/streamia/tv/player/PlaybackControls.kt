package fr.streamia.tv.player

import fr.streamia.tv.domain.MediaType

enum class PlaybackRemoteButton {
    Up,
    Down,
    Left,
    Right,
    Ok,
    Menu,
    Settings,
    PlayPause,
    Rewind,
    FastForward,
    Info,
    Other,
}

enum class PlaybackRemoteAction {
    None,
    ZapPrevious,
    ZapNext,
    OpenLivePicker,
    OpenSettings,
    ToggleHud,
    TogglePlayback,
    SeekBackward,
    SeekForward,
}

fun playbackRemoteAction(type: MediaType, button: PlaybackRemoteButton): PlaybackRemoteAction =
    when (button) {
        PlaybackRemoteButton.Ok -> if (type == MediaType.Live) PlaybackRemoteAction.OpenLivePicker else PlaybackRemoteAction.TogglePlayback
        PlaybackRemoteButton.PlayPause -> PlaybackRemoteAction.TogglePlayback
        PlaybackRemoteButton.Settings -> PlaybackRemoteAction.OpenSettings
        PlaybackRemoteButton.Menu -> if (type == MediaType.Live) PlaybackRemoteAction.OpenLivePicker else PlaybackRemoteAction.None
        PlaybackRemoteButton.Up -> if (type == MediaType.Live) PlaybackRemoteAction.ZapNext else PlaybackRemoteAction.None
        PlaybackRemoteButton.Down -> if (type == MediaType.Live) PlaybackRemoteAction.ZapPrevious else PlaybackRemoteAction.None
        PlaybackRemoteButton.Left -> if (type == MediaType.Live) PlaybackRemoteAction.OpenLivePicker else PlaybackRemoteAction.SeekBackward
        PlaybackRemoteButton.Right -> if (type == MediaType.Live) PlaybackRemoteAction.OpenSettings else PlaybackRemoteAction.SeekForward
        PlaybackRemoteButton.Rewind -> if (type == MediaType.Live) PlaybackRemoteAction.None else PlaybackRemoteAction.SeekBackward
        PlaybackRemoteButton.FastForward -> if (type == MediaType.Live) PlaybackRemoteAction.None else PlaybackRemoteAction.SeekForward
        PlaybackRemoteButton.Info -> if (type == MediaType.Live) PlaybackRemoteAction.ToggleHud else PlaybackRemoteAction.OpenSettings
        PlaybackRemoteButton.Other -> PlaybackRemoteAction.None
    }

fun resolveSeekPosition(currentPositionMs: Long, durationMs: Long, deltaMs: Long): Long =
    (currentPositionMs.coerceAtLeast(0L) + deltaMs).coerceIn(
        minimumValue = 0L,
        maximumValue = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
    )

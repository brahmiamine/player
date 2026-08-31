package fr.streamia.tv.ui

enum class LiveChannelConfirmAction {
    Preview,
    Fullscreen,
    Ignore,
}

fun liveChannelConfirmAction(
    previewKey: String?,
    channelKey: String,
    fullscreenPending: Boolean = false,
): LiveChannelConfirmAction =
    when {
        fullscreenPending -> LiveChannelConfirmAction.Ignore
        previewKey == channelKey -> LiveChannelConfirmAction.Fullscreen
        else -> LiveChannelConfirmAction.Preview
    }

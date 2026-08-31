package fr.streamia.tv.ui

enum class LiveChannelConfirmAction {
    Preview,
    Fullscreen,
}

fun liveChannelConfirmAction(previewKey: String?, channelKey: String): LiveChannelConfirmAction =
    if (previewKey == channelKey) LiveChannelConfirmAction.Fullscreen else LiveChannelConfirmAction.Preview

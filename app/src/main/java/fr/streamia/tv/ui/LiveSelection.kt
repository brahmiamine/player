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


/**
 * Décide si l'aperçu Live doit remplacer le média actuellement attaché au lecteur partagé.
 *
 * Cette fonction est volontairement isolée pour rendre le contrat de continuité du flux testable
 * sans instancier ExoPlayer dans un test unitaire JVM.
 */
fun shouldRestartLivePreview(
    currentEntryKey: String?,
    currentMediaItemCount: Int,
    targetEntryKey: String,
): Boolean = true

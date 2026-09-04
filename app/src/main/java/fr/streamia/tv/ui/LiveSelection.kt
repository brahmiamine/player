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
): Boolean = currentEntryKey != targetEntryKey || currentMediaItemCount <= 0


/**
 * Replace le flux réellement actif en tête de la stratégie de fallback.
 *
 * Lors d'un retour plein écran -> Browser, les candidats peuvent être régénérés avec un ordre
 * différent de celui utilisé par la session partagée. Le flux courant devient donc explicitement
 * le candidat 0, puis les autres fallbacks gardent leur ordre sans doublon.
 */
fun prioritizeActiveLiveCandidate(
    candidates: List<String>,
    activeUrl: String,
): List<String> {
    if (activeUrl.isBlank()) return candidates
    return buildList {
        add(activeUrl)
        addAll(candidates.filterNot { it == activeUrl })
    }
}

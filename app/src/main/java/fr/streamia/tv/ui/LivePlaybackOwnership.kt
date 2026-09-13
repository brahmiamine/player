package fr.streamia.tv.ui

import fr.streamia.tv.domain.MediaType

/**
 * Le lecteur Live partagé ne doit rester actif que pour l'aperçu du navigateur Direct ou le
 * plein écran d'une chaîne. Partir vers l'accueil, un film, les réglages, etc. doit couper
 * la session — sinon l'audio Live continue sous un autre écran, et un retour au premier plan
 * la relance via ON_START.
 */
fun shouldKeepLivePlayback(screen: StreamiaScreen): Boolean = when (screen) {
    is StreamiaScreen.Browser -> true
    is StreamiaScreen.Player -> screen.entry.type == MediaType.Live
    else -> false
}

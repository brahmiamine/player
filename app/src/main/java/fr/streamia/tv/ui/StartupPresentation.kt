package fr.streamia.tv.ui

/**
 * Au redémarrage, le player peut repartir immédiatement avec le dernier média mémorisé pendant
 * que le catalogue complet est relu depuis le disque. Si l'utilisateur quitte ce player avant la
 * fin de cette lecture locale, on garde l'accueil derrière l'écran de démarrage afin de ne jamais
 * afficher TV / Films / Séries avec de faux états « Chargement… ».
 */
internal fun shouldShowStartupGate(state: StreamiaUiState): Boolean =
    state.booting || (state.catalogHydrating && state.screen is StreamiaScreen.Home)

/**
 * La vidéo Live restaurée doit rester visible pendant la lecture du catalogue local. Une demande
 * OK / gauche / menu est mémorisée puis exécutée dès que le catalogue complet est prêt, plutôt que
 * d'envoyer l'utilisateur vers l'écran de démarrage.
 */
internal fun shouldDeferLiveBrowserReturn(state: StreamiaUiState): Boolean {
    val player = state.screen as? StreamiaScreen.Player ?: return false
    return state.catalogHydrating && player.entry.type == fr.streamia.tv.domain.MediaType.Live
}

/**
 * Une chaîne ouverte depuis un écran qui sait se restaurer y revient, sur la carte d'origine :
 * `ContentReturnContext` porte la rangée + l'empreinte du match côté accueil, et la clé match +
 * chaîne côté « Matchs du jour ». Les autres provenances gardent le navigateur Direct — c'est le
 * seul écran qui partage le lecteur Live avec son aperçu, et la Recherche ne mémorise pas ses
 * résultats.
 *
 * `when` exhaustif plutôt qu'une comparaison : un nouvel écran d'origine fera échouer la compilation
 * au lieu de retomber silencieusement sur le navigateur.
 */
internal fun livePlayerReturnsToSource(origin: ContentReturnOrigin?): Boolean = when (origin) {
    ContentReturnOrigin.Home, ContentReturnOrigin.LiveMatches -> true
    ContentReturnOrigin.Browser, ContentReturnOrigin.Search, null -> false
}

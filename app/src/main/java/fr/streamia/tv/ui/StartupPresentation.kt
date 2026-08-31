package fr.streamia.tv.ui

/**
 * Au redémarrage, le player peut repartir immédiatement avec le dernier média mémorisé pendant
 * que le catalogue complet est relu depuis le disque. Si l'utilisateur quitte ce player avant la
 * fin de cette lecture locale, on garde l'accueil derrière l'écran de démarrage afin de ne jamais
 * afficher TV / Films / Séries avec de faux états « Chargement… ».
 */
internal fun shouldShowStartupGate(state: StreamiaUiState): Boolean =
    state.booting || (state.catalogHydrating && state.screen is StreamiaScreen.Home)

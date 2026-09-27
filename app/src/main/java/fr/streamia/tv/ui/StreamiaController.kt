package fr.streamia.tv.ui

import fr.streamia.tv.data.HomeBlock
import fr.streamia.tv.data.XtreamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * État partagé par [StreamiaViewModel] et ses contrôleurs de domaine : chaque contrôleur lit et
 * publie dans ces mêmes flux, dans la portée du ViewModel (annulée avec lui).
 */
internal class StreamiaStateHolder(val scope: CoroutineScope, val repository: XtreamRepository) {
    /** État de l'application (écran, catalogue, bibliothèque, réglages…). */
    val ui = MutableStateFlow(StreamiaUiState())

    /** État de l'accueil, dans son propre flux (voir [HomeUiState]). */
    val home = MutableStateFlow(HomeUiState())

    /** État du lecteur qui change souvent (voir [PlayerUiState]). */
    val player = MutableStateFlow(PlayerUiState())

    /** Premier chargement de ces blocs terminé : l'accueil remplace leur squelette par le contenu, ou rien. */
    fun settleHomeBlocks(blocks: Set<HomeBlock>) {
        home.update { if (it.homePendingBlocks.any(blocks::contains)) it.copy(homePendingBlocks = it.homePendingBlocks - blocks) else it }
    }
}

/**
 * Base des contrôleurs : mêmes noms que dans le ViewModel (`_uiState`, `viewModelScope`…), pour que
 * le code d'un domaine se lise de la même façon, qu'il soit dans le ViewModel ou dans son contrôleur.
 */
internal abstract class StreamiaController(protected val host: StreamiaStateHolder) {
    protected val _uiState: MutableStateFlow<StreamiaUiState> get() = host.ui
    protected val _homeState: MutableStateFlow<HomeUiState> get() = host.home
    protected val _playerState: MutableStateFlow<PlayerUiState> get() = host.player
    protected val viewModelScope: CoroutineScope get() = host.scope
    protected val repository: XtreamRepository get() = host.repository

    protected fun settleHomeBlocks(blocks: Set<HomeBlock>) = host.settleHomeBlocks(blocks)
}

/** Message d'erreur montrable à l'utilisateur. */
internal fun Throwable.safeMessage(): String = message?.takeIf { it.isNotBlank() } ?: "Une erreur inattendue s'est produite."

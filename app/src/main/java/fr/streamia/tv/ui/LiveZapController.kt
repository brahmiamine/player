package fr.streamia.tv.ui

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.LiveZapIndex
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Zapping du Direct dans le lecteur : CH+/CH− (liste parcourue ou catégorie d'origine), autre
 * version de la chaîne et « dernière chaîne ». L'ouverture effective passe par [openChannel].
 */
internal class LiveZapController(
    host: StreamiaStateHolder,
    private val openChannel: (MediaEntry) -> Unit,
) : StreamiaController(host) {
    private var zapJob: Job? = null

    /** Chaîne Direct regardée juste avant la chaîne courante, pour « dernière chaîne ». */
    private var previousLiveEntry: MediaEntry? = null

    private var lastLiveEntry: MediaEntry? = null

    /** Liste parcourue dans le Direct au lancement de la chaîne, pour CH+/CH− (voir openLiveFromList). */
    var zapList: List<MediaEntry>? = null

    // Clé comparée par identité des instances (catalogue/ensembles), recalculée seulement quand
    // l'un d'eux change réellement.
    private var zapIndexCache: Pair<List<Any>, LiveZapIndex>? = null

    /** Toute chaîne ouverte en plein écran compte, qu'on y arrive par zap ou via la liste Direct. */
    fun onChannelOpened(entry: MediaEntry) {
        lastLiveEntry?.takeIf { it.key != entry.key }?.let { previousLiveEntry = it }
        lastLiveEntry = entry
    }

    /** Reprise au démarrage : point de départ de « dernière chaîne », sans chaîne précédente. */
    fun onChannelResumed(entry: MediaEntry) {
        lastLiveEntry = entry
    }

    fun cancelPendingZap() {
        zapJob?.cancel()
    }

    /** Déconnexion : rien ne survit au profil quitté. */
    fun reset() {
        zapList = null
        previousLiveEntry = null
        lastLiveEntry = null
        zapJob?.cancel()
    }

    fun zap(delta: Int) {
        val state = _uiState.value
        val current = (state.screen as? StreamiaScreen.Player)?.entry ?: return
        if (current.type != MediaType.Live) return
        val catalog = state.catalog ?: return
        // Zap rapide : chaque appui avance depuis la chaîne déjà annoncée (pas depuis celle qui
        // joue), le bandeau s'affiche tout de suite, et le flux ne démarre qu'une fois les appuis
        // terminés — enchaîner CH+ ne lance plus un flux réseau (et un EPG) par chaîne traversée.
        val from = _playerState.value.pendingZapEntry ?: current
        val next = zapList?.let { list ->
            list.indexOfFirst { it.key == from.key }.takeIf { it >= 0 }?.let { index -> list[Math.floorMod(index + delta, list.size)] }
        } ?: liveZapIndex(state, catalog).adjacent(from, delta) ?: return
        _playerState.update { it.copy(pendingZapEntry = next) }
        zapJob?.cancel()
        zapJob = viewModelScope.launch {
            delay(ZAP_SETTLE_MS)
            _playerState.update { it.copy(pendingZapEntry = null) }
            if (next.key != current.key) openChannel(next)
        }
    }

    /**
     * Autre version de la chaîne en cours (panneau « Versions » du lecteur). Elle prend la place de
     * la chaîne dans la liste de zapping, pour que CH+/CH− repartent du même endroit.
     */
    fun switchLiveVersion(version: MediaEntry) {
        val current = (_uiState.value.screen as? StreamiaScreen.Player)?.entry ?: return
        if (current.type != MediaType.Live || version.type != MediaType.Live || version.key == current.key) return
        zapList = zapList?.let { list ->
            if (list.any { it.key == version.key }) list else list.map { if (it.key == current.key) version else it }
        }
        zapJob?.cancel()
        _playerState.update { it.copy(pendingZapEntry = null) }
        openChannel(version)
    }

    /**
     * Le repli sur toutes les chaînes peut faire sauter le zapping dans une autre catégorie : une
     * catégorie verrouillée et pas encore déverrouillée cette session est exclue comme si elle était
     * masquée — il n'y a pas d'écran de code pendant le zapping.
     */
    private fun liveZapIndex(state: StreamiaUiState, catalog: Catalog): LiveZapIndex {
        val locked = state.appSettings.parentalControlEnabled && !state.parentalUnlocked
        val key = listOf(catalog, state.library.hiddenEntries, state.library.lockedCategories, locked)
        zapIndexCache?.takeIf { it.first == key }?.let { return it.second }
        val lockedCategoryIds = if (locked) {
            catalog.categoriesFor(MediaType.Live)
                .filter { it.key in state.library.lockedCategories }
                .mapTo(mutableSetOf(), MediaCategory::id)
        } else {
            emptySet()
        }
        return LiveZapIndex(catalog, state.library.hiddenEntries, lockedCategoryIds).also { zapIndexCache = key to it }
    }

    /** Revient à la chaîne regardée juste avant (un second appui ramène à la chaîne d'origine). */
    fun previousChannel() {
        val current = (_uiState.value.screen as? StreamiaScreen.Player)?.entry ?: return
        if (current.type != MediaType.Live) return
        val target = previousLiveEntry?.takeIf { it.key != current.key } ?: return
        zapJob?.cancel()
        _playerState.update { it.copy(pendingZapEntry = null) }
        openChannel(target)
    }
}

private const val ZAP_SETTLE_MS = 350L

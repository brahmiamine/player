package fr.streamia.tv.ui

import fr.streamia.tv.data.BackgroundWork
import androidx.lifecycle.viewModelScope
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bibliothèque de l'utilisateur : favoris, masquages, verrouillages, contenus vus, historique de lecture, code parental et organisation du catalogue.
 */
internal class LibraryController(
    host: StreamiaStateHolder,
    private val catalogLayoutMutation: Mutex,
    private val mergeIntoCatalog: suspend (profileId: String, merge: (Catalog) -> Catalog) -> Unit,
) : StreamiaController(host) {
    /**
     * Bascule optimiste : l'état local change tout de suite, la persistance est confirmée ensuite
     * en arrière-plan puis la bibliothèque est relue pour rester source de vérité. [libraryMutation]
     * sérialise cette confirmation — sans elle, deux appuis rapprochés sur le même favori (double
     * appui télécommande) lancent deux relectures concurrentes sur Dispatchers.IO ; rien ne garantit
     * que celle du premier appui se termine avant celle du second, et celle qui arrive en dernier
     * écrase l'état affiché même si elle correspond à un instantané antérieur, ce qui fait
     * clignoter l'icône vers une valeur déjà obsolète.
     *
     * [libraryMutationSequence] complète la sérialisation : sans lui, la relecture du *premier*
     * appui (encore en file quand le second optimiste s'applique déjà) écraserait brièvement l'état
     * affiché avec un instantané qui ne contient pas encore le second changement — un retour en
     * arrière visible avant que la relecture du second appui ne corrige. Seule la relecture dont le
     * numéro de séquence est encore le plus récent au moment où elle se termine est appliquée ; les
     * relectures intermédiaires, déjà dépassées par un appui plus récent, sont ignorées.
     */
    private val libraryMutation = Mutex()

    private var libraryMutationSequence = 0L

    /**
     * Bascule une clé dans un ensemble de la bibliothèque (favori, masqué, verrouillé, vu) : mise à
     * jour immédiate de l'interface, écriture sérialisée sur IO, puis relecture de la bibliothèque
     * seulement si aucune bascule plus récente n'a eu lieu entre-temps.
     */
    private fun toggleInLibrary(
        key: String,
        read: (UserLibrarySnapshot) -> Set<String>,
        write: (UserLibrarySnapshot, Set<String>) -> UserLibrarySnapshot,
        persist: (profileId: String) -> Unit,
    ) {
        val profileId = _uiState.value.activeProfileId ?: return
        _uiState.update { state ->
            val updated = read(state.library).toMutableSet().apply { if (!add(key)) remove(key) }
            state.copy(library = write(state.library, updated))
        }
        val sequence = ++libraryMutationSequence
        viewModelScope.launch {
            libraryMutation.withLock {
                runCatching {
                    withContext(Dispatchers.IO) {
                        persist(profileId)
                        repository.library(profileId)
                    }
                }.onSuccess { library ->
                    if (sequence != libraryMutationSequence) return@onSuccess
                    _uiState.update { state -> if (state.activeProfileId == profileId) state.copy(library = library) else state }
                }
            }
        }
    }

    fun toggleEntryHidden(entry: MediaEntry) = toggleInLibrary(entry.key, { it.hiddenEntries }, { lib, set -> lib.copy(hiddenEntries = set) }) { profileId ->
        repository.toggleEntryHidden(profileId, entry)
    }

    fun toggleCategoryHidden(category: MediaCategory) = toggleInLibrary(category.key, { it.hiddenCategories }, { lib, set -> lib.copy(hiddenCategories = set) }) { profileId ->
        repository.toggleCategoryHidden(profileId, category)
    }

    fun toggleCategoryLocked(category: MediaCategory) = toggleInLibrary(category.key, { it.lockedCategories }, { lib, set -> lib.copy(lockedCategories = set) }) { profileId ->
        repository.toggleCategoryLocked(profileId, category)
    }

    fun toggleEntryWatched(entry: MediaEntry) = toggleInLibrary(entry.key, { it.watchedEntries }, { lib, set -> lib.copy(watchedEntries = set) }) { profileId ->
        repository.toggleEntryWatched(profileId, entry)
    }

    fun toggleCategoryFavorite(category: MediaCategory) = toggleInLibrary(category.key, { it.favoriteCategories }, { lib, set -> lib.copy(favoriteCategories = set) }) { profileId ->
        repository.toggleCategoryFavorite(profileId, category)
    }

    /** Enregistre un nouveau code parental et active le verrouillage — déverrouille aussi la session en cours puisque c'est l'utilisateur qui vient de le saisir. */
    fun setParentalPin(pin: String) {
        // PBKDF2 volontairement lent (plusieurs centaines de ms sur un boîtier TV) : hors du thread principal.
        viewModelScope.launch {
            val settings = withContext(Dispatchers.Default) { repository.setParentalPin(pin) }
            _uiState.update { it.copy(appSettings = settings, parentalUnlocked = true) }
        }
    }

    fun disableParentalControl() {
        val settings = repository.clearParentalPin()
        _uiState.update { it.copy(appSettings = settings, parentalUnlocked = false) }
    }

    /** Code correct : déverrouille le contenu verrouillé pour le reste de la session (jusqu'à la fermeture de l'app). */
    suspend fun verifyParentalPin(pin: String): Boolean {
        val correct = withContext(Dispatchers.Default) { repository.verifyParentalPin(pin) }
        if (correct) _uiState.update { it.copy(parentalUnlocked = true) }
        return correct
    }

    /**
     * [final] : lecture quittée ou mise en arrière-plan. La rangée « Continuer à regarder » de
     * Google TV (≈ 10 appels au fournisseur système) n'est republiée qu'à ce moment-là, plus toutes
     * les 15 s pendant un film.
     */
    fun recordPlayback(entry: MediaEntry, positionMs: Long, durationMs: Long, final: Boolean = false) {
        val profileId = _uiState.value.activeProfileId ?: return
        viewModelScope.launch {
            libraryMutation.withLock {
                // Réécriture de tout le JSON du profil juste après un zap, pendant que la nouvelle
                // chaîne démarre : en priorité basse, pour ne pas disputer le processeur au lecteur.
                val history = withContext(BackgroundWork.light) {
                    repository.recordPlayback(profileId, entry, positionMs, durationMs)
                    // « Continuer à regarder » de Google TV ne liste que films et épisodes : rien à
                    // republier (≈ 10 appels au fournisseur système) à chaque chaîne Direct.
                    if (final && entry.type != MediaType.Live) repository.publishWatchNext(profileId)
                    repository.library(profileId).history
                }
                _uiState.update { state ->
                    if (state.activeProfileId == profileId) {
                        state.copy(library = state.library.copy(history = history))
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun clearHistory(type: MediaType? = null) {
        val profileId = _uiState.value.activeProfileId ?: return
        // Relit et réécrit tout le JSON du profil (historique compris) : hors du thread principal,
        // sous le même verrou que les autres écritures de la bibliothèque.
        viewModelScope.launch {
            libraryMutation.withLock {
                withContext(Dispatchers.IO) { repository.clearHistory(profileId, type) }
            }
            refreshLibraryPresentation()
            withContext(Dispatchers.IO) { repository.publishWatchNext(profileId) }
        }
    }

    fun setCategoryOrder(type: MediaType, categoryKeys: List<String>) {
        val profileId = _uiState.value.activeProfileId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            catalogLayoutMutation.withLock {
                repository.setCategoryOrder(profileId, type, categoryKeys)
            }
        }
    }

    fun moveEntries(entryKeys: Set<String>, targetCategoryId: String) {
        val profileId = _uiState.value.activeProfileId ?: return
        if (entryKeys.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            catalogLayoutMutation.withLock {
                repository.moveEntries(profileId, entryKeys, targetCategoryId)
            }
        }
    }

    fun resetEntryMoves(entryKeys: Set<String>) {
        val profileId = _uiState.value.activeProfileId ?: return
        if (entryKeys.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            catalogLayoutMutation.withLock {
                repository.resetEntryMoves(profileId, entryKeys)
            }
        }
    }

    private fun refreshLibraryPresentation() {
        val state = _uiState.value
        val profileId = state.activeProfileId ?: return
        viewModelScope.launch {
            val presentation = withContext(Dispatchers.IO) { libraryPresentation(profileId) }
            applyLibraryPresentation(profileId, presentation)
        }
    }

    fun refreshLibrarySnapshot(profileId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val library = repository.library(profileId)
            _uiState.update { state -> if (state.activeProfileId == profileId) state.copy(library = library) else state }
        }
    }

    fun libraryPresentation(profileId: String): Pair<Catalog?, UserLibrarySnapshot> {
        val state = _uiState.value
        val raw = state.rawCatalog ?: state.catalog
        return (raw?.let { repository.customizedCatalog(profileId, it) }) to repository.library(profileId)
    }

    fun applyLibraryPresentation(profileId: String, presentation: Pair<Catalog?, UserLibrarySnapshot>) {
        _uiState.update { state ->
            if (state.activeProfileId != profileId) state
            else state.copy(
                catalog = presentation.first ?: state.catalog,
                library = presentation.second,
                // Déplacements modifiés (organisateur, restauration) : les pages Films/Séries sont relues.
                vodPages = if (state.library.movedEntries == presentation.second.movedEntries) state.vodPages else emptyMap(),
            )
        }
    }

    fun toggleEntryFavorite(entry: MediaEntry) {
        toggleInLibrary(entry.key, { it.favoriteEntries }, { lib, set -> lib.copy(favoriteEntries = set) }) { profileId ->
            repository.toggleEntryFavorite(profileId, entry)
        }
        // Film/série ajouté depuis une page (hors du catalogue global) : ajouté au catalogue pour
        // apparaître dans « Favoris » et sur l'accueil, comme les favoris chargés à l'ouverture.
        val profileId = _uiState.value.activeProfileId ?: return
        if (_uiState.value.catalog?.entry(entry.key) == null) {
            viewModelScope.launch { mergeIntoCatalog(profileId) { base -> base.withExtraEntries(listOf(entry)) } }
        }
    }
}

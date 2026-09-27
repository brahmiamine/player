package fr.streamia.tv.ui

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import fr.streamia.tv.data.BackgroundWork
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import fr.streamia.tv.domain.LiveVersionIndex
import kotlinx.coroutines.withContext

/**
 * Versions des chaînes du Direct (panneau « Versions », secours automatique) : index construit hors du thread principal, une fois par liste de chaînes.
 */
internal class LiveVersionIndexController(
    host: StreamiaStateHolder,
    private val liveSection: suspend (profileId: String) -> List<MediaEntry>,
) : StreamiaController(host) {
    /** Reconstruit l'index quand la liste active ou son catalogue change (une fois le calme revenu). */
    fun start() {
        viewModelScope.launch {
            _uiState
                .map { LiveVersionIndexSource(it.activeProfileId, it.catalog, it.catalogHydrating) }
                .distinctUntilChanged { old, new ->
                    old.profileId == new.profileId && old.catalog === new.catalog && old.hydrating == new.hydrating
                }
                .collectLatest(::rebuildLiveVersionIndex)
        }
    }

    /**
     * Versions des chaînes du Direct (panneau « Versions », secours automatique), construit une
     * seule fois par liste de chaînes, hors du thread principal, et gardé entre les ouvertures du
     * lecteur. Tant que le catalogue n'a pas toutes les chaînes en mémoire (démarrage, catalogue
     * paginé), il est construit depuis la base : les versions de toutes les catégories comptent.
     */
    private val _liveVersionIndex = MutableStateFlow<LiveVersionIndex?>(null)

    val liveVersionIndex: StateFlow<LiveVersionIndex?> = _liveVersionIndex.asStateFlow()

    private var liveVersionIndexProfileId: String? = null

    private var liveVersionIndexFromDatabase = false

    private suspend fun rebuildLiveVersionIndex(source: LiveVersionIndexSource) {
        val profileId = source.profileId
        val catalog = source.catalog
        if (profileId == null || catalog == null) {
            liveVersionIndexProfileId = null
            _liveVersionIndex.value = null
            return
        }
        if (liveVersionIndexProfileId != profileId) {
            liveVersionIndexProfileId = null
            _liveVersionIndex.value = null
        }
        // Les pages fusionnées pendant l'hydratation changent le catalogue en rafale : une seule
        // construction une fois le calme revenu (collectLatest annule l'attente précédente).
        delay(LIVE_VERSION_INDEX_SETTLE_MS)
        val complete = !source.hydrating && catalog.isCategoryLoaded(MediaType.Live, Catalog.ALL_CATEGORY_ID)
        val current = _liveVersionIndex.value
        // Index déjà construit depuis la base pour cette liste : il reste valable jusqu'au catalogue complet.
        if (!complete && current != null && liveVersionIndexFromDatabase && liveVersionIndexProfileId == profileId) return
        // Priorité basse : l'index ne doit jamais prendre le processeur à l'interface ni au lecteur.
        val index = withContext(BackgroundWork.light) {
            val channels = if (complete) {
                catalog.entriesFor(MediaType.Live)
            } else {
                runCatching { liveSection(profileId) }.getOrDefault(emptyList())
            }
            if (channels.isEmpty()) return@withContext null
            val fingerprint = LiveVersionIndex.fingerprintOf(channels)
            // Même liste (page Films fusionnée, favori ajouté…) : rien à reconstruire.
            if (current != null && liveVersionIndexProfileId == profileId && current.fingerprint == fingerprint) current
            else LiveVersionIndex(channels, fingerprint)
        } ?: return
        if (_uiState.value.activeProfileId != profileId) return
        liveVersionIndexProfileId = profileId
        liveVersionIndexFromDatabase = !complete
        _liveVersionIndex.value = index
    }
}

/** Ce dont dépend l'index des versions du Direct (voir [StreamiaViewModel.liveVersionIndex]). */
private class LiveVersionIndexSource(val profileId: String?, val catalog: Catalog?, val hydrating: Boolean)

private const val LIVE_VERSION_INDEX_SETTLE_MS = 1_500L

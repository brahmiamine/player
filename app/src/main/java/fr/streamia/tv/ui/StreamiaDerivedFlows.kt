package fr.streamia.tv.ui

import fr.streamia.tv.data.BackgroundWork
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Programmes du jour de chaque chaîne Direct (clé d'entrée), rapprochés du guide une seule fois
 * par guide et par liste, en priorité basse. La liste des chaînes affichait sinon le programme en
 * cours en résolvant chaque ligne (alias par expressions régulières) pendant le défilement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun liveEpgProgramsFlow(ui: StateFlow<StreamiaUiState>, scope: CoroutineScope): StateFlow<Map<String, List<EpgProgram>>> = ui
    .map { state -> state.todayEpgGuide to state.catalog?.entriesFor(MediaType.Live) }
    .distinctUntilChanged { old, new -> old.first === new.first && old.second === new.second }
    .mapLatest { (guide, channels) ->
        if (guide == null || channels.isNullOrEmpty()) emptyMap()
        else withContext(BackgroundWork.light) {
            val result = HashMap<String, List<EpgProgram>>()
            for (channel in channels) {
                guide.forEntry(channel).takeIf { it.isNotEmpty() }?.let { result[channel.key] = it }
            }
            result
        }
    }
    // Lecture de l'état et accès à la section Direct hors du thread principal.
    .flowOn(Dispatchers.Default)
    .stateIn(scope, SharingStarted.Eagerly, emptyMap())

/**
 * Matchs du jour sans les chaînes masquées ou verrouillées, calculés hors du thread principal et
 * seulement quand les matchs, le catalogue ou les masquages changent (auparavant dans la
 * composition de la racine, à chaque lot de matchs rapprochés, quel que soit l'écran affiché).
 */
internal fun visibleLiveOnSatMatchesFlow(
    ui: StateFlow<StreamiaUiState>,
    home: StateFlow<HomeUiState>,
    scope: CoroutineScope,
): StateFlow<List<ResolvedLiveOnSatMatch>> = combine(
    home.map { it.liveOnSatMatches }.distinctUntilChanged { old, new -> old === new },
    ui.map { LiveMatchVisibility(it.catalog, it.library, it.appSettings.parentalControlEnabled && !it.parentalUnlocked) }
        .distinctUntilChanged { old, new -> old.sameAs(new) },
) { matches, visibility -> matches.withoutHiddenChannels(visibility.catalog, visibility.library, visibility.parentalLocked) }
    .flowOn(Dispatchers.Default)
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

/** Ce dont dépend le filtrage des chaînes des matchs (voir [visibleLiveOnSatMatchesFlow]). */
private class LiveMatchVisibility(val catalog: Catalog?, val library: UserLibrarySnapshot, val parentalLocked: Boolean) {
    fun sameAs(other: LiveMatchVisibility): Boolean =
        catalog?.categories === other.catalog?.categories &&
            library.hiddenEntries == other.library.hiddenEntries &&
            library.hiddenCategories == other.library.hiddenCategories &&
            library.lockedCategories == other.library.lockedCategories &&
            parentalLocked == other.parentalLocked
}

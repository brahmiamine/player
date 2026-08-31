package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import fr.streamia.tv.data.PlaybackSessionStore
import fr.streamia.tv.data.resolveStartupProfileId
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield

/** État très léger utilisé par MainActivity pour ouvrir le sélecteur Live avec la touche OK. */
object PlayerOverlayController {
    private val _livePickerOpen = MutableStateFlow(false)
    val livePickerOpen = _livePickerOpen.asStateFlow()

    fun openLivePicker() { _livePickerOpen.value = true }
    fun closeLivePicker() { _livePickerOpen.value = false }
    fun isLivePickerOpen(): Boolean = _livePickerOpen.value
}

/**
 * Racine TV autour de StreamiaApp.
 * Elle garde le lecteur monté pendant le sélecteur Live et restaure la dernière playlist/contenu.
 */
@Composable
fun StreamiaTvRoot(viewModel: StreamiaViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pickerOpen by PlayerOverlayController.livePickerOpen.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sessionStore = remember { PlaybackSessionStore(context.applicationContext) }

    LaunchedEffect(Unit) {
        val initialState = viewModel.uiState.value
        if (initialState.activeProfileId != null || initialState.screen !is StreamiaScreen.Login) return@LaunchedEffect

        val availableIds = initialState.profiles.map { it.id }
        val storedSession = sessionStore.load()
        val validSession = storedSession?.takeIf { it.profileId in availableIds }
        if (storedSession != null && validSession == null) sessionStore.clearPlayback()

        val targetProfileId = resolveStartupProfileId(
            availableProfileIds = availableIds,
            playbackProfileId = validSession?.profileId,
            activeProfileId = sessionStore.loadActiveProfileId(),
            autoOpenDisabled = sessionStore.isAutoOpenDisabled(),
        ) ?: return@LaunchedEffect

        viewModel.openProfile(targetProfileId)
        val loaded = viewModel.uiState.first { candidate ->
            val profileReady = candidate.activeProfileId == targetProfileId &&
                candidate.catalog != null &&
                !candidate.busy
            val failed = candidate.screen is StreamiaScreen.Login &&
                !candidate.busy &&
                candidate.message != null
            profileReady || failed
        }

        if (loaded.activeProfileId != targetProfileId || loaded.catalog == null) return@LaunchedEffect
        val session = validSession?.takeIf { it.profileId == targetProfileId } ?: return@LaunchedEffect

        val restoredEntry = when {
            session.entry.type == MediaType.Series && session.entry.playable -> session.entry
            else -> loaded.catalog.entry(session.entry.key) ?: session.entry
        }

        when (restoredEntry.type) {
            MediaType.Movie -> viewModel.playMovie(restoredEntry)
            MediaType.Live,
            MediaType.Series,
            -> viewModel.openEntry(restoredEntry)
        }
    }

    LaunchedEffect(Unit) {
        var previouslyActiveProfileId: String? = null
        viewModel.uiState.collect { current ->
            val activeProfileId = current.activeProfileId
            if (activeProfileId != null) {
                val savedPlayback = sessionStore.load()
                if (savedPlayback != null && savedPlayback.profileId != activeProfileId) {
                    sessionStore.clearPlayback()
                }
                sessionStore.saveActiveProfile(activeProfileId)
                previouslyActiveProfileId = activeProfileId
            }

            val playerScreen = current.screen as? StreamiaScreen.Player
            if (playerScreen != null && activeProfileId != null) {
                sessionStore.save(activeProfileId, playerScreen.entry, playerScreen.returnToSeries)
            }

            // Un logout explicite repasse d'un profil actif vers Login et désactive l'auto-ouverture.
            if (current.screen is StreamiaScreen.Login && activeProfileId == null && previouslyActiveProfileId != null) {
                sessionStore.disableAutoOpen()
                previouslyActiveProfileId = null
            }

            if (playerScreen?.entry?.type != MediaType.Live) {
                PlayerOverlayController.closeLivePicker()
            }
        }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        StreamiaApp(viewModel)

        val playerScreen = state.screen as? StreamiaScreen.Player
        if (
            pickerOpen &&
            playerScreen?.entry?.type == MediaType.Live &&
            state.catalog != null
        ) {
            LiveChannelPickerOverlay(
                catalog = state.catalog!!,
                currentEntry = playerScreen.entry,
                onEntrySelected = { channel ->
                    PlayerOverlayController.closeLivePicker()
                    viewModel.openEntry(channel)
                },
                onClose = PlayerOverlayController::closeLivePicker,
            )
        }
    }
}

@Composable
private fun LiveChannelPickerOverlay(
    catalog: Catalog,
    currentEntry: MediaEntry,
    onEntrySelected: (MediaEntry) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    val categories = remember(catalog) {
        listOf(Catalog.allCategory(MediaType.Live)) + catalog.categoriesFor(MediaType.Live)
    }
    var selectedCategoryId by androidx.compose.runtime.remember(currentEntry.categoryId) {
        androidx.compose.runtime.mutableStateOf(
            currentEntry.categoryId.takeIf { id -> categories.any { it.id == id } }
                ?: Catalog.ALL_CATEGORY_ID,
        )
    }
    val channels = remember(catalog, selectedCategoryId) {
        catalog.entriesIn(MediaType.Live, selectedCategoryId)
    }
    val categoryListState = rememberLazyListState()
    val channelListState = rememberLazyListState()
    val channelFocus = remember(selectedCategoryId, currentEntry.key) { FocusRequester() }
    val focusTargetKey = channels.firstOrNull { it.key == currentEntry.key }?.key ?: channels.firstOrNull()?.key

    LaunchedEffect(categories.size, selectedCategoryId) {
        val index = categories.indexOfFirst { it.id == selectedCategoryId }
        if (index >= 0) categoryListState.scrollToItem(index)
    }

    LaunchedEffect(selectedCategoryId, channels.size, focusTargetKey) {
        val index = channels.indexOfFirst { it.key == focusTargetKey }
        if (index >= 0) {
            channelListState.scrollToItem(index)
            yield()
            runCatching { channelFocus.requestFocus() }
        }
    }

    Row(
        Modifier
            .fillMaxHeight()
            .width(900.dp)
            .background(Night.copy(alpha = 0.985f))
            .padding(24.dp),
    ) {
        Column(Modifier.width(315.dp).fillMaxHeight()) {
            Text("Catégories", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("OK pour choisir", color = MutedInk, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                state = categoryListState,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp),
            ) {
                items(categories, key = MediaCategory::key) { category ->
                    FocusableSurface(
                        onClick = { selectedCategoryId = category.id },
                        selected = selectedCategoryId == category.id,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                category.name,
                                color = Ink,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            val count = catalog.entriesIn(MediaType.Live, category.id).size
                            Text(count.toString(), color = MutedInk, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(20.dp))

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Chaînes", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("${channels.size} chaînes", color = MutedInk, fontSize = 12.sp)
                }
                FocusableSurface(onClick = onClose, modifier = Modifier.width(110.dp).height(48.dp)) {
                    Text("Fermer", color = Ink, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 15.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                state = channelListState,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp),
            ) {
                items(channels, key = MediaEntry::key) { channel ->
                    val isFocusTarget = channel.key == focusTargetKey
                    FocusableSurface(
                        onClick = { onEntrySelected(channel) },
                        selected = channel.key == currentEntry.key,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(70.dp)
                            .then(if (isFocusTarget) Modifier.focusRequester(channelFocus) else Modifier),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ChannelLogo(channel.iconUrl, channel.displayName, Modifier.size(48.dp))
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    channel.displayName,
                                    color = Ink,
                                    fontSize = 15.sp,
                                    fontWeight = if (channel.key == currentEntry.key) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (channel.key == currentEntry.key) {
                                    Text("En cours", color = FocusBlueBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(
                                channel.number.toString(),
                                color = FocusBlueBright,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import fr.streamia.tv.data.PlaybackSessionStore
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.data.resolveStartupProfileId
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.WarmSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield

private const val LIVE_PICKER_FAVORITES_CATEGORY_ID = "__favorites__"
private const val LIVE_PICKER_HISTORY_CATEGORY_ID = "__history__"

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

    Box(Modifier.fillMaxSize()) {
        StreamiaApp(viewModel)

        val playerScreen = state.screen as? StreamiaScreen.Player
        if (
            pickerOpen &&
            playerScreen?.entry?.type == MediaType.Live &&
            state.catalog != null
        ) {
            LiveChannelPickerOverlay(
                catalog = state.catalog!!,
                library = state.library,
                currentEntry = playerScreen.entry,
                initialCategoryId = state.browserCategoryId,
                offline = state.offline,
                busy = state.busy,
                onEntrySelected = { channel ->
                    when (liveChannelConfirmAction(playerScreen.entry.key, channel.key)) {
                        LiveChannelConfirmAction.Preview -> viewModel.openEntry(channel)
                        LiveChannelConfirmAction.Fullscreen -> PlayerOverlayController.closeLivePicker()
                        LiveChannelConfirmAction.Ignore -> Unit
                    }
                },
                onCategorySelected = { categoryId ->
                    viewModel.rememberBrowserLocation(MediaType.Live, categoryId)
                },
                onToggleEntryFavorite = viewModel::toggleEntryFavorite,
                onToggleCategoryFavorite = viewModel::toggleCategoryFavorite,
                onHome = {
                    PlayerOverlayController.closeLivePicker()
                    viewModel.showHome()
                },
                onOpenSection = { type ->
                    PlayerOverlayController.closeLivePicker()
                    viewModel.openSection(type)
                },
                onSearch = {
                    PlayerOverlayController.closeLivePicker()
                    viewModel.showSearch()
                },
                onEpg = {
                    PlayerOverlayController.closeLivePicker()
                    viewModel.showEpg()
                },
                onSettings = {
                    PlayerOverlayController.closeLivePicker()
                    viewModel.showSettings()
                },
                onClose = PlayerOverlayController::closeLivePicker,
            )
        }
    }
}

@Composable
private fun LiveChannelPickerOverlay(
    catalog: Catalog,
    library: UserLibrarySnapshot,
    currentEntry: MediaEntry,
    initialCategoryId: String?,
    offline: Boolean,
    busy: Boolean,
    onEntrySelected: (MediaEntry) -> Unit,
    onCategorySelected: (String) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    onToggleCategoryFavorite: (MediaCategory) -> Unit,
    onHome: () -> Unit,
    onOpenSection: (MediaType) -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    val favoriteLiveEntries = remember(catalog, library.favoriteEntries) {
        catalog.entriesFor(MediaType.Live).filter { it.key in library.favoriteEntries }
    }
    val historyLiveEntries = remember(catalog, library.history) {
        library.history.asSequence()
            .map { item -> catalog.entry(item.entry.key) ?: item.entry }
            .filter { it.type == MediaType.Live }
            .distinctBy(MediaEntry::key)
            .toList()
    }
    val categories = remember(catalog, favoriteLiveEntries.size, historyLiveEntries.size) {
        buildList {
            if (historyLiveEntries.isNotEmpty()) {
                add(MediaCategory(LIVE_PICKER_HISTORY_CATEGORY_ID, "↺ Historique", MediaType.Live))
            }
            if (favoriteLiveEntries.isNotEmpty()) {
                add(MediaCategory(LIVE_PICKER_FAVORITES_CATEGORY_ID, "★ Favoris", MediaType.Live))
            }
            addAll(catalog.categoriesFor(MediaType.Live))
            add(Catalog.allCategory(MediaType.Live))
        }
    }
    var selectedCategoryId by androidx.compose.runtime.remember(initialCategoryId, currentEntry.categoryId, categories.size) {
        androidx.compose.runtime.mutableStateOf(
            initialCategoryId?.takeIf { id -> categories.any { it.id == id } }
                ?: currentEntry.categoryId.takeIf { id -> categories.any { it.id == id } }
                ?: Catalog.ALL_CATEGORY_ID,
        )
    }
    val channels = remember(catalog, selectedCategoryId, favoriteLiveEntries, historyLiveEntries) {
        when (selectedCategoryId) {
            LIVE_PICKER_FAVORITES_CATEGORY_ID -> favoriteLiveEntries
            LIVE_PICKER_HISTORY_CATEGORY_ID -> historyLiveEntries
            else -> catalog.entriesIn(MediaType.Live, selectedCategoryId)
        }
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

    Column(Modifier.fillMaxSize()) {
        LivePickerHeader(
            catalog = catalog,
            offline = offline,
            busy = busy,
            onHome = onHome,
            onOpenSection = onOpenSection,
            onSearch = onSearch,
            onEpg = onEpg,
            onSettings = onSettings,
        )

        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier
                    .width(250.dp)
                    .fillMaxHeight()
                    .background(Night.copy(alpha = 0.985f))
                    .padding(14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Catégories", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("OK choisir · OK long favori", color = MutedInk, fontSize = 9.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    state = categoryListState,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                ) {
                    items(categories, key = MediaCategory::key) { category ->
                        val isVirtual = category.id == LIVE_PICKER_FAVORITES_CATEGORY_ID ||
                            category.id == LIVE_PICKER_HISTORY_CATEGORY_ID ||
                            category.id == Catalog.ALL_CATEGORY_ID
                        FocusableSurface(
                            onClick = {
                                selectedCategoryId = category.id
                                onCategorySelected(category.id)
                            },
                            onLongClick = if (isVirtual) null else { { onToggleCategoryFavorite(category) } },
                            selected = selectedCategoryId == category.id,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    category.name,
                                    color = Ink,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!isVirtual && category.key in library.favoriteCategories) {
                                    Spacer(Modifier.width(5.dp))
                                    Text("★", color = FocusBlueBright, fontSize = 11.sp)
                                }
                                Spacer(Modifier.width(6.dp))
                                val count = when (category.id) {
                                    LIVE_PICKER_FAVORITES_CATEGORY_ID -> favoriteLiveEntries.size
                                    LIVE_PICKER_HISTORY_CATEGORY_ID -> historyLiveEntries.size
                                    else -> catalog.entriesIn(MediaType.Live, category.id).size
                                }
                                Text(count.toString(), color = MutedInk, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }

            Column(
                Modifier
                    .width(340.dp)
                    .fillMaxHeight()
                    .background(Night.copy(alpha = 0.985f))
                    .padding(14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Chaînes", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("OK aperçu · OK encore plein écran · OK long favori", color = MutedInk, fontSize = 9.sp)
                    }
                    FocusableSurface(onClick = onClose, modifier = Modifier.width(72.dp).height(38.dp)) {
                        Text("Fermer", color = Ink, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    state = channelListState,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                ) {
                    items(channels, key = MediaEntry::key) { channel ->
                        val isFocusTarget = channel.key == focusTargetKey
                        FocusableSurface(
                            onClick = { onEntrySelected(channel) },
                            onLongClick = { onToggleEntryFavorite(channel) },
                            selected = channel.key == currentEntry.key,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .then(if (isFocusTarget) Modifier.focusRequester(channelFocus) else Modifier),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(channel.number.toString(), color = MutedInk, fontSize = 9.sp, modifier = Modifier.width(38.dp))
                                ChannelLogo(channel.iconUrl, channel.displayName, Modifier.size(31.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    channel.displayName,
                                    color = Ink,
                                    fontSize = 12.sp,
                                    fontWeight = if (channel.key == currentEntry.key) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (channel.key in library.favoriteEntries) {
                                    Spacer(Modifier.width(5.dp))
                                    Text("★", color = FocusBlueBright, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(
                    "Aperçu en direct",
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Night.copy(alpha = 0.90f))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
                Spacer(Modifier.weight(1f))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Night.copy(alpha = 0.92f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        currentEntry.displayName,
                        color = Ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "CH ${currentEntry.number} · chaîne déjà en lecture · OK sur cette chaîne = plein écran",
                        color = FocusBlueBright,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LivePickerHeader(
    catalog: Catalog,
    offline: Boolean,
    busy: Boolean,
    onHome: () -> Unit,
    onOpenSection: (MediaType) -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
            .background(Night.copy(alpha = 0.985f))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreamiaLogo(compact = true)
        Spacer(Modifier.width(16.dp))
        LivePickerHeaderAction("Accueil", 90.dp, selected = false, onClick = onHome)
        Spacer(Modifier.width(6.dp))
        for (type in MediaType.entries) {
            LivePickerHeaderAction(
                label = type.displayName,
                width = 104.dp,
                selected = type == MediaType.Live,
                enabled = catalog.count(type) > 0,
                subtitle = catalog.count(type).toString(),
                onClick = { onOpenSection(type) },
            )
            Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.weight(1f))
        LivePickerHeaderAction("⌕", 52.dp, selected = false, onClick = onSearch)
        Spacer(Modifier.width(6.dp))
        LivePickerHeaderAction("EPG", 68.dp, selected = false, enabled = catalog.count(MediaType.Live) > 0, onClick = onEpg)
        Spacer(Modifier.width(6.dp))
        LivePickerHeaderAction("⚙", 52.dp, selected = false, onClick = onSettings)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(6.dp).background(if (offline) WarmSignal else Color(0xFF6CCB91)))
        Spacer(Modifier.width(5.dp))
        Text(
            when {
                busy -> "Chargement…"
                offline -> "Cache"
                else -> "Local / en ligne"
            },
            color = if (offline) WarmSignal else MutedInk,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun LivePickerHeaderAction(
    label: String,
    width: androidx.compose.ui.unit.Dp,
    selected: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    FocusableSurface(
        onClick = onClick,
        selected = selected,
        enabled = enabled,
        modifier = Modifier.width(width).height(44.dp),
    ) {
        if (subtitle == null) {
            Text(label, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
        } else {
            Column(Modifier.padding(horizontal = 10.dp)) {
                Text(label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = MutedInk, fontSize = 9.sp)
            }
        }
    }
}

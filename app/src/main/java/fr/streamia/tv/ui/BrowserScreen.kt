package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.LiveChannelSortOrder
import fr.streamia.tv.data.VodSortOrder
import fr.streamia.tv.data.BrowserNavigationStore
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.player.LivePlaybackSession
import fr.streamia.tv.ui.theme.FocusBlueBright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.debounce

internal const val FAVORITES_CATEGORY_ID = "__favorites__"

internal const val HISTORY_CATEGORY_ID = "__history__"

/** Chaînes du Direct lues en vraie 3840 × 2160, ajoutées automatiquement (voir LiveUhdDetector). */
internal const val UHD_CATEGORY_ID = "__uhd__"

/** Catégories construites par l'application (pas de chargement fournisseur, pas de favori). */
internal val VIRTUAL_CATEGORY_IDS = setOf(FAVORITES_CATEGORY_ID, HISTORY_CATEGORY_ID, UHD_CATEGORY_ID)

/** Distance (en éléments) à la fin de la liste/grille matérialisée à partir de laquelle la page suivante est demandée. */
internal const val LOAD_MORE_THRESHOLD = 20

/** Au-delà, un tri pas encore en mémoire est fait hors du thread principal (liste « Chargement… » d'ici là). */
private const val HEAVY_SORT_THRESHOLD = 2_000

/** Images préchargées après la dernière ligne/carte visible. */
internal const val ARTWORK_PREFETCH_COUNT = 12

/** Délai sans déplacement du focus avant de mémoriser le contenu focalisé. */
private const val FOCUSED_ENTRY_SAVE_DELAY_MS = 1_000L

/** Laisse à la grille le temps de se composer après le défilement avant de réclamer le focus. */
internal const val BROWSER_RESTORE_FOCUS_DELAY_MS = 60L

@Composable
fun BrowserScreen(
    catalog: Catalog,
    credentials: ServerCredentials,
    livePlaybackSession: LivePlaybackSession,
    liveVideoSurface: @Composable (LiveVideoSurfacePlacement) -> Unit,
    /** Programmes du jour par chaîne Direct, pré-rapprochés hors du thread principal (voir StreamiaViewModel.liveEpgPrograms). */
    epgPrograms: Map<String, List<EpgProgram>> = emptyMap(),
    library: UserLibrarySnapshot,
    appSettings: AppSettings,
    loadingCategoryKeys: Set<String> = emptySet(),
    vodPages: Map<String, List<MediaEntry>> = emptyMap(),
    categoryLoadErrors: Set<String> = emptySet(),
    parentalUnlocked: Boolean,
    offline: Boolean,
    busy: Boolean,
    message: String?,
    initialType: MediaType? = null,
    initialCategoryId: String? = null,
    /** Élément à re-sélectionner au retour depuis une fiche (voir [ContentReturnContext]). */
    restoreEntryKey: String? = null,
    onRestoreConsumed: () -> Unit = {},
    onEntrySelected: (MediaEntry) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    onToggleCategoryFavorite: (MediaCategory) -> Unit,
    onVerifyParentalPin: suspend (String) -> Boolean,
    onRememberContent: (MediaEntry) -> Unit,
    onLivePreviewWatched: (MediaEntry) -> Unit = {},
    onLocationChanged: (MediaType, String?) -> Unit,
    onEnsureCategoryLoaded: (MediaType, String, VodSortOrder) -> Unit,
    onLoadMoreInCategory: (MediaType, String, VodSortOrder) -> Unit,
    /** Chaîne lancée en plein écran depuis la liste affichée : le zapping parcourt cette liste. */
    onLiveEntrySelected: (MediaEntry, List<MediaEntry>) -> Unit = { entry, _ -> onEntrySelected(entry) },
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onSettings: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    fun leaveBrowserForHome() {
        livePlaybackSession.stop(clearSession = true)
        onHome()
    }
    BackHandler(onBack = ::leaveBrowserForHome)
    val context = LocalContext.current.applicationContext
    val navigationStore = remember(credentials) { BrowserNavigationStore(context, credentials) }
    // Contenu focalisé dans la grille : gardé en mémoire et écrit seulement quand le focus se pose
    // (1 s sans mouvement) ou à la sortie de l'écran. L'écrire en préférences à chaque appui sur
    // une flèche faisait une écriture disque par déplacement du focus.
    val focusedEntry = remember(credentials) { mutableStateOf<Pair<MediaType, String>?>(null) }
    LaunchedEffect(navigationStore) {
        snapshotFlow { focusedEntry.value }
            .debounce(FOCUSED_ENTRY_SAVE_DELAY_MS)
            .collect { saved -> saved?.let { (type, key) -> navigationStore.saveEntry(type, key) } }
    }
    DisposableEffect(navigationStore) {
        onDispose { focusedEntry.value?.let { (type, key) -> navigationStore.saveEntry(type, key) } }
    }
    val restoredLiveSelection = remember(credentials) {
        val stored = navigationStore.liveSelection()
        val returnedEntryKey = LiveBrowserReturnState.consume()
        val entryKey = returnedEntryKey ?: stored?.entryKey
        val categoryId = when {
            entryKey == null -> stored?.categoryId
            returnedEntryKey == null || returnedEntryKey == stored?.entryKey -> stored?.categoryId
            else -> catalog.entry(entryKey)?.categoryId
        }
        categoryId to entryKey
    }

    val defaultType = initialType
        ?.takeIf { catalog.count(it) > 0 }
        ?: MediaType.entries.firstOrNull { catalog.count(it) > 0 }
        ?: MediaType.Live
    // initialType/initialCategoryId ne servent qu'à l'entrée dans l'écran. Ils ne doivent PAS faire
    // partie des clés de mémorisation : `catalog` change d'instance à chaque page chargée, et le
    // claver dessus réinitialisait `selectedType`/`selectedCategoryId` à chaque chargement de
    // catégorie — la sélection revenait à la catégorie par défaut/enregistrée et annulait le clic.
    // `credentials` est stable pour toute la durée de visite de l'écran (il ne change qu'au
    // changement de profil, qui détruit l'écran de toute façon).
    var selectedType by remember(credentials) { mutableStateOf(defaultType) }
    var lastLiveEntryKey by remember(credentials) { mutableStateOf(restoredLiveSelection.second) }
    var selectedCategoryId by remember(credentials) {
        mutableStateOf(
            if (defaultType == MediaType.Live) {
                restoredLiveSelection.first ?: initialCategoryId ?: defaultCategoryId(catalog, MediaType.Live)
            } else {
                initialCategoryId ?: navigationStore.category(defaultType) ?: defaultCategoryId(catalog, defaultType)
            },
        )
    }

    // Tri de la catégorie affichée : celui choisi via son bouton « Trier » (mémorisé par
    // catégorie), sinon celui de Paramètres. Le Direct garde son propre tri des chaînes.
    var categorySortOrder by remember(credentials, selectedType, selectedCategoryId, appSettings.vodSortOrder) {
        mutableStateOf(
            if (selectedType == MediaType.Live) VodSortOrder.Provider
            else navigationStore.categorySort(selectedType, selectedCategoryId) ?: appSettings.vodSortOrder,
        )
    }

    val hiddenCategoryIds = remember(catalog, selectedType, library.hiddenCategories) {
        catalog.categoriesFor(selectedType)
            .filter { it.key in library.hiddenCategories }
            .mapTo(mutableSetOf(), MediaCategory::id)
    }
    // Une catégorie verrouillée reste visible dans le rail (l'utilisateur doit voir qu'elle
    // existe pour pouvoir la déverrouiller) : seul son contenu disparaît des vues agrégées
    // (Tout, favoris, historique) tant que le code n'a pas été saisi cette session.
    val lockedCategoryIds = remember(catalog, selectedType, library.lockedCategories, appSettings.parentalControlEnabled, parentalUnlocked) {
        if (!appSettings.parentalControlEnabled || parentalUnlocked) emptySet()
        else catalog.categoriesFor(selectedType)
            .filter { it.key in library.lockedCategories }
            .mapTo(mutableSetOf(), MediaCategory::id)
    }
    val excludedCategoryIds = remember(hiddenCategoryIds, lockedCategoryIds) { hiddenCategoryIds + lockedCategoryIds }
    val baseCategories = remember(catalog, selectedType, library.hiddenCategories) {
        catalog.categoriesFor(selectedType).filterNot { it.key in library.hiddenCategories }
    }
    val favoriteEntriesForType = remember(catalog, selectedType, library.favoriteEntries, library.hiddenEntries, excludedCategoryIds) {
        library.favoriteEntries.asSequence()
            .mapNotNull(catalog::entry)
            .filter {
                it.type == selectedType &&
                    it.key !in library.hiddenEntries &&
                    it.categoryId !in excludedCategoryIds
            }
            .toList()
    }
    val historyForType = remember(catalog, selectedType, library.history, library.hiddenEntries, excludedCategoryIds) {
        library.history.asSequence()
            .map { item -> item to (catalog.entry(item.entry.key) ?: item.entry) }
            .filter { (_, entry) ->
                entry.type == selectedType &&
                    entry.key !in library.hiddenEntries &&
                    entry.categoryId !in excludedCategoryIds
            }
            .toList()
    }
    // Ordre de découverte ; une chaîne masquée, verrouillée ou disparue de la liste n'y figure pas.
    val uhdEntriesForType = remember(catalog, selectedType, library.uhdEntries, library.hiddenEntries, excludedCategoryIds) {
        if (selectedType != MediaType.Live) emptyList()
        else library.uhdEntries.asSequence()
            .mapNotNull(catalog::entry)
            .filter {
                it.type == MediaType.Live &&
                    it.key !in library.hiddenEntries &&
                    it.categoryId !in excludedCategoryIds
            }
            .toList()
    }
    val categories = remember(baseCategories, favoriteEntriesForType.isNotEmpty(), historyForType.isNotEmpty(), uhdEntriesForType.isNotEmpty(), selectedType, library.favoriteCategories) {
        buildBrowserCategories(
            type = selectedType,
            providerCategories = baseCategories,
            // Seules les catégories du Direct peuvent être mises en favori (remontées en tête).
            favoriteCategoryKeys = if (selectedType == MediaType.Live) library.favoriteCategories else emptySet(),
            hasFavoriteEntries = favoriteEntriesForType.isNotEmpty(),
            hasHistory = historyForType.isNotEmpty(),
            hasUhdEntries = uhdEntriesForType.isNotEmpty(),
        )
    }
    /**
     * [allowHeavySort] faux (changement de catégorie, sur le thread principal) : `null` si la liste
     * doit d'abord être triée alors que ce tri n'est pas encore en mémoire et porte sur beaucoup de
     * chaînes. Elle est alors triée hors du thread principal (effet plus bas), sans geler la navigation.
     */
    fun computeEntries(allowHeavySort: Boolean = true): List<MediaEntry>? = when (selectedCategoryId) {
        FAVORITES_CATEGORY_ID -> favoriteEntriesForType
        HISTORY_CATEGORY_ID -> historyForType.map { it.second }
        UHD_CATEGORY_ID -> uhdEntriesForType
        else -> {
            // Films/Séries paginés : ordre des pages lues en base, déjà triées (voir vodPages).
            // Rien tant que la première page au tri courant n'est pas là, plutôt qu'un ordre
            // provisoire qui se réorganiserait sous les yeux de l'utilisateur.
            val paged = selectedType != MediaType.Live && catalog.isPaged
            val pageEntries = vodPages[vodPageKey(selectedType, selectedCategoryId, categorySortOrder)]
            val source = when {
                !paged -> catalog.entriesIn(selectedType, selectedCategoryId)
                pageEntries == null -> emptyList()
                else -> pageEntries.filter {
                    // Contenu déplacé ailleurs dans l'organisateur : n'appartient plus à cette catégorie.
                    selectedCategoryId == Catalog.ALL_CATEGORY_ID || it.categoryId == selectedCategoryId
                }
            }
            // Tri déjà fait pour cette même source (retour du plein écran, aller-retour) : réutilisé.
            val sortOrder: Any? = when {
                selectedType == MediaType.Live -> appSettings.liveChannelSortOrder.takeIf { it != LiveChannelSortOrder.Provider }
                paged -> null
                else -> categorySortOrder.takeIf { it != VodSortOrder.Provider }
            }
            val memo = sortOrder?.let { BrowserSortMemo.get(source, library.hiddenEntries, excludedCategoryIds, it) }
            if (memo == null && sortOrder != null && !allowHeavySort && source.size > HEAVY_SORT_THRESHOLD) return null
            memo ?: run {
                val filtered = source.filterNot {
                    it.key in library.hiddenEntries || it.categoryId in excludedCategoryIds
                }
                // « Favoris »/« Historique » gardent leur propre ordre (ajout / dernière lecture),
                // qui perdrait son sens sous un tri alphabétique ou par numéro : seule une vraie
                // catégorie suit la préférence de tri de son type.
                val result = when {
                    selectedType == MediaType.Live -> sortedForLiveDisplay(filtered, appSettings.liveChannelSortOrder)
                    paged -> filtered
                    else -> sortedForVodDisplay(filtered, categorySortOrder)
                }
                if (sortOrder != null) BrowserSortMemo.put(source, library.hiddenEntries, excludedCategoryIds, sortOrder, result)
                result
            }
        }
    }
    // Changement de type/catégorie (geste de l'utilisateur) : calcul immédiat, pour que la liste
    // affichée corresponde toujours à la catégorie choisie. Les autres changements (pages chargées
    // en arrière-plan, favoris, tri…) recalculent hors du thread principal en gardant la liste
    // courante affichée d'ici là : trier des milliers de chaînes à chaque page fusionnée faisait
    // saccader la navigation pendant l'hydratation du catalogue.
    val location = selectedType to selectedCategoryId
    var computedEntries by remember(credentials) { mutableStateOf(location to computeEntries(allowHeavySort = false)) }
    if (computedEntries.first != location) computedEntries = location to computeEntries(allowHeavySort = false)
    // Favoris/historique ne comptent que pour leur propre catégorie : une chaîne regardée en aperçu
    // (ajoutée à l'historique) relançait sinon le tri de toute la liste affichée (« Tout » : des
    // dizaines de milliers de chaînes) à chaque aperçu de plus de 20 s.
    val favoritesKey = favoriteEntriesForType.takeIf { selectedCategoryId == FAVORITES_CATEGORY_ID }
    val historyKey = historyForType.takeIf { selectedCategoryId == HISTORY_CATEGORY_ID }
    val uhdKey = uhdEntriesForType.takeIf { selectedCategoryId == UHD_CATEGORY_ID }
    // Liste en attente de tri (grande catégorie) : clé supplémentaire pour la calculer tout de suite.
    val pendingLocation = location.takeIf { computedEntries.second == null }
    LaunchedEffect(
        catalog, favoritesKey, historyKey, uhdKey, excludedCategoryIds, library.hiddenEntries, vodPages, categorySortOrder,
        appSettings.liveChannelSortOrder, appSettings.vodSortOrder, pendingLocation,
    ) {
        val target = location
        val result = withContext(Dispatchers.Default) { computeEntries() }
        if (computedEntries.first == target) computedEntries = target to result
    }
    val entries = computedEntries.second.orEmpty()
    val entriesPending = computedEntries.second == null
    val historyByKey = remember(historyForType) { historyForType.associate { it.second.key to it.first } }

    var pendingLockedCategory by remember { mutableStateOf<MediaCategory?>(null) }
    fun selectCategory(category: MediaCategory) {
        val locked = appSettings.parentalControlEnabled && !parentalUnlocked && category.key in library.lockedCategories
        if (locked) pendingLockedCategory = category else selectedCategoryId = category.id
    }

    androidx.compose.runtime.LaunchedEffect(selectedType, categories) {
        if (categories.none { it.id == selectedCategoryId }) {
            selectedCategoryId = defaultCategoryId(catalog, selectedType)
        }
    }
    val currentCategoryKey = Catalog.categoryKey(selectedType, selectedCategoryId)
    // Pages absentes au tri courant (tri changé, liste actualisée) : relues sans quitter l'écran.
    val pagesMissing = selectedType != MediaType.Live && catalog.isPaged &&
        vodPageKey(selectedType, selectedCategoryId, categorySortOrder) !in vodPages
    androidx.compose.runtime.LaunchedEffect(selectedType, selectedCategoryId, pagesMissing) {
        onLocationChanged(selectedType, selectedCategoryId)
        if (selectedType != MediaType.Live) {
            navigationStore.saveCategory(selectedType, selectedCategoryId)
        }
        if (selectedCategoryId !in VIRTUAL_CATEGORY_IDS) {
            onEnsureCategoryLoaded(selectedType, selectedCategoryId, categorySortOrder)
        }
    }

    val isLive = selectedType == MediaType.Live
    // Lue une fois par catégorie (la liste des chaînes est recréée à chaque catégorie) et non plus
    // à chaque recomposition de l'écran.
    val liveListPosition = remember(navigationStore, selectedCategoryId) {
        navigationStore.listPosition(MediaType.Live, selectedCategoryId)
    }

    // En Direct, la vidéo doit remplir tout l'écran, bandeau du haut compris : LiveCatalogLayout
    // est donc posé en premier (plein écran) dans ce Box, et le bandeau + le message flottent
    // ensuite par-dessus, translucides, plutôt que de réserver leur propre bande opaque en haut
    // comme le fait la disposition Column classique utilisée par les autres écrans (VOD compris).
    // Panneaux catégories/chaînes du Direct : le bandeau du haut apparaît avec eux (même fondu, y
    // compris au retour du plein écran) et disparaît aussitôt quand une chaîne passe en plein écran.
    var liveControlsVisible by remember(isLive) { mutableStateOf(false) }
    val liveControlsAlpha by animateFloatAsState(
        if (liveControlsVisible) 1f else 0f,
        animationSpec = tween(160),
        label = "live-header-alpha",
    )
    Box(Modifier.fillMaxSize()) {
        if (isLive) {
            LiveCatalogLayout(
                catalog = catalog,
                epgPrograms = epgPrograms,
                credentials = credentials,
                livePlaybackSession = livePlaybackSession,
                liveVideoSurface = liveVideoSurface,
                appSettings = appSettings,
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                entries = entries,
                entriesPending = entriesPending,
                initialPreviewKey = lastLiveEntryKey,
                initialListPosition = liveListPosition,
                favoriteCategories = library.favoriteCategories,
                favoriteEntries = library.favoriteEntries,
                lockedCategories = library.lockedCategories,
                historyCount = historyForType.size,
                uhdCount = uhdEntriesForType.size,
                onCategorySelected = ::selectCategory,
                onPreviewChanged = {
                    lastLiveEntryKey = it.key
                    navigationStore.saveLiveSelection(selectedCategoryId, it.key)
                    onRememberContent(it)
                },
                onListPositionChanged = { categoryId, position ->
                    navigationStore.saveListPosition(MediaType.Live, categoryId, position)
                },
                onToggleCategoryFavorite = onToggleCategoryFavorite,
                onEntrySelected = { onLiveEntrySelected(it, entries) },
                onToggleEntryFavorite = onToggleEntryFavorite,
                onLoadMore = {
                    // Catégories de l'application : déjà complètes, rien à demander au fournisseur.
                    if (selectedCategoryId !in VIRTUAL_CATEGORY_IDS) onLoadMoreInCategory(MediaType.Live, selectedCategoryId, VodSortOrder.Provider)
                },
                onLivePreviewWatched = onLivePreviewWatched,
                controlsVisible = liveControlsVisible,
                onControlsVisibleChange = { liveControlsVisible = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.graphicsLayer { if (isLive) alpha = if (liveControlsVisible) liveControlsAlpha else 0f }) {
            BrowserHeader(
                catalog = catalog,
                selectedType = selectedType,
                offline = offline,
                busy = busy,
                translucent = isLive,
                onHome = ::leaveBrowserForHome,
                onTypeSelected = {
                    if (it != MediaType.Live) livePlaybackSession.stop(clearSession = true)
                    selectedType = it
                    selectedCategoryId = navigationStore.category(it) ?: defaultCategoryId(catalog, it)
                },
                onSearch = onSearch,
                onEpg = onEpg,
                onSettings = onSettings,
            )
            }

            if (message != null) {
                FocusableSurface(
                    onClick = onDismissMessage,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 3.dp).height(48.dp),
                ) {
                    Text(
                        "$message  ·  OK pour fermer",
                        color = if (message.contains("média", ignoreCase = true) || message.contains("import", ignoreCase = true)) FocusBlueBright else MaterialTheme.colorScheme.error,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!isLive) {
                VodCatalogLayout(
                    type = selectedType,
                    catalog = catalog,
                    categories = categories,
                    selectedCategoryId = selectedCategoryId,
                    entries = entries,
                    loading = entriesPending || currentCategoryKey in loadingCategoryKeys || (pagesMissing && currentCategoryKey !in categoryLoadErrors),
                    loadError = currentCategoryKey in categoryLoadErrors,
                    favoriteCategories = library.favoriteCategories,
                    favoriteEntries = library.favoriteEntries,
                    lockedCategories = library.lockedCategories,
                    historyCount = historyForType.size,
                    historyByKey = historyByKey,
                    restoreEntryKey = restoreEntryKey,
                    onRestoreConsumed = onRestoreConsumed,
                    onCategorySelected = ::selectCategory,
                    onToggleCategoryFavorite = onToggleCategoryFavorite,
                    onEntrySelected = onEntrySelected,
                    onEntryFocused = { focusedEntry.value = selectedType to it.key },
                    onToggleEntryFavorite = onToggleEntryFavorite,
                    onLoadMore = { onLoadMoreInCategory(selectedType, selectedCategoryId, categorySortOrder) },
                    // Favoris/Historique ont leur propre ordre (ajout, dernière lecture) : pas de tri.
                    sortOrder = categorySortOrder.takeUnless { selectedCategoryId == FAVORITES_CATEGORY_ID || selectedCategoryId == HISTORY_CATEGORY_ID },
                    onSortSelected = { order ->
                        navigationStore.saveCategorySort(selectedType, selectedCategoryId, order)
                        categorySortOrder = order
                    },
                    modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                )
            }
        }

        pendingLockedCategory?.let { category ->
            ParentalPinDialog(
                title = "Contenu verrouillé",
                subtitle = "Entrez le code parental pour accéder à « ${category.name} »",
                onSubmit = { pin ->
                    val correct = onVerifyParentalPin(pin)
                    if (correct) {
                        selectedCategoryId = category.id
                        pendingLockedCategory = null
                    }
                    correct
                },
                onCancel = { pendingLockedCategory = null },
            )
        }
    }
}

private fun defaultCategoryId(catalog: Catalog, type: MediaType): String =
    catalog.categoriesFor(type).firstOrNull { catalog.countIn(type, it.id) > 0 }?.id
        ?: Catalog.ALL_CATEGORY_ID

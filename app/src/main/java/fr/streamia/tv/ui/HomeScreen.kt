package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import fr.streamia.tv.beinsports.ResolvedBeinProgrammeItem
import fr.streamia.tv.data.CurrentWeather
import fr.streamia.tv.data.HomeBlock
import fr.streamia.tv.data.HomePlace
import fr.streamia.tv.data.PrayerMethod
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.data.isResumable
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.recommendation.RecommendationRow
import fr.streamia.tv.ukguide.ResolvedUkProgrammeItem
import fr.streamia.tv.tvprogramme.ResolvedTvProgrammeItem
import fr.streamia.tv.tvprogramme.ResolvedTvProgrammeNowItem
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.TypeBody
import fr.streamia.tv.ui.theme.RadiusPill
import kotlinx.coroutines.delay
import java.time.LocalTime

@Composable
fun HomeScreen(
    catalog: Catalog,
    weatherPlace: HomePlace?,
    weather: CurrentWeather?,
    prayerMethod: PrayerMethod,
    offline: Boolean,
    busy: Boolean,
    library: UserLibrarySnapshot,
    parentalControlEnabled: Boolean = false,
    parentalUnlocked: Boolean = false,
    catalogLoading: Boolean = false,
    resumeRowEnabled: Boolean = true,
    favoritesRowEnabled: Boolean = true,
    footballScoresEnabled: Boolean = true,
    recentChannelsEnabled: Boolean = true,
    recommendationRows: List<RecommendationRow> = emptyList(),
    tvProgrammeNow: List<ResolvedTvProgrammeNowItem> = emptyList(),
    tvProgrammeTonight: List<ResolvedTvProgrammeItem> = emptyList(),
    beinSportsNow: List<ResolvedBeinProgrammeItem> = emptyList(),
    beinSportsNext: List<ResolvedBeinProgrammeItem> = emptyList(),
    ukGuideNow: List<ResolvedUkProgrammeItem> = emptyList(),
    ukGuideNext: List<ResolvedUkProgrammeItem> = emptyList(),
    liveMatches: List<fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch> = emptyList(),
    /** Blocs pas encore chargés une première fois : rangée squelette à la place du contenu. */
    pendingBlocks: Set<HomeBlock> = emptySet(),
    liveMatchesPending: Boolean = false,
    liveMatchesResolving: Boolean = false,
    /** Assistant IA actif : la rangée d'accès à ses écrans apparaît, et les raisons des recommandations avec elle. */
    aiActive: Boolean = false,
    aiReasons: Map<String, String> = emptyMap(),
    aiBrief: BriefUiState = BriefUiState(),
    aiRemote: RemoteUiState = RemoteUiState(),
    /** Dernières réponses de « Ce soir ? », résumées sur sa carte. */
    aiTonightAnswers: fr.streamia.tv.data.TonightAnswers = DefaultTonightAnswers,
    onOpenAssistant: (AssistantMode) -> Unit = {},
    onRefreshAiBrief: () -> Unit = {},
    restoreContext: ContentReturnContext? = null,
    focusTarget: HomeFocusTarget? = null,
    onFocusConsumed: () -> Unit = {},
    onRestoreConsumed: () -> Unit = {},
    onOpenSection: (MediaType) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onRefresh: () -> Unit,
    onChangePlaylist: () -> Unit,
    onResumePlayback: (MediaEntry) -> Unit,
    onOpenHomeEntry: (MediaEntry, String, String) -> Unit,
    onOpenLiveMatches: () -> Unit,
    onRefreshTvProgrammeNow: () -> Unit,
    onRefreshBeinSportsGuide: () -> Unit,
    onRefreshUkGuide: () -> Unit,
    onRefreshLiveMatches: () -> Unit,
    onRefreshWeather: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val gridFocusRequester = remember { FocusRequester() }
    val assistantFocus = remember { FocusRequester() }
    val restoringHome = restoreContext?.origin == ContentReturnOrigin.Home
    // À l'arrivée seulement : la cible de retour, une fois consommée, ne doit pas renvoyer le focus à la grille.
    LaunchedEffect(Unit) {
        if (!restoringHome && focusTarget == null) runCatching { firstFocus.requestFocus() }
    }

    // Comme pour le guide TV, une catégorie verrouillée et pas encore déverrouillée cette session
    // est traitée comme masquée ici : l'accueil ouvre le contenu directement (reprise, favori),
    // sans passer par le geste de sélection de catégorie qui déclenche le code dans le navigateur.
    val excludedCategories = remember(library.hiddenCategories, library.lockedCategories, parentalControlEnabled, parentalUnlocked) {
        if (!parentalControlEnabled || parentalUnlocked) library.hiddenCategories else library.hiddenCategories + library.lockedCategories
    }
    val hiddenCategoryIdsByType = remember(catalog, excludedCategories) {
        catalog.categories
            .filter { it.key in excludedCategories }
            .groupBy { it.type }
            .mapValues { (_, categories) -> categories.mapTo(mutableSetOf()) { it.id } }
    }

    // Reprise en cours : uniquement le contenu VOD (Films/Séries) assez avancé pour être
    // reprenable — Direct n'a pas de notion de position de lecture. On relit l'entrée depuis le
    // catalogue courant (icône/nom à jour) tout en gardant l'item d'historique pour sa progression.
    val resumeCards = remember(catalog, library.history, library.hiddenEntries, library.watchedEntries, hiddenCategoryIdsByType, resumeRowEnabled) {
        if (!resumeRowEnabled) return@remember emptyList()
        library.history.asSequence()
            .filter {
                it.entry.type != MediaType.Live &&
                    it.isResumable() &&
                    it.entry.key !in library.hiddenEntries &&
                    it.entry.key !in library.watchedEntries &&
                    it.entry.categoryId !in hiddenCategoryIdsByType[it.entry.type].orEmpty()
            }
            .map { item ->
                // Les entrées Séries de l'historique sont des épisodes synthétisés à la lecture
                // (playEpisode), absents du catalogue sous leur propre clé — celui-ci n'indexe que
                // les séries parentes. Relire via catalog.entry() pour un épisode risquerait donc,
                // en cas de collision d'identifiant entre un episode.id et un series.id, de
                // substituer silencieusement la série parente (non lisible) à l'épisode enregistré.
                // Seuls les films sont réellement présents dans le catalogue sous leur propre clé.
                val entry = if (item.entry.type == MediaType.Movie) catalog.entry(item.entry.key) ?: item.entry else item.entry
                entry to item.progress
            }
            .toList()
    }

    // Favoris : accès direct depuis l'accueil sans repasser par une catégorie. Si un favori est
    // aussi présent dans l'historique, sa progression est affichée pour rester cohérent avec le
    // reste de l'app plutôt que d'inventer un second indicateur.
    val favoriteCards = remember(catalog, library.favoriteEntries, library.history, library.hiddenEntries, hiddenCategoryIdsByType, favoritesRowEnabled) {
        if (!favoritesRowEnabled) return@remember emptyList()
        val historyByKey = library.history.associateBy { it.entry.key }
        library.favoriteEntries.asSequence()
            .filterNot { it in library.hiddenEntries }
            .mapNotNull(catalog::entry)
            .filter { it.categoryId !in hiddenCategoryIdsByType[it.type].orEmpty() }
            .map { entry -> entry to historyByKey[entry.key]?.progress?.takeIf { it > 0.02f } }
            .toList()
    }

    // 10 dernières chaînes regardées : l'historique est déjà trié du plus récent au plus ancien.
    val recentChannelCards = remember(catalog, library.history, library.hiddenEntries, hiddenCategoryIdsByType, recentChannelsEnabled) {
        if (!recentChannelsEnabled) return@remember emptyList()
        library.history.asSequence()
            .filter {
                it.entry.type == MediaType.Live &&
                    it.entry.key !in library.hiddenEntries &&
                    it.entry.categoryId !in hiddenCategoryIdsByType[MediaType.Live].orEmpty()
            }
            .map { (catalog.entry(it.entry.key) ?: it.entry) to null as Float? }
            .take(10)
            .toList()
    }

    // Une seule horloge pour toutes les rangées « en direct » (au lieu d'une boucle par rangée,
    // chacune invalidant l'accueil de son côté).
    val hasLiveRows = liveMatches.isNotEmpty() || tvProgrammeNow.isNotEmpty() || beinSportsNow.isNotEmpty() || ukGuideNow.isNotEmpty()
    // Lue seulement par les cartes qui affichent une progression (lambdas) : mise à jour toutes les
    // 30 s, elle recomposait tout l'accueil. Les cartes de matchs passent par derivedStateOf : la
    // racine n'est recomposée que si la liste des matchs en cours change réellement.
    val liveRowsNow = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(hasLiveRows) {
        if (!hasLiveRows) return@LaunchedEffect
        while (true) {
            liveRowsNow.longValue = System.currentTimeMillis()
            delay(TV_PROGRAMME_PROGRESS_REFRESH_MS)
        }
    }
    val liveMatchCardsState = remember(liveMatches, liveMatchesResolving) {
        derivedStateOf { liveMatchCards(liveMatches, liveRowsNow.longValue / 1000, liveMatchesResolving) }
    }
    val liveMatchCards = liveMatchCardsState.value
    val tvProgrammeNowEpochMillis: () -> Long = remember { { liveRowsNow.longValue } }
    val beinNowEpochMillis = tvProgrammeNowEpochMillis
    val ukGuideNowTime: () -> LocalTime = remember {
        { java.time.Instant.ofEpochMilli(liveRowsNow.longValue).atZone(UK_GUIDE_ZONE).toLocalTime() }
    }
    // Seulement app visible : en arrière-plan (bouton Home), aucune requête vers les sites tiers.
    // Au retour, rafraîchissement immédiat ; au premier affichage, les chargements de démarrage
    // (scheduleSecondaryLoads) s'en chargent déjà.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        var resumed = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            onRefreshWeather()
            if (resumed) {
                onRefreshTvProgrammeNow()
                onRefreshBeinSportsGuide()
                onRefreshUkGuide()
                onRefreshLiveMatches()
            }
            resumed = true
            while (true) {
                delay(TV_PROGRAMME_DATA_REFRESH_MS)
                onRefreshTvProgrammeNow()
                onRefreshBeinSportsGuide()
                onRefreshUkGuide()
                onRefreshLiveMatches()
                onRefreshWeather()
            }
        }
    }

    // Le focus initial va toujours à la rangée la plus haute réellement affichée, pour ne jamais
    // demander le focus d'un composant pas encore composé (grille hors écran si les deux rangées
    // sont présentes). Sans historique ni favori (cas courant après import), le comportement est
    // strictement identique à l'ancien écran fixe.
    // Une carte précise de la grille à refocaliser (retour depuis une tuile) l'emporte sur le focus
    // par défaut : les rangées ne réclament alors pas le focus. Un retour vers une carte de rangée
    // (origine « Home ») reste prioritaire.
    // La navigation principale (Direct, Films, Séries…) est en tête de l'accueil et reçoit le focus
    // initial : le direct est à un appui sur OK, les rangées de contenu défilent en dessous.
    val preferGridFocus = focusTarget != null && !restoringHome

    val showAiBrief = aiBrief.visibleOnHome()
    val homeListState = rememberLazyListState()
    val restoreTarget = restoreContext?.takeIf { it.origin == ContentReturnOrigin.Home }
    val effectiveRestoreRowKey = restoreTarget?.homeRowKey
    val tvGuidePending = HomeBlock.TvProgrammeNow in pendingBlocks || HomeBlock.TvProgrammeTonight in pendingBlocks ||
        HomeBlock.BeinSportsNow in pendingBlocks || HomeBlock.BeinSportsNext in pendingBlocks ||
        HomeBlock.UkGuideNow in pendingBlocks || HomeBlock.UkGuideNext in pendingBlocks
    val tvGuideLoaded = tvProgrammeNow.isNotEmpty() || tvProgrammeTonight.isNotEmpty() || beinSportsNow.isNotEmpty() ||
        beinSportsNext.isNotEmpty() || ukGuideNow.isNotEmpty() || ukGuideNext.isNotEmpty()
    val recoGroups = remember(recommendationRows) { RecoGroup.entries.filter { it.rowsIn(recommendationRows).isNotEmpty() } }
    val visibleRowKeys = remember(
        resumeCards,
        favoriteCards,
        liveMatchCards,
        recentChannelCards,
        tvGuideLoaded,
        tvGuidePending,
        recoGroups,
        recommendationRows,
        footballScoresEnabled,
        liveMatchesPending,
        pendingBlocks,
        aiActive,
    ) {
        buildList {
            // Même ordre que la LazyColumn, après l'en-tête et le dock : le hero (reprise, assistant, résumé) d'abord.
            if (aiActive || resumeCards.isNotEmpty()) add(HERO_ROW_KEY)
            if (tvGuideLoaded || tvGuidePending) add(HomeRowKey.TvProgramme)
            if (footballScoresEnabled || liveMatchCards.isNotEmpty() || liveMatchesPending) add(HomeRowKey.LiveMatches)
            if (favoriteCards.isNotEmpty() || recentChannelCards.isNotEmpty()) add(HomeRowKey.MyChannels)
            if (recommendationRows.isEmpty() && HomeBlock.Recommendations in pendingBlocks) repeat(2) { add("recommendations-skeleton") }
            recoGroups.forEach { add(it.key) }
        }
    }
    // Clé de rangée enregistrée au retour → élément de liste qui la porte (plusieurs rangées partagent un même élément).
    fun listKeyOf(rowKey: String?): String? = when (rowKey) {
        null -> null
        HomeRowKey.Resume, HomeRowKey.AiBrief, AI_ASSISTANT_ROW_KEY -> HERO_ROW_KEY
        HomeRowKey.Favorites, HomeRowKey.RecentChannels -> HomeRowKey.MyChannels
        else -> RecoGroup.entries.firstOrNull { g -> g.kinds.any { HomeRowKey.recommendation(it) == rowKey } }?.key ?: rowKey
    }
    LaunchedEffect(effectiveRestoreRowKey, visibleRowKeys) {
        val rowIndex = listKeyOf(effectiveRestoreRowKey)?.let(visibleRowKeys::indexOf) ?: -1
        if (rowIndex >= 0) {
            homeListState.scrollToItem(rowIndex + 2)
        } else if (restoringHome) {
            // La rangée ou la carte visée a disparu entre-temps (match terminé, catégorie masquée,
            // rangée recalculée) : sans ce repli, l'accueil resterait sans focus et la télécommande
            // ne répondrait plus.
            runCatching { firstFocus.requestFocus() }
        }
    }

    // Retour depuis une tuile (TV en direct, Films, Séries, Recherche, Guide TV, Paramètres…) :
    // on fait défiler jusqu'à la grille d'actions puis on repose le focus sur la même carte.
    val gridListIndex = 1
    LaunchedEffect(focusTarget, gridListIndex, restoringHome) {
        if (focusTarget == null) return@LaunchedEffect
        if (restoringHome) {
            // Un retour vers une carte de rangée prime : on abandonne la cible de grille.
            onFocusConsumed()
            return@LaunchedEffect
        }
        val toAssistant = focusTarget == HomeFocusTarget.Assistant && aiActive
        val inHeader = focusTarget == HomeFocusTarget.Settings || focusTarget == HomeFocusTarget.Refresh || focusTarget == HomeFocusTarget.ChangePlaylist
        homeListState.scrollToItem(if (toAssistant) gridListIndex + 1 else if (inHeader) 0 else gridListIndex)
        delay(RESTORE_FOCUS_DELAY_MS)
        runCatching { (if (toAssistant) assistantFocus else gridFocusRequester).requestFocus() }
        onFocusConsumed()
    }

    // Retour sur l'accueil : un premier appui prévient, le second (dans les 2,5 s) quitte l'app —
    // un seul appui fermait l'application sans prévenir.
    var exitArmed by remember { mutableStateOf(false) }
    BackHandler(enabled = !exitArmed) { exitArmed = true }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(EXIT_CONFIRM_WINDOW_MS)
            exitArmed = false
        }
    }
    var confirmChangePlaylist by remember { mutableStateOf(false) }
    var showRemote by remember { mutableStateOf(false) }
    val changePlaylistFocus = remember { FocusRequester() }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = homeListState,
        modifier = Modifier
            // Carte (ou repli) refocalisée : la cible est consommée, sinon chaque actualisation de
            // rangée referait défiler l'accueil et un retour de Paramètres viserait encore la carte.
            .onFocusChanged { if (it.hasFocus && restoringHome) onRestoreConsumed() }
            .fillMaxSize()
            .padding(horizontal = 46.dp, vertical = 30.dp),
    ) {
        item(key = "home-header", contentType = "header") {
            Column(Modifier.fillMaxWidth()) {
                GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(RadiusPill)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 26.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            StreamiaLogo(compact = true)
                        }
                        LocalClockText()
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.End) {
                                WeatherAndPrayers(weatherPlace, weather, prayerMethod)
                                // Nom de la liste et expiration : dans Paramètres. Ne reste ici que l'état anormal.
                                if (offline || catalogLoading) {
                                    Text(
                                        if (offline) "Mode cache" else "Chargement du catalogue…",
                                        color = if (offline) FocusBlueBright else MutedInk,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.width(20.dp))
                            HomeHeaderActions(
                                showRemote = aiActive,
                                refreshEnabled = !busy && !catalogLoading,
                                focusTarget = focusTarget,
                                gridFocusRequester = gridFocusRequester,
                                changePlaylistFocusRequester = changePlaylistFocus,
                                onRemote = { showRemote = true },
                                onRefresh = onRefresh,
                                onChangePlaylist = { confirmChangePlaylist = true },
                                onSettings = onSettings,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        item(key = "home-actions", contentType = "actions") {
            MainActionGrid(
                catalog = catalog,
                catalogLoading = catalogLoading,
                firstFocus = if (!preferGridFocus) firstFocus else null,
                focusTarget = focusTarget,
                gridFocusRequester = gridFocusRequester,
                onOpenSection = onOpenSection,
                onSearch = onSearch,
                onEpg = onEpg,
                onOpenLiveMatches = onOpenLiveMatches,
                modifier = Modifier.fillMaxWidth().height(MainGridHeight),
            )
            Spacer(Modifier.height(CardRowSpacing))
        }

        if (aiActive || resumeCards.isNotEmpty()) {
            item(key = HERO_ROW_KEY, contentType = "hero") {
                Column(Modifier.fillMaxWidth()) {
                    HomeHero(
                        resumeCards = resumeCards,
                        resumeRestoreKey = restoreTarget?.takeIf { it.homeRowKey == HomeRowKey.Resume }?.itemKey,
                        onResume = onResumePlayback,
                        aiActive = aiActive,
                        tonightAnswers = aiTonightAnswers,
                        onOpenAssistant = onOpenAssistant,
                        assistantFocus = assistantFocus,
                        showBrief = showAiBrief,
                        brief = aiBrief,
                        briefRestoreKey = restoreTarget?.takeIf { it.homeRowKey == HomeRowKey.AiBrief }?.itemKey,
                        onOpenBrief = { channel -> onOpenHomeEntry(channel, HomeRowKey.AiBrief, channel.key) },
                        onRefreshBrief = onRefreshAiBrief,
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        if (tvGuideLoaded) {
            item(key = HomeRowKey.TvProgramme, contentType = "programme-row") {
                Column(Modifier.fillMaxWidth()) {
                    TvGuideRow(
                        tvNow = tvProgrammeNow,
                        tvTonight = tvProgrammeTonight,
                        beinNow = beinSportsNow,
                        beinNext = beinSportsNext,
                        ukNow = ukGuideNow,
                        ukNext = ukGuideNext,
                        nowMillis = tvProgrammeNowEpochMillis,
                        ukTime = { ms -> java.time.Instant.ofEpochMilli(ms).atZone(UK_GUIDE_ZONE).toLocalTime() },
                        restoreItemKey = restoreTarget?.takeIf { it.homeRowKey == HomeRowKey.TvProgramme }?.itemKey,
                        onOpen = { channel, itemKey -> onOpenHomeEntry(channel, HomeRowKey.TvProgramme, itemKey) },
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        } else if (tvGuidePending) {
            item(key = HomeRowKey.TvProgramme, contentType = "skeleton") {
                Column(Modifier.fillMaxWidth()) {
                    SkeletonRow("Programme TV") { ProgrammeCardSkeleton() }
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        if (footballScoresEnabled || liveMatchCards.isNotEmpty() || liveMatchesPending) {
            item(key = HomeRowKey.LiveMatches, contentType = "live-matches") {
                FootRow(
                    cards = liveMatchCards,
                    liveMatchesPending = liveMatchesPending,
                    scoresEnabled = footballScoresEnabled,
                    restoreItemKey = restoreTarget?.takeIf { it.homeRowKey == HomeRowKey.LiveMatches }?.itemKey,
                    onOpen = { card -> card.channel?.let { onOpenHomeEntry(it, HomeRowKey.LiveMatches, card.key) } },
                    modifier = Modifier.padding(bottom = CardRowSpacing),
                )
            }
        }

        if (favoriteCards.isNotEmpty() || recentChannelCards.isNotEmpty()) {
            item(key = HomeRowKey.MyChannels, contentType = "card-row") {
                val restoreItem = restoreTarget?.takeIf { it.homeRowKey == HomeRowKey.MyChannels }?.itemKey
                var showFavorites by remember {
                    mutableStateOf(restoreItem?.tabPart()?.let { it == "fav" } ?: favoriteCards.isNotEmpty())
                }
                val onFavorites = (showFavorites && favoriteCards.isNotEmpty()) || recentChannelCards.isEmpty()
                val tab = if (onFavorites) "fav" else "recent"
                Column(Modifier.fillMaxWidth()) {
                    TabbedRowHeader(
                        title = "Mes chaînes",
                        tabs = buildList {
                            if (favoriteCards.isNotEmpty()) add("Favoris")
                            if (recentChannelCards.isNotEmpty()) add("Dernières regardées")
                        },
                        selected = if (onFavorites || favoriteCards.isEmpty()) 0 else 1,
                        onSelect = { showFavorites = favoriteCards.isNotEmpty() && it == 0 },
                    )
                    HomeCardRow(
                        title = "Mes chaînes",
                        showTitle = false,
                        entries = if (onFavorites) favoriteCards else recentChannelCards,
                        firstFocusRequester = null,
                        restoreItemKey = restoreItem?.takeIf { it.tabPart() == tab }?.keyPart(),
                        compact = true,
                        onEntryClick = { entry -> onOpenHomeEntry(entry, HomeRowKey.MyChannels, tabItemKey(tab, entry.key)) },
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        if (recommendationRows.isEmpty() && HomeBlock.Recommendations in pendingBlocks) {
            // Titres inconnus avant le calcul : barre fantôme à la place du titre.
            items(2, key = { "recommendations-skeleton-$it" }, contentType = { "skeleton" }) {
                Column(Modifier.fillMaxWidth()) {
                    SkeletonRow(title = null) { PosterCardSkeleton() }
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        items(recoGroups, key = { it.key }, contentType = { "recommendation-row" }) { group ->
            Column(Modifier.fillMaxWidth()) {
                RecoGroupRow(
                    group = group,
                    rows = group.rowsIn(recommendationRows),
                    aiReasons = if (aiActive) aiReasons else emptyMap(),
                    restoreRowKey = restoreTarget?.homeRowKey,
                    restoreItemKey = restoreTarget?.itemKey,
                    onOpen = { entry, rowKey -> onOpenHomeEntry(entry, rowKey, entry.key) },
                )
                Spacer(Modifier.height(CardRowSpacing))
            }
        }

    }
    if (exitArmed) {
        GlassSurface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
            shape = RoundedCornerShape(RadiusPill),
        ) {
            Text(
                "Appuyez de nouveau sur Retour pour quitter",
                color = Ink,
                fontSize = TypeBody,
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp),
            )
        }
    }
    if (showRemote) {
        BackHandler { showRemote = false }
        val closeFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                AiRemoteQrCard(aiRemote, Modifier.width(680.dp).height(220.dp))
                FocusableSurface(onClick = { showRemote = false }, wrapContent = true, radius = RadiusPill, modifier = Modifier.height(52.dp).focusRequester(closeFocus)) {
                    Text("Fermer", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp))
                }
            }
        }
    }
    if (confirmChangePlaylist) {
        // Même confirmation que dans Paramètres : la tuile est voisine de « Matchs du jour ».
        ConfirmDialog(
            title = "Changer de liste",
            message = "Vous allez quitter la liste actuelle et revenir au gestionnaire de playlists.",
            confirmLabel = "Continuer",
            onConfirm = {
                confirmChangePlaylist = false
                onChangePlaylist()
            },
            onDismiss = {
                confirmChangePlaylist = false
                // Annulé : le focus revient sur la tuile, au lieu de tomber sur une tuile voisine.
                runCatching { changePlaylistFocus.requestFocus() }
            },
        )
    }
    }
}

internal const val RESTORE_FOCUS_DELAY_MS = 60L

private const val EXIT_CONFIRM_WINDOW_MS = 2_500L

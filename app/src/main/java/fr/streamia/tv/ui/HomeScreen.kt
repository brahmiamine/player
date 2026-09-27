package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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

    val homeListState = rememberLazyListState()
    val restoreTarget = restoreContext?.takeIf { it.origin == ContentReturnOrigin.Home }
    val effectiveRestoreRowKey = restoreTarget?.homeRowKey
    val visibleRowKeys = remember(
        resumeCards,
        favoriteCards,
        liveMatchCards,
        recentChannelCards,
        tvProgrammeNow,
        tvProgrammeTonight,
        beinSportsNow,
        beinSportsNext,
        ukGuideNow,
        ukGuideNext,
        recommendationRows,
        footballScoresEnabled,
        liveMatchesPending,
        pendingBlocks,
    ) {
        buildList {
            if (resumeCards.isNotEmpty()) add(HomeRowKey.Resume)
            if (favoriteCards.isNotEmpty()) add(HomeRowKey.Favorites)
            if (recentChannelCards.isNotEmpty()) add(HomeRowKey.RecentChannels)
            // Même éléments que la LazyColumn, squelettes compris (sinon l'index de défilement se décale).
            if (footballScoresEnabled) add("football-scores")
            if (liveMatchCards.isNotEmpty() || liveMatchesPending) add(HomeRowKey.LiveMatches)
            if (tvProgrammeNow.isNotEmpty() || HomeBlock.TvProgrammeNow in pendingBlocks) add(HomeRowKey.TvProgrammeNow)
            if (tvProgrammeTonight.isNotEmpty() || HomeBlock.TvProgrammeTonight in pendingBlocks) add(HomeRowKey.TvProgrammeTonight)
            if (beinSportsNow.isNotEmpty() || HomeBlock.BeinSportsNow in pendingBlocks) add(HomeRowKey.BeinSportsNow)
            if (beinSportsNext.isNotEmpty() || HomeBlock.BeinSportsNext in pendingBlocks) add(HomeRowKey.BeinSportsNext)
            if (ukGuideNow.isNotEmpty() || HomeBlock.UkGuideNow in pendingBlocks) add(HomeRowKey.UkGuideNow)
            if (ukGuideNext.isNotEmpty() || HomeBlock.UkGuideNext in pendingBlocks) add(HomeRowKey.UkGuideNext)
            if (recommendationRows.isEmpty() && HomeBlock.Recommendations in pendingBlocks) repeat(2) { add("recommendations-skeleton") }
            recommendationRows.forEach { row -> add(HomeRowKey.recommendation(row.kind)) }
        }
    }
    LaunchedEffect(effectiveRestoreRowKey, visibleRowKeys) {
        val rowIndex = effectiveRestoreRowKey?.let(visibleRowKeys::indexOf) ?: -1
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
        homeListState.scrollToItem(gridListIndex)
        delay(RESTORE_FOCUS_DELAY_MS)
        runCatching { gridFocusRequester.requestFocus() }
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
                        Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            StreamiaLogo()
                        }
                        LocalClockText()
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
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
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }

        item(key = "home-actions", contentType = "actions") {
            MainActionGrid(
                catalog = catalog,
                catalogLoading = catalogLoading,
                busy = busy,
                firstFocus = if (!preferGridFocus) firstFocus else null,
                focusTarget = focusTarget,
                gridFocusRequester = gridFocusRequester,
                changePlaylistFocusRequester = changePlaylistFocus,
                onOpenSection = onOpenSection,
                onSearch = onSearch,
                onEpg = onEpg,
                onSettings = onSettings,
                onRefresh = onRefresh,
                onChangePlaylist = { confirmChangePlaylist = true },
                onOpenLiveMatches = onOpenLiveMatches,
                modifier = Modifier.fillMaxWidth().height(MainGridHeight),
            )
            Spacer(Modifier.height(CardRowSpacing))
        }

        if (resumeCards.isNotEmpty()) {
            item(key = HomeRowKey.Resume, contentType = "card-row") {
                Column(Modifier.fillMaxWidth()) {
                    HomeCardRow(
                        title = "Reprendre la lecture",
                        entries = resumeCards,
                        firstFocusRequester = null,
                        restoreItemKey = restoreTarget
                            ?.takeIf { it.homeRowKey == HomeRowKey.Resume }
                            ?.itemKey,
                        onEntryClick = onResumePlayback,
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        if (favoriteCards.isNotEmpty()) {
            item(key = HomeRowKey.Favorites, contentType = "card-row") {
                Column(Modifier.fillMaxWidth()) {
                    HomeCardRow(
                        title = "Favoris",
                        entries = favoriteCards,
                        firstFocusRequester = null,
                        restoreItemKey = restoreTarget
                            ?.takeIf { it.homeRowKey == HomeRowKey.Favorites }
                            ?.itemKey,
                        compact = true,
                        onEntryClick = { entry ->
                            onOpenHomeEntry(entry, HomeRowKey.Favorites, entry.key)
                        },
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        if (recentChannelCards.isNotEmpty()) {
            item(key = HomeRowKey.RecentChannels, contentType = "card-row") {
                Column(Modifier.fillMaxWidth()) {
                    HomeCardRow(
                        title = "Dernières chaînes regardées",
                        entries = recentChannelCards,
                        firstFocusRequester = null,
                        restoreItemKey = restoreTarget
                            ?.takeIf { it.homeRowKey == HomeRowKey.RecentChannels }
                            ?.itemKey,
                        compact = true,
                        onEntryClick = { entry ->
                            onOpenHomeEntry(entry, HomeRowKey.RecentChannels, entry.key)
                        },
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        if (footballScoresEnabled) {
            item(key = "football-scores") {
                FootballScoresRow(Modifier.padding(bottom = CardRowSpacing))
            }
        }

        if (liveMatchCards.isNotEmpty()) {
            item(key = HomeRowKey.LiveMatches, contentType = "live-matches") {
                LiveMatchesRow(
                    cards = liveMatchCards,
                    restoreItemKey = restoreTarget
                        ?.takeIf { it.homeRowKey == HomeRowKey.LiveMatches }
                        ?.itemKey,
                    onOpen = { card -> card.channel?.let { onOpenHomeEntry(it, HomeRowKey.LiveMatches, card.key) } },
                    modifier = Modifier.padding(bottom = CardRowSpacing),
                )
            }
        } else if (liveMatchesPending) {
            item(key = HomeRowKey.LiveMatches, contentType = "skeleton") {
                Column(Modifier.fillMaxWidth()) {
                    SkeletonRow("Matchs en direct") { LiveMatchCardSkeleton() }
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        if (tvProgrammeNow.isNotEmpty()) {
            item(key = HomeRowKey.TvProgrammeNow, contentType = "programme-row") {
                Column(Modifier.fillMaxWidth()) {
                    TvProgrammeNowRow(
                        items = tvProgrammeNow,
                        nowEpochMillis = tvProgrammeNowEpochMillis,
                        firstFocusRequester = null,
                        restoreItemKey = restoreTarget
                            ?.takeIf { it.homeRowKey == HomeRowKey.TvProgrammeNow }
                            ?.itemKey,
                        onOpenProgramme = { item ->
                            onOpenHomeEntry(
                                item.channel,
                                HomeRowKey.TvProgrammeNow,
                                item.fingerprint,
                            )
                        },
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        } else if (HomeBlock.TvProgrammeNow in pendingBlocks) {
            item(key = HomeRowKey.TvProgrammeNow, contentType = "skeleton") {
                Column(Modifier.fillMaxWidth()) {
                    SkeletonRow("Programme TV FR en direct") { ProgrammeCardSkeleton() }
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        if (tvProgrammeTonight.isNotEmpty()) {
            item(key = HomeRowKey.TvProgrammeTonight, contentType = "programme-row") {
                Column(Modifier.fillMaxWidth()) {
                    TvProgrammeTonightRow(
                        items = tvProgrammeTonight,
                        firstFocusRequester = null,
                        restoreItemKey = restoreTarget
                            ?.takeIf { it.homeRowKey == HomeRowKey.TvProgrammeTonight }
                            ?.itemKey,
                        onOpenProgramme = { item ->
                            onOpenHomeEntry(
                                item.channel,
                                HomeRowKey.TvProgrammeTonight,
                                item.fingerprint,
                            )
                        },
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        } else if (HomeBlock.TvProgrammeTonight in pendingBlocks) {
            item(key = HomeRowKey.TvProgrammeTonight, contentType = "skeleton") {
                Column(Modifier.fillMaxWidth()) {
                    SkeletonRow("Programme TV FR ce soir") { ProgrammeCardSkeleton() }
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        if (beinSportsNow.isNotEmpty()) {
            item(key = HomeRowKey.BeinSportsNow, contentType = "programme-row") {
                Column(Modifier.fillMaxWidth()) {
                    BeinSportsProgrammeRow(
                        title = "beIN Sports en direct",
                        items = beinSportsNow,
                        nowEpochMillis = beinNowEpochMillis,
                        showLive = true,
                        firstFocusRequester = null,
                        restoreItemKey = restoreTarget
                            ?.takeIf { it.homeRowKey == HomeRowKey.BeinSportsNow }
                            ?.itemKey,
                        onOpenProgramme = { item ->
                            onOpenHomeEntry(
                                item.channel,
                                HomeRowKey.BeinSportsNow,
                                item.fingerprint,
                            )
                        },
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        } else if (HomeBlock.BeinSportsNow in pendingBlocks) {
            item(key = HomeRowKey.BeinSportsNow, contentType = "skeleton") {
                Column(Modifier.fillMaxWidth()) {
                    SkeletonRow("beIN Sports en direct") { ProgrammeCardSkeleton() }
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        if (beinSportsNext.isNotEmpty()) {
            item(key = HomeRowKey.BeinSportsNext, contentType = "programme-row") {
                Column(Modifier.fillMaxWidth()) {
                    BeinSportsProgrammeRow(
                        title = "beIN Sports suivant",
                        items = beinSportsNext,
                        nowEpochMillis = beinNowEpochMillis,
                        showLive = false,
                        firstFocusRequester = null,
                        restoreItemKey = restoreTarget
                            ?.takeIf { it.homeRowKey == HomeRowKey.BeinSportsNext }
                            ?.itemKey,
                        onOpenProgramme = { item ->
                            onOpenHomeEntry(
                                item.channel,
                                HomeRowKey.BeinSportsNext,
                                item.fingerprint,
                            )
                        },
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        } else if (HomeBlock.BeinSportsNext in pendingBlocks) {
            item(key = HomeRowKey.BeinSportsNext, contentType = "skeleton") {
                Column(Modifier.fillMaxWidth()) {
                    SkeletonRow("beIN Sports suivant") { ProgrammeCardSkeleton() }
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        if (ukGuideNow.isNotEmpty()) {
            item(key = HomeRowKey.UkGuideNow, contentType = "programme-row") {
                Column(Modifier.fillMaxWidth()) {
                    UkGuideProgrammeRow(
                        title = "UK en direct",
                        items = ukGuideNow,
                        now = ukGuideNowTime,
                        showLive = true,
                        firstFocusRequester = null,
                        restoreItemKey = restoreTarget
                            ?.takeIf { it.homeRowKey == HomeRowKey.UkGuideNow }
                            ?.itemKey,
                        onOpenProgramme = { item ->
                            onOpenHomeEntry(
                                item.channel,
                                HomeRowKey.UkGuideNow,
                                item.fingerprint,
                            )
                        },
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        } else if (HomeBlock.UkGuideNow in pendingBlocks) {
            item(key = HomeRowKey.UkGuideNow, contentType = "skeleton") {
                Column(Modifier.fillMaxWidth()) {
                    SkeletonRow("UK en direct") { ProgrammeCardSkeleton() }
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        if (ukGuideNext.isNotEmpty()) {
            item(key = HomeRowKey.UkGuideNext, contentType = "programme-row") {
                Column(Modifier.fillMaxWidth()) {
                    UkGuideProgrammeRow(
                        title = "UK suivant",
                        items = ukGuideNext,
                        now = ukGuideNowTime,
                        showLive = false,
                        firstFocusRequester = null,
                        restoreItemKey = restoreTarget
                            ?.takeIf { it.homeRowKey == HomeRowKey.UkGuideNext }
                            ?.itemKey,
                        onOpenProgramme = { item ->
                            onOpenHomeEntry(
                                item.channel,
                                HomeRowKey.UkGuideNext,
                                item.fingerprint,
                            )
                        },
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        } else if (HomeBlock.UkGuideNext in pendingBlocks) {
            item(key = HomeRowKey.UkGuideNext, contentType = "skeleton") {
                Column(Modifier.fillMaxWidth()) {
                    SkeletonRow("UK suivant") { ProgrammeCardSkeleton() }
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

        itemsIndexed(recommendationRows, key = { _, row -> HomeRowKey.recommendation(row.kind) }, contentType = { _, _ -> "recommendation-row" }) { index, row ->
            Column(Modifier.fillMaxWidth()) {
                HomeRecommendationRow(
                    row = row,
                    firstFocusRequester = null,
                    restoreItemKey = restoreTarget
                        ?.takeIf { it.homeRowKey == HomeRowKey.recommendation(row.kind) }
                        ?.itemKey,
                    onOpenRecommendation = { entry ->
                        onOpenHomeEntry(entry, HomeRowKey.recommendation(row.kind), entry.key)
                    },
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

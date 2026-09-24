package fr.streamia.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.beinsports.ResolvedBeinProgrammeItem
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.data.isResumable
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.isVisualSeparator
import fr.streamia.tv.recommendation.RecommendationRow
import fr.streamia.tv.recommendation.RecommendedMedia
import fr.streamia.tv.tvprogramme.ResolvedTvProgrammeItem
import fr.streamia.tv.tvprogramme.ResolvedTvProgrammeNowItem
import fr.streamia.tv.ukguide.ResolvedUkProgrammeItem
import fr.streamia.tv.ui.theme.Danger
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RadiusPill
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Hauteur allouée à la grille d'actions principale : proche de la surface qu'occupait
 * l'ancien `fillMaxSize()` (écran logique 1280x720, moins l'en-tête et les marges), pour que
 * l'accueil garde le même confort quand aucune rangée « Reprendre »/« Favoris » n'est affichée. */
// Grille compacte : laisse la place aux rangées de contenu sous la navigation principale.
private val MainGridHeight = 300.dp
private val CardRowSpacing = 22.dp
private val UK_GUIDE_ZONE: ZoneId = ZoneId.of("Europe/London")
private const val TV_PROGRAMME_PROGRESS_REFRESH_MS = 30_000L
private const val TV_PROGRAMME_DATA_REFRESH_MS = 2 * 60_000L

@Composable
fun HomeScreen(
    catalog: Catalog,
    profileName: String?,
    offline: Boolean,
    busy: Boolean,
    library: UserLibrarySnapshot,
    parentalControlEnabled: Boolean = false,
    parentalUnlocked: Boolean = false,
    catalogLoading: Boolean = false,
    resumeRowEnabled: Boolean = true,
    favoritesRowEnabled: Boolean = true,
    recommendationRows: List<RecommendationRow> = emptyList(),
    tvProgrammeNow: List<ResolvedTvProgrammeNowItem> = emptyList(),
    tvProgrammeTonight: List<ResolvedTvProgrammeItem> = emptyList(),
    beinSportsNow: List<ResolvedBeinProgrammeItem> = emptyList(),
    beinSportsNext: List<ResolvedBeinProgrammeItem> = emptyList(),
    ukGuideNow: List<ResolvedUkProgrammeItem> = emptyList(),
    ukGuideNext: List<ResolvedUkProgrammeItem> = emptyList(),
    liveMatches: List<fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch> = emptyList(),
    restoreContext: ContentReturnContext? = null,
    focusTarget: HomeFocusTarget? = null,
    onFocusConsumed: () -> Unit = {},
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
) {
    val firstFocus = remember { FocusRequester() }
    val gridFocusRequester = remember { FocusRequester() }
    val restoringHome = restoreContext?.origin == ContentReturnOrigin.Home
    LaunchedEffect(restoringHome, focusTarget) {
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
    val recentChannelCards = remember(catalog, library.history, library.hiddenEntries, hiddenCategoryIdsByType) {
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
    var liveRowsNowEpochMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(hasLiveRows) {
        if (!hasLiveRows) return@LaunchedEffect
        while (true) {
            liveRowsNowEpochMillis = System.currentTimeMillis()
            delay(TV_PROGRAMME_PROGRESS_REFRESH_MS)
        }
    }
    val liveMatchCards = remember(liveMatches, liveRowsNowEpochMillis) {
        liveMatchCards(liveMatches, liveRowsNowEpochMillis / 1000)
    }
    val tvProgrammeNowEpochMillis = liveRowsNowEpochMillis
    val beinNowEpochMillis = liveRowsNowEpochMillis
    val ukGuideNowTime = remember(liveRowsNowEpochMillis) {
        java.time.Instant.ofEpochMilli(liveRowsNowEpochMillis).atZone(UK_GUIDE_ZONE).toLocalTime()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TV_PROGRAMME_DATA_REFRESH_MS)
            onRefreshTvProgrammeNow()
            onRefreshBeinSportsGuide()
            onRefreshUkGuide()
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
    ) {
        buildList {
            if (resumeCards.isNotEmpty()) add(HomeRowKey.Resume)
            if (favoriteCards.isNotEmpty()) add(HomeRowKey.Favorites)
            if (liveMatchCards.isNotEmpty()) add(HomeRowKey.LiveMatches)
            if (recentChannelCards.isNotEmpty()) add(HomeRowKey.RecentChannels)
            if (tvProgrammeNow.isNotEmpty()) add(HomeRowKey.TvProgrammeNow)
            if (tvProgrammeTonight.isNotEmpty()) add(HomeRowKey.TvProgrammeTonight)
            if (beinSportsNow.isNotEmpty()) add(HomeRowKey.BeinSportsNow)
            if (beinSportsNext.isNotEmpty()) add(HomeRowKey.BeinSportsNext)
            if (ukGuideNow.isNotEmpty()) add(HomeRowKey.UkGuideNow)
            if (ukGuideNext.isNotEmpty()) add(HomeRowKey.UkGuideNext)
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

    LazyColumn(
        state = homeListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 46.dp, vertical = 30.dp),
    ) {
        item {
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
                                profileName?.takeIf(String::isNotBlank)?.let {
                                    Text(it, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                }
                                val expiry = catalog.account?.expiresAtEpochSeconds?.let(::formatExpiry)
                                Text(
                                    buildString {
                                        append(if (offline) "Mode cache" else "Liste connectée")
                                        if (catalogLoading) append(" · chargement du catalogue…")
                                        if (expiry != null) append(" · expire le $expiry")
                                    },
                                    color = if (offline) FocusBlueBright else MutedInk,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }

        item {
            MainActionGrid(
                catalog = catalog,
                catalogLoading = catalogLoading,
                busy = busy,
                firstFocus = if (!preferGridFocus) firstFocus else null,
                focusTarget = focusTarget,
                gridFocusRequester = gridFocusRequester,
                onOpenSection = onOpenSection,
                onSearch = onSearch,
                onEpg = onEpg,
                onSettings = onSettings,
                onRefresh = onRefresh,
                onChangePlaylist = onChangePlaylist,
                onOpenLiveMatches = onOpenLiveMatches,
                modifier = Modifier.fillMaxWidth().height(MainGridHeight),
            )
            Spacer(Modifier.height(CardRowSpacing))
        }

        if (resumeCards.isNotEmpty()) {
            item {
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
            item {
                Column(Modifier.fillMaxWidth()) {
                    HomeCardRow(
                        title = "Favoris",
                        entries = favoriteCards,
                        firstFocusRequester = null,
                        restoreItemKey = restoreTarget
                            ?.takeIf { it.homeRowKey == HomeRowKey.Favorites }
                            ?.itemKey,
                        onEntryClick = { entry ->
                            onOpenHomeEntry(entry, HomeRowKey.Favorites, entry.key)
                        },
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        if (liveMatchCards.isNotEmpty()) {
            item {
                LiveMatchesRow(
                    cards = liveMatchCards,
                    onOpen = { card -> onOpenHomeEntry(card.channel, HomeRowKey.LiveMatches, card.key) },
                    modifier = Modifier.padding(bottom = CardRowSpacing),
                )
            }
        }

        if (recentChannelCards.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    HomeCardRow(
                        title = "Dernières chaînes regardées",
                        entries = recentChannelCards,
                        firstFocusRequester = null,
                        restoreItemKey = restoreTarget
                            ?.takeIf { it.homeRowKey == HomeRowKey.RecentChannels }
                            ?.itemKey,
                        onEntryClick = { entry ->
                            onOpenHomeEntry(entry, HomeRowKey.RecentChannels, entry.key)
                        },
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        item(key = "football-scores") {
            FootballScoresRow(Modifier.padding(bottom = CardRowSpacing))
        }

        if (tvProgrammeNow.isNotEmpty()) {
            item {
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
        }

        if (tvProgrammeTonight.isNotEmpty()) {
            item {
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
        }

        if (beinSportsNow.isNotEmpty()) {
            item {
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
        }

        if (beinSportsNext.isNotEmpty()) {
            item {
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
        }

        if (ukGuideNow.isNotEmpty()) {
            item {
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
        }

        if (ukGuideNext.isNotEmpty()) {
            item {
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
        }

        itemsIndexed(recommendationRows, key = { _, row -> row.kind }) { index, row ->
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
}

@Composable
private fun MainActionGrid(
    catalog: Catalog,
    catalogLoading: Boolean,
    busy: Boolean,
    firstFocus: FocusRequester?,
    focusTarget: HomeFocusTarget?,
    gridFocusRequester: FocusRequester,
    onOpenSection: (MediaType) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onRefresh: () -> Unit,
    onChangePlaylist: () -> Unit,
    onOpenLiveMatches: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val liveTileEnabled = !catalogLoading && catalog.count(MediaType.Live) > 0
        HomeTile(
            title = "TV en direct",
            subtitle = if (catalogLoading) "Chargement…" else "${catalog.count(MediaType.Live)} chaînes",
            glyph = StreamiaIconGlyph.Live,
            modifier = Modifier
                .then(if (firstFocus != null && liveTileEnabled) Modifier.focusRequester(firstFocus) else Modifier)
                .gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.Live)
                .width(300.dp)
                .fillMaxSize(),
            onClick = { onOpenSection(MediaType.Live) },
            enabled = liveTileEnabled,
            prominent = true,
        )

        Column(
            Modifier.width(400.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HomeTile(
                    title = "Films",
                    subtitle = if (catalogLoading) "Chargement…" else "${catalog.count(MediaType.Movie)} contenus",
                    glyph = StreamiaIconGlyph.Movie,
                    modifier = Modifier.weight(1f).fillMaxSize()
                        .gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.Movies),
                    onClick = { onOpenSection(MediaType.Movie) },
                    enabled = !catalogLoading && catalog.count(MediaType.Movie) > 0,
                )
                HomeTile(
                    title = "Séries",
                    subtitle = if (catalogLoading) "Chargement…" else "${catalog.count(MediaType.Series)} contenus",
                    glyph = StreamiaIconGlyph.Series,
                    modifier = Modifier.weight(1f).fillMaxSize()
                        .gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.Series),
                    onClick = { onOpenSection(MediaType.Series) },
                    enabled = !catalogLoading && catalog.count(MediaType.Series) > 0,
                )
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HomeTile(
                    title = "Recherche",
                    subtitle = "Tout le catalogue",
                    glyph = StreamiaIconGlyph.Search,
                    modifier = Modifier.weight(1f).fillMaxSize()
                        .gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.Search),
                    onClick = onSearch,
                    enabled = !catalogLoading,
                )
                HomeTile(
                    title = "Guide TV",
                    subtitle = "EPG",
                    glyph = StreamiaIconGlyph.Guide,
                    modifier = Modifier.weight(1f).fillMaxSize()
                        .gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.Guide),
                    onClick = onEpg,
                    enabled = !catalogLoading && catalog.count(MediaType.Live) > 0,
                )
            }
        }

        Column(
            Modifier.weight(1f).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HomeAction(
                StreamiaIconGlyph.Settings,
                "Paramètres",
                onSettings,
                Modifier.weight(1f)
                    .then(if (firstFocus != null && !liveTileEnabled) Modifier.focusRequester(firstFocus) else Modifier)
                    .gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.Settings),
            )
            HomeAction(
                StreamiaIconGlyph.Refresh,
                if (busy || catalogLoading) "Chargement…" else "Actualiser",
                onRefresh,
                Modifier.weight(1f).gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.Refresh),
                enabled = !busy && !catalogLoading,
            )
            HomeAction(
                StreamiaIconGlyph.Guide,
                "Matchs du jour",
                onOpenLiveMatches,
                Modifier.weight(1f).gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.LiveMatches),
            )
            HomeAction(
                StreamiaIconGlyph.Swap,
                "Changer de liste",
                onChangePlaylist,
                Modifier.weight(1f).gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.ChangePlaylist),
            )
        }
    }
}

private fun Modifier.gridFocus(requester: FocusRequester, matches: Boolean): Modifier =
    if (matches) focusRequester(requester) else this

@Composable
private fun HomeCardRow(
    title: String,
    entries: List<Pair<MediaEntry, Float?>>,
    firstFocusRequester: FocusRequester?,
    restoreItemKey: String?,
    onEntryClick: (MediaEntry) -> Unit,
) {
    val rowState = rememberLazyListState()
    val restoreFocus = remember { FocusRequester() }
    LaunchedEffect(restoreItemKey, entries) {
        val targetIndex = entries.indexOfFirst { (entry, _) -> entry.key == restoreItemKey }
        if (targetIndex >= 0) {
            rowState.scrollToItem(targetIndex)
            delay(RESTORE_FOCUS_DELAY_MS)
            runCatching { restoreFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SectionLabel(title, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        LazyRow(state = rowState, modifier = Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(entries, key = { _, (entry, _) -> entry.key }) { index, (entry, progress) ->
                val cardModifier = when {
                    entry.key == restoreItemKey -> Modifier.focusRequester(restoreFocus)
                    index == 0 && firstFocusRequester != null -> Modifier.focusRequester(firstFocusRequester)
                    else -> Modifier
                }
                HomeMediaCard(
                    entry = entry,
                    progress = progress,
                    onClick = { onEntryClick(entry) },
                    modifier = cardModifier,
                )
            }
        }
    }
}

/** Pastille distincte du libellé texte de la carte : signale le direct sans dépendre du texte. */
@Composable
internal fun LiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Danger)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text("LIVE", color = Night, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TvProgrammeNowRow(
    items: List<ResolvedTvProgrammeNowItem>,
    nowEpochMillis: Long,
    firstFocusRequester: FocusRequester?,
    restoreItemKey: String?,
    onOpenProgramme: (ResolvedTvProgrammeNowItem) -> Unit,
) = ProgrammeRow("Programme TV FR en direct", items, { it.fingerprint }, firstFocusRequester, restoreItemKey) { item, modifier ->
    ProgrammeCard(
        title = item.programme.title,
        timeLabel = item.programme.timeRangeLabel,
        imageUrl = item.programme.imageUrl,
        badge = ProgrammeBadge.Live,
        progress = item.programme.progressAt(nowEpochMillis),
        channel = item.channel,
        onClick = { onOpenProgramme(item) },
        modifier = modifier,
    )
}

@Composable
private fun TvProgrammeTonightRow(
    items: List<ResolvedTvProgrammeItem>,
    firstFocusRequester: FocusRequester?,
    restoreItemKey: String?,
    onOpenProgramme: (ResolvedTvProgrammeItem) -> Unit,
) = ProgrammeRow("Programme TV FR ce soir", items, { it.fingerprint }, firstFocusRequester, restoreItemKey) { item, modifier ->
    ProgrammeCard(
        title = item.programme.title,
        timeLabel = item.programme.time,
        imageUrl = item.programme.imageUrl,
        badge = ProgrammeBadge.None,
        channel = item.channel,
        onClick = { onOpenProgramme(item) },
        modifier = modifier,
    )
}

@Composable
private fun BeinSportsProgrammeRow(
    title: String,
    items: List<ResolvedBeinProgrammeItem>,
    nowEpochMillis: Long,
    showLive: Boolean,
    firstFocusRequester: FocusRequester?,
    restoreItemKey: String?,
    onOpenProgramme: (ResolvedBeinProgrammeItem) -> Unit,
) = ProgrammeRow(title, items, { it.fingerprint }, firstFocusRequester, restoreItemKey) { item, modifier ->
    ProgrammeCard(
        title = item.programme.title,
        timeLabel = item.programme.timeRangeLabel,
        imageUrl = item.programme.imageUrl,
        category = item.programme.category,
        badge = if (showLive) ProgrammeBadge.Live else ProgrammeBadge.Next,
        progress = if (showLive) item.programme.progressAt(nowEpochMillis) else null,
        channel = item.channel,
        onClick = { onOpenProgramme(item) },
        modifier = modifier,
    )
}

@Composable
private fun UkGuideProgrammeRow(
    title: String,
    items: List<ResolvedUkProgrammeItem>,
    now: LocalTime,
    showLive: Boolean,
    firstFocusRequester: FocusRequester?,
    restoreItemKey: String?,
    onOpenProgramme: (ResolvedUkProgrammeItem) -> Unit,
) = ProgrammeRow(title, items, { it.fingerprint }, firstFocusRequester, restoreItemKey) { item, modifier ->
    ProgrammeCard(
        title = item.programme.title,
        timeLabel = item.programme.timeRangeLabel,
        imageUrl = item.programme.imageUrl,
        badge = if (showLive) ProgrammeBadge.Live else ProgrammeBadge.Next,
        progress = if (showLive) item.programme.progressAt(now) else null,
        channel = item.channel,
        onClick = { onOpenProgramme(item) },
        modifier = modifier,
    )
}

/** Rangée commune aux guides (FR, beIN, UK) : titre, défilement horizontal, restauration du focus. */
@Composable
private fun <T> ProgrammeRow(
    title: String,
    items: List<T>,
    key: (T) -> String,
    firstFocusRequester: FocusRequester?,
    restoreItemKey: String?,
    card: @Composable (T, Modifier) -> Unit,
) {
    val rowState = rememberLazyListState()
    val restoreFocus = remember { FocusRequester() }
    LaunchedEffect(restoreItemKey, items) {
        val targetIndex = items.indexOfFirst { key(it) == restoreItemKey }
        if (targetIndex >= 0) {
            rowState.scrollToItem(targetIndex)
            delay(RESTORE_FOCUS_DELAY_MS)
            runCatching { restoreFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SectionLabel(title, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        LazyRow(state = rowState, modifier = Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
                val cardModifier = when {
                    key(item) == restoreItemKey -> Modifier.focusRequester(restoreFocus)
                    index == 0 && firstFocusRequester != null -> Modifier.focusRequester(firstFocusRequester)
                    else -> Modifier
                }
                card(item, cardModifier)
            }
        }
    }
}

private enum class ProgrammeBadge { None, Live, Next }

/** Carte commune aux programmes (en direct, ce soir, beIN, UK). */
@Composable
private fun ProgrammeCard(
    title: String,
    timeLabel: String,
    imageUrl: String?,
    badge: ProgrammeBadge,
    channel: MediaEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    category: String? = null,
    progress: Float? = null,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.width(220.dp).height(215.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(9.dp)) {
            Box(Modifier.fillMaxWidth().height(92.dp)) {
                if (!imageUrl.isNullOrBlank()) {
                    MediaArtwork(imageUrl, title, Modifier.fillMaxSize())
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MutedInk.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        StreamiaIcon(StreamiaIconGlyph.Live, tint = MutedInk.copy(alpha = 0.55f), size = 32.dp)
                    }
                }
                Text(
                    timeLabel,
                    color = Ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Night.copy(alpha = 0.9f))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                )
                when (badge) {
                    ProgrammeBadge.Live -> LiveBadge(Modifier.align(Alignment.TopEnd).padding(7.dp))
                    ProgrammeBadge.Next -> Text(
                        "À SUIVRE",
                        color = Night,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(FocusBlueBright)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                    ProgrammeBadge.None -> Unit
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                title,
                color = Ink,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            category?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, color = MutedInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (progress != null) {
                Spacer(Modifier.height(7.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MutedInk.copy(alpha = 0.24f)),
                ) {
                    Box(Modifier.fillMaxWidth(progress).height(5.dp).background(FocusBlueBright))
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel.iconUrl, channel.displayName, Modifier.width(44.dp).height(44.dp), imagePadding = 2)
                Spacer(Modifier.width(7.dp))
                Text(channel.displayName, color = MutedInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HomeRecommendationRow(
    row: RecommendationRow,
    firstFocusRequester: FocusRequester?,
    restoreItemKey: String?,
    onOpenRecommendation: (MediaEntry) -> Unit,
) {
    val rowState = rememberLazyListState()
    val restoreFocus = remember { FocusRequester() }
    LaunchedEffect(restoreItemKey, row.items) {
        val targetIndex = row.items.indexOfFirst { it.entry.key == restoreItemKey }
        if (targetIndex >= 0) {
            rowState.scrollToItem(targetIndex)
            delay(RESTORE_FOCUS_DELAY_MS)
            runCatching { restoreFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SectionLabel(row.title, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        LazyRow(state = rowState, modifier = Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(row.items, key = { _, recommended -> recommended.entry.key }) { index, recommended ->
                val cardModifier = when {
                    recommended.entry.key == restoreItemKey -> Modifier.focusRequester(restoreFocus)
                    index == 0 && firstFocusRequester != null -> Modifier.focusRequester(firstFocusRequester)
                    else -> Modifier
                }
                HomeRecommendationCard(
                    recommended = recommended,
                    onClick = { onOpenRecommendation(recommended.entry) },
                    modifier = cardModifier,
                )
            }
        }
    }
}

@Composable
private fun HomeRecommendationCard(
    recommended: RecommendedMedia,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = recommended.entry
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.width(HomeCardWidth).height(HomeCardHeight),
    ) {
        Column(Modifier.fillMaxSize().padding(9.dp)) {
            MediaArtwork(entry.iconUrl, entry.displayName, Modifier.fillMaxWidth().height(128.dp))
            Spacer(Modifier.height(7.dp))
            Text(
                entry.displayName,
                color = Ink,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                recommended.reason ?: entry.type.displayName,
                color = FocusBlueBright,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val RESTORE_FOCUS_DELAY_MS = 60L

private val HomeCardWidth = 172.dp
// 128dp d'illustration + jusqu'à 2 lignes de titre en 13sp/16sp de lineHeight + le label de type
// + une éventuelle barre de progression : 224dp laisse une marge confortable dans le pire cas
// (titre sur 2 lignes ET progression affichée) plutôt que de risquer un rognage en bas de carte.
private val HomeCardHeight = 224.dp

@Composable
private fun HomeMediaCard(
    entry: MediaEntry,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.width(HomeCardWidth).height(HomeCardHeight),
    ) {
        Column(Modifier.fillMaxSize().padding(9.dp)) {
            MediaArtwork(entry.iconUrl, entry.displayName, Modifier.fillMaxWidth().height(128.dp))
            Spacer(Modifier.height(7.dp))
            Text(
                entry.displayName,
                color = Ink,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(entry.type.displayName, color = FocusBlueBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (progress != null) {
                Spacer(Modifier.height(5.dp))
                HomeProgressBar(progress)
            }
        }
    }
}

@Composable
private fun HomeProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MutedInk.copy(alpha = 0.25f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(2.dp))
                .background(FocusBlueBright),
        )
    }
}

@Composable
private fun HomeTile(
    title: String,
    subtitle: String,
    glyph: StreamiaIconGlyph,
    modifier: Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
    prominent: Boolean = false,
) {
    FocusableSurface(onClick = onClick, enabled = enabled, accent = prominent, modifier = modifier) {
        Column(
            Modifier.fillMaxSize().padding(if (prominent) 20.dp else 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StreamiaIcon(
                    glyph,
                    size = if (prominent) 48.dp else 30.dp,
                    tint = if (prominent) Ink else FocusBlueBright,
                )
            }
            Spacer(Modifier.height(if (prominent) 14.dp else 6.dp))
            Text(
                title,
                color = Ink,
                fontSize = if (prominent) 26.sp else 18.sp,
                fontWeight = HeadingWeight,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                color = if (prominent) Ink.copy(alpha = 0.75f) else MutedInk,
                fontSize = if (prominent) 15.sp else 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HomeAction(
    glyph: StreamiaIconGlyph,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
) {
    FocusableSurface(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            StreamiaIcon(glyph, size = 24.dp)
            Spacer(Modifier.width(16.dp))
            Text(title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Horloge locale de l'en-tête (heures:minutes:secondes), rafraîchie chaque seconde. L'état vit
 * dans ce composable pour que seule cette zone se recompose, et non toute la liste d'accueil.
 */
@Composable
private fun LocalClockText(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            // Se recale sur la frontière de la seconde suivante pour éviter la dérive.
            delay(1_000L - System.currentTimeMillis() % 1_000L)
        }
    }
    Text(
        text = now.format(ClockFormatter),
        color = Ink,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun formatExpiry(epochSeconds: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(epochSeconds * 1000L))


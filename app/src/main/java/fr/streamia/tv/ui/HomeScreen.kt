package fr.streamia.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.data.isResumable
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.matches.MatchRow
import fr.streamia.tv.matches.MatchRowItem
import fr.streamia.tv.matches.MatchTemporalState
import fr.streamia.tv.recommendation.RecommendationRow
import fr.streamia.tv.recommendation.RecommendedMedia
import fr.streamia.tv.ui.theme.Danger
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Hauteur allouée à la grille d'actions principale : proche de la surface qu'occupait
 * l'ancien `fillMaxSize()` (écran logique 1280x720, moins l'en-tête et les marges), pour que
 * l'accueil garde le même confort quand aucune rangée « Reprendre »/« Favoris » n'est affichée. */
private val MainGridHeight = 560.dp
private val CardRowSpacing = 22.dp

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
    matchRow: MatchRow? = null,
    recommendationRows: List<RecommendationRow> = emptyList(),
    onOpenSection: (MediaType) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onRefresh: () -> Unit,
    onChangePlaylist: () -> Unit,
    onResumePlayback: (MediaEntry) -> Unit,
    onOpenFavorite: (MediaEntry) -> Unit,
    onOpenMatch: (MediaEntry) -> Unit,
    onOpenRecommendation: (MediaEntry) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

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
    val resumeCards = remember(catalog, library.history, library.hiddenEntries, library.watchedEntries, hiddenCategoryIdsByType) {
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
    val favoriteCards = remember(catalog, library.favoriteEntries, library.history, library.hiddenEntries, hiddenCategoryIdsByType) {
        val historyByKey = library.history.associateBy { it.entry.key }
        library.favoriteEntries.asSequence()
            .filterNot { it in library.hiddenEntries }
            .mapNotNull(catalog::entry)
            .filter { it.categoryId !in hiddenCategoryIdsByType[it.type].orEmpty() }
            .map { entry -> entry to historyByKey[entry.key]?.progress?.takeIf { it > 0.02f } }
            .toList()
    }

    // Le focus initial va toujours à la rangée la plus haute réellement affichée, pour ne jamais
    // demander le focus d'un composant pas encore composé (grille hors écran si les deux rangées
    // sont présentes). Sans historique ni favori (cas courant après import), le comportement est
    // strictement identique à l'ancien écran fixe.
    val focusOnResume = resumeCards.isNotEmpty()
    val focusOnFavorites = !focusOnResume && favoriteCards.isNotEmpty()
    val focusOnMatches = !focusOnResume && !focusOnFavorites && matchRow?.items?.isNotEmpty() == true
    val focusOnRecommendations = !focusOnResume && !focusOnFavorites && !focusOnMatches && recommendationRows.isNotEmpty()
    val focusOnGrid = !focusOnResume && !focusOnFavorites && !focusOnMatches && !focusOnRecommendations

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Night)
            .padding(horizontal = 46.dp, vertical = 30.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    StreamiaLogo()
                    Spacer(Modifier.weight(1f))
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
                Spacer(Modifier.height(28.dp))
            }
        }

        if (resumeCards.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    HomeCardRow(
                        title = "Reprendre la lecture",
                        entries = resumeCards,
                        firstFocusRequester = if (focusOnResume) firstFocus else null,
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
                        firstFocusRequester = if (focusOnFavorites) firstFocus else null,
                        onEntryClick = onOpenFavorite,
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        if (matchRow?.items?.isNotEmpty() == true) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    HomeMatchRow(
                        row = matchRow,
                        firstFocusRequester = if (focusOnMatches) firstFocus else null,
                        onOpenMatch = onOpenMatch,
                    )
                    Spacer(Modifier.height(CardRowSpacing))
                }
            }
        }

        itemsIndexed(recommendationRows, key = { _, row -> row.kind }) { index, row ->
            Column(Modifier.fillMaxWidth()) {
                HomeRecommendationRow(
                    row = row,
                    firstFocusRequester = if (focusOnRecommendations && index == 0) firstFocus else null,
                    onOpenRecommendation = onOpenRecommendation,
                )
                Spacer(Modifier.height(CardRowSpacing))
            }
        }

        item {
            MainActionGrid(
                catalog = catalog,
                catalogLoading = catalogLoading,
                busy = busy,
                firstFocus = if (focusOnGrid) firstFocus else null,
                onOpenSection = onOpenSection,
                onSearch = onSearch,
                onEpg = onEpg,
                onSettings = onSettings,
                onRefresh = onRefresh,
                onChangePlaylist = onChangePlaylist,
                modifier = Modifier.fillMaxWidth().height(MainGridHeight),
            )
        }
    }
}

@Composable
private fun MainActionGrid(
    catalog: Catalog,
    catalogLoading: Boolean,
    busy: Boolean,
    firstFocus: FocusRequester?,
    onOpenSection: (MediaType) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onRefresh: () -> Unit,
    onChangePlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val liveTileEnabled = !catalogLoading && catalog.count(MediaType.Live) > 0
        HomeTile(
            title = "TV en direct",
            subtitle = if (catalogLoading) "Chargement…" else "${catalog.count(MediaType.Live)} chaînes",
            glyph = StreamiaIconGlyph.Live,
            modifier = Modifier
                .then(if (firstFocus != null && liveTileEnabled) Modifier.focusRequester(firstFocus) else Modifier)
                .width(360.dp)
                .fillMaxSize(),
            onClick = { onOpenSection(MediaType.Live) },
            enabled = liveTileEnabled,
            prominent = true,
        )

        Column(
            Modifier.width(360.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                HomeTile(
                    title = "Films",
                    subtitle = if (catalogLoading) "Chargement…" else "${catalog.count(MediaType.Movie)} contenus",
                    glyph = StreamiaIconGlyph.Movie,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    onClick = { onOpenSection(MediaType.Movie) },
                    enabled = !catalogLoading && catalog.count(MediaType.Movie) > 0,
                )
                HomeTile(
                    title = "Séries",
                    subtitle = if (catalogLoading) "Chargement…" else "${catalog.count(MediaType.Series)} contenus",
                    glyph = StreamiaIconGlyph.Series,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    onClick = { onOpenSection(MediaType.Series) },
                    enabled = !catalogLoading && catalog.count(MediaType.Series) > 0,
                )
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                HomeTile(
                    title = "Recherche",
                    subtitle = "Tout le catalogue",
                    glyph = StreamiaIconGlyph.Search,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    onClick = onSearch,
                    enabled = !catalogLoading,
                )
                HomeTile(
                    title = "Guide TV",
                    subtitle = "EPG",
                    glyph = StreamiaIconGlyph.Guide,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    onClick = onEpg,
                    enabled = !catalogLoading && catalog.count(MediaType.Live) > 0,
                )
            }
        }

        Column(
            Modifier.weight(1f).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            HomeAction(
                StreamiaIconGlyph.Settings,
                "Paramètres",
                onSettings,
                Modifier.weight(1f).then(if (firstFocus != null && !liveTileEnabled) Modifier.focusRequester(firstFocus) else Modifier),
            )
            HomeAction(StreamiaIconGlyph.Refresh, if (busy || catalogLoading) "Chargement…" else "Actualiser", onRefresh, Modifier.weight(1f), enabled = !busy && !catalogLoading)
            HomeAction(StreamiaIconGlyph.Swap, "Changer de liste", onChangePlaylist, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HomeCardRow(
    title: String,
    entries: List<Pair<MediaEntry, Float?>>,
    firstFocusRequester: FocusRequester?,
    onEntryClick: (MediaEntry) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(title, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(entries, key = { _, (entry, _) -> entry.key }) { index, (entry, progress) ->
                HomeMediaCard(
                    entry = entry,
                    progress = progress,
                    onClick = { onEntryClick(entry) },
                    modifier = if (index == 0 && firstFocusRequester != null) {
                        Modifier.focusRequester(firstFocusRequester)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeMatchRow(
    row: MatchRow,
    firstFocusRequester: FocusRequester?,
    onOpenMatch: (MediaEntry) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(row.title, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(row.items, key = { _, item -> item.event.fingerprint }) { index, item ->
                HomeMatchCard(
                    item = item,
                    onClick = { onOpenMatch(item.event.channel) },
                    modifier = if (index == 0 && firstFocusRequester != null) {
                        Modifier.focusRequester(firstFocusRequester)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeMatchCard(
    item: MatchRowItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val event = item.event
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.width(280.dp).height(136.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 13.dp)) {
                Text(
                    matchTimingLabel(item),
                    color = FocusBlueBright,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "${event.participantA} / ${event.participantB}",
                    color = Ink,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    event.competition ?: event.channel.displayName,
                    color = MutedInk,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.temporalState == MatchTemporalState.Live) {
                LiveBadge(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp))
            }
        }
    }
}

/** Pastille distincte du libellé texte de la carte : signale le direct sans dépendre du texte. */
@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Danger)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text("LIVE", color = Night, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomeRecommendationRow(
    row: RecommendationRow,
    firstFocusRequester: FocusRequester?,
    onOpenRecommendation: (MediaEntry) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(row.title, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(row.items, key = { _, recommended -> recommended.entry.key }) { index, recommended ->
                HomeRecommendationCard(
                    recommended = recommended,
                    onClick = { onOpenRecommendation(recommended.entry) },
                    modifier = if (index == 0 && firstFocusRequester != null) {
                        Modifier.focusRequester(firstFocusRequester)
                    } else {
                        Modifier
                    },
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
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun matchTimingLabel(item: MatchRowItem): String {
    val dateTime = Instant.ofEpochSecond(item.event.startEpochSeconds)
        .atZone(ZoneId.systemDefault())
    val time = dateTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
    return when (item.temporalState) {
        MatchTemporalState.Live -> "Match"
        MatchTemporalState.Today -> "Aujourd'hui · $time"
        MatchTemporalState.Tomorrow -> "Demain · $time"
        MatchTemporalState.ThisWeek -> {
            val day = dateTime.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
                .replaceFirstChar { it.uppercaseChar() }
            "$day · $time"
        }
    }
}

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
            Text(entry.type.displayName, color = FocusBlueBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
    FocusableSurface(onClick = onClick, enabled = enabled, modifier = modifier) {
        Column(
            Modifier.fillMaxSize().padding(if (prominent) 34.dp else 22.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StreamiaIcon(glyph, size = if (prominent) 64.dp else 42.dp)
            Spacer(Modifier.height(if (prominent) 24.dp else 12.dp))
            Text(title, color = Ink, fontSize = if (prominent) 29.sp else 20.sp, fontWeight = HeadingWeight)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color = MutedInk, fontSize = if (prominent) 15.sp else 12.sp)
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
        Row(Modifier.fillMaxSize().padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            StreamiaIcon(glyph, size = 26.dp)
            Spacer(Modifier.width(18.dp))
            Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun formatExpiry(epochSeconds: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(epochSeconds * 1000L))

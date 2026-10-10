package fr.streamia.tv.ui.mobile

import fr.streamia.tv.ui.visibleOnHome
import fr.streamia.tv.ui.BriefUiState
import fr.streamia.tv.ui.AiLoadingIndicator
import fr.streamia.tv.ui.AiButtonLabel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.tv.material3.Text
import fr.streamia.tv.R
import fr.streamia.tv.data.CurrentWeather
import fr.streamia.tv.data.HomePlace
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.data.isResumable
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.data.weatherEmoji
import fr.streamia.tv.beinsports.ResolvedBeinProgrammeItem
import fr.streamia.tv.recommendation.RecommendationRow
import fr.streamia.tv.tvprogramme.ResolvedTvProgrammeItem
import fr.streamia.tv.tvprogramme.ResolvedTvProgrammeNowItem
import fr.streamia.tv.ukguide.ResolvedUkProgrammeItem
import fr.streamia.tv.ui.BeinSportsProgrammeRow
import fr.streamia.tv.ui.FootballScoresRow
import fr.streamia.tv.ui.HomeRecommendationRow
import fr.streamia.tv.ui.TV_PROGRAMME_DATA_REFRESH_MS
import fr.streamia.tv.ui.TvProgrammeNowRow
import fr.streamia.tv.ui.TvProgrammeTonightRow
import fr.streamia.tv.ui.UK_GUIDE_ZONE
import fr.streamia.tv.ui.UkGuideProgrammeRow
import fr.streamia.tv.ui.ChannelLogo
import fr.streamia.tv.ui.HomeRowKey
import fr.streamia.tv.ui.LiveMatchCard
import fr.streamia.tv.ui.MediaArtwork
import fr.streamia.tv.ui.StreamiaIcon
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.liveMatchCards
import fr.streamia.tv.ui.liveOnSatMatchKey
import fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.Danger
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusCard
import fr.streamia.tv.ui.theme.RadiusTile
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Accueil mobile : en-tête, heure et météo, accès « TV en direct », tuiles, actions rapides, puis
 * rangées défilantes au doigt (reprise, favoris, chaînes récentes, matchs en direct). Tirer vers le
 * bas actualise la liste. Les blocs suivent les mêmes réglages que l'accueil TV.
 */
@Composable
fun MobileHomeScreen(
    catalog: Catalog,
    library: UserLibrarySnapshot,
    weatherPlace: HomePlace?,
    weather: CurrentWeather?,
    liveMatches: List<ResolvedLiveOnSatMatch>,
    liveMatchesResolving: Boolean,
    offline: Boolean,
    busy: Boolean,
    parentalControlEnabled: Boolean,
    parentalUnlocked: Boolean,
    resumeRowEnabled: Boolean,
    favoritesRowEnabled: Boolean,
    recentChannelsEnabled: Boolean,
    liveMatchesEnabled: Boolean,
    footballScoresEnabled: Boolean,
    recommendationRows: List<RecommendationRow>,
    /** Assistant IA : résumé « Quoi de neuf maintenant ? » (vide ou absent quand l'assistant est coupé). */
    aiBrief: BriefUiState = BriefUiState(),
    onRefreshAiBrief: () -> Unit = {},
    tvProgrammeNow: List<ResolvedTvProgrammeNowItem>,
    tvProgrammeTonight: List<ResolvedTvProgrammeItem>,
    beinSportsNow: List<ResolvedBeinProgrammeItem>,
    beinSportsNext: List<ResolvedBeinProgrammeItem>,
    ukGuideNow: List<ResolvedUkProgrammeItem>,
    ukGuideNext: List<ResolvedUkProgrammeItem>,
    onOpenSection: (MediaType) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onRefresh: () -> Unit,
    onOpenLiveMatches: () -> Unit,
    onChangePlaylist: () -> Unit,
    onResumePlayback: (MediaEntry) -> Unit,
    onOpenHomeEntry: (MediaEntry, String, String) -> Unit,
    onOpenLiveMatchChannel: (MediaEntry, String) -> Unit,
    onRefreshWeather: () -> Unit,
    onRefreshLiveMatches: () -> Unit,
    onRefreshTvProgrammeNow: () -> Unit,
    onRefreshBeinSportsGuide: () -> Unit,
    onRefreshUkGuide: () -> Unit,
) {
    LaunchedEffect(Unit) {
        onRefreshWeather()
        onRefreshLiveMatches()
        // Guides FR, beIN et UK : rechargés toutes les 2 minutes tant que l'accueil est affiché.
        while (true) {
            delay(TV_PROGRAMME_DATA_REFRESH_MS)
            onRefreshTvProgrammeNow()
            onRefreshBeinSportsGuide()
            onRefreshUkGuide()
            onRefreshLiveMatches()
        }
    }

    // Catégories masquées, ou verrouillées et pas encore déverrouillées : exclues de l'accueil.
    val excluded = remember(library.hiddenCategories, library.lockedCategories, parentalControlEnabled, parentalUnlocked) {
        if (!parentalControlEnabled || parentalUnlocked) library.hiddenCategories else library.hiddenCategories + library.lockedCategories
    }
    val excludedIdsByType = remember(catalog, excluded) {
        catalog.categories.filter { it.key in excluded }.groupBy { it.type }.mapValues { (_, list) -> list.mapTo(mutableSetOf()) { it.id } }
    }

    val resume = remember(catalog, library.history, library.hiddenEntries, library.watchedEntries, excludedIdsByType, resumeRowEnabled) {
        if (!resumeRowEnabled) emptyList() else library.history.asSequence()
            .filter {
                it.entry.type != MediaType.Live && it.isResumable() &&
                    it.entry.key !in library.hiddenEntries && it.entry.key !in library.watchedEntries &&
                    it.entry.categoryId !in excludedIdsByType[it.entry.type].orEmpty()
            }
            .map { item ->
                val entry = if (item.entry.type == MediaType.Movie) catalog.entry(item.entry.key) ?: item.entry else item.entry
                entry to item.progress
            }
            .toList()
    }
    val favorites = remember(catalog, library.favoriteEntries, library.history, library.hiddenEntries, excludedIdsByType, favoritesRowEnabled) {
        if (!favoritesRowEnabled) emptyList() else {
            val progress = library.history.associateBy { it.entry.key }
            library.favoriteEntries.asSequence()
                .filterNot { it in library.hiddenEntries }
                .mapNotNull(catalog::entry)
                .filter { it.categoryId !in excludedIdsByType[it.type].orEmpty() }
                .map { entry -> entry to progress[entry.key]?.progress?.takeIf { it > 0.02f } }
                .toList()
        }
    }
    val recentChannels = remember(catalog, library.history, library.hiddenEntries, excludedIdsByType, recentChannelsEnabled) {
        if (!recentChannelsEnabled) emptyList() else library.history.asSequence()
            .filter {
                it.entry.type == MediaType.Live && it.entry.key !in library.hiddenEntries &&
                    it.entry.categoryId !in excludedIdsByType[MediaType.Live].orEmpty()
            }
            .map { (catalog.entry(it.entry.key) ?: it.entry) to null as Float? }
            .take(10)
            .toList()
    }
    val nowSeconds by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis() / 1000
        }
    }
    val matchCards = remember(liveMatches, liveMatchesResolving, nowSeconds, liveMatchesEnabled) {
        if (!liveMatchesEnabled) emptyList() else liveMatchCards(liveMatches, nowSeconds, liveMatchesResolving)
    }

    MobilePullToRefresh(refreshing = busy, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            item { HomeTopBar(offline, onSettings) }
            item { HomeInfoCard(weatherPlace, weather) }
            item {
                MobileAccentCard(
                    Modifier.fillMaxWidth().padding(start = MobileGutter, end = MobileGutter, top = 12.dp).height(92.dp),
                    onClick = { onOpenSection(MediaType.Live) },
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        StreamiaIcon(StreamiaIconGlyph.Live, tint = Ink, size = 38.dp)
                        Column(Modifier.weight(1f)) {
                            Text("TV en direct", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                            Text("${catalog.count(MediaType.Live)} chaînes", color = Ink.copy(alpha = 0.85f), fontSize = 14.sp)
                        }
                        StreamiaIcon(StreamiaIconGlyph.ArrowForward, tint = Ink, size = 22.dp)
                    }
                }
            }
            item {
                Column(Modifier.padding(start = MobileGutter, end = MobileGutter, top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HomeTile("Films", "${catalog.count(MediaType.Movie)} contenus", StreamiaIconGlyph.Movie, Modifier.weight(1f)) { onOpenSection(MediaType.Movie) }
                        HomeTile("Séries", "${catalog.count(MediaType.Series)} contenus", StreamiaIconGlyph.Series, Modifier.weight(1f)) { onOpenSection(MediaType.Series) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HomeTile("Recherche", "Tout le catalogue", StreamiaIconGlyph.Search, Modifier.weight(1f), onSearch)
                        HomeTile("Guide TV", "EPG", StreamiaIconGlyph.Guide, Modifier.weight(1f), onEpg)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickAction("Actualiser", StreamiaIconGlyph.Refresh, Modifier.weight(1f), onRefresh)
                        QuickAction("Matchs du jour", StreamiaIconGlyph.Trophy, Modifier.weight(1f), onOpenLiveMatches)
                        QuickAction("Changer de liste", StreamiaIconGlyph.Swap, Modifier.weight(1f), onChangePlaylist)
                    }
                }
            }
            if (aiBrief.visibleOnHome()) item(key = HomeRowKey.AiBrief) {
                Column(Modifier.padding(start = MobileGutter, end = MobileGutter, top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MobileSectionLabel("✦ Quoi de neuf maintenant ?")
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !aiBrief.loading, onClick = onRefreshAiBrief).padding(horizontal = 10.dp, vertical = 4.dp)) {
                            AiButtonLabel("Actualiser", aiBrief.loading)
                        }
                    }
                    aiBrief.headline?.let { Text(it, color = MutedInk, fontSize = 13.sp, maxLines = 2) }
                    aiBrief.error?.takeIf { !aiBrief.loading }?.let { Text(it, color = MutedInk, fontSize = 13.sp) }
                    AiLoadingIndicator("L'assistant résume ce qui passe en ce moment…", aiBrief.loading)
                    aiBrief.items.forEach { item ->
                        val channel = item.channel ?: return@forEach
                        MobileCard(Modifier.fillMaxWidth(), onClick = { onOpenHomeEntry(channel, HomeRowKey.AiBrief, channel.key) }) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ChannelLogo(channel.iconUrl, channel.displayName, Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)), imagePadding = 4)
                                Column(Modifier.weight(1f)) {
                                    Text(item.text, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text(channel.displayName, color = AccentPinkText, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            if (resume.isNotEmpty()) item {
                PosterRow("Reprendre la lecture", resume.size.toString(), resume, onClick = { onResumePlayback(it) })
            }
            if (favorites.isNotEmpty()) item {
                PosterRow(
                    "Favoris", favorites.size.toString(), favorites,
                    onClick = { onOpenHomeEntry(it, HomeRowKey.Favorites, it.key) },
                )
            }
            if (recentChannels.isNotEmpty()) item {
                PosterRow(
                    "Dernières chaînes regardées", "Appui long : options", recentChannels,
                    wide = true,
                    onClick = { onOpenHomeEntry(it, HomeRowKey.RecentChannels, it.key) },
                )
            }
            if (matchCards.isNotEmpty()) item {
                MatchRow(matchCards, onOpenLiveMatches, onOpenLiveMatchChannel)
            }
            if (footballScoresEnabled) item(key = "football-scores") {
                FootballScoresRow(Modifier.padding(top = 26.dp, start = MobileGutter - 12.dp))
            }
            val openProgramme = { channel: MediaEntry, rowKey: String, itemKey: String -> onOpenHomeEntry(channel, rowKey, itemKey) }
            val nowMillis: () -> Long = { nowSeconds * 1000 }
            if (tvProgrammeNow.isNotEmpty()) item(key = HomeRowKey.TvProgrammeNow) {
                GuideBlock { TvProgrammeNowRow(tvProgrammeNow, nowMillis, null, null) { openProgramme(it.channel, HomeRowKey.TvProgrammeNow, it.fingerprint) } }
            }
            if (tvProgrammeTonight.isNotEmpty()) item(key = HomeRowKey.TvProgrammeTonight) {
                GuideBlock { TvProgrammeTonightRow(tvProgrammeTonight, null, null) { openProgramme(it.channel, HomeRowKey.TvProgrammeTonight, it.fingerprint) } }
            }
            if (beinSportsNow.isNotEmpty()) item(key = HomeRowKey.BeinSportsNow) {
                GuideBlock { BeinSportsProgrammeRow("beIN Sports en direct", beinSportsNow, nowMillis, true, null, null) { openProgramme(it.channel, HomeRowKey.BeinSportsNow, it.fingerprint) } }
            }
            if (beinSportsNext.isNotEmpty()) item(key = HomeRowKey.BeinSportsNext) {
                GuideBlock { BeinSportsProgrammeRow("beIN Sports suivant", beinSportsNext, nowMillis, false, null, null) { openProgramme(it.channel, HomeRowKey.BeinSportsNext, it.fingerprint) } }
            }
            val ukNow: () -> LocalTime = { java.time.Instant.ofEpochSecond(nowSeconds).atZone(UK_GUIDE_ZONE).toLocalTime() }
            if (ukGuideNow.isNotEmpty()) item(key = HomeRowKey.UkGuideNow) {
                GuideBlock { UkGuideProgrammeRow("UK en direct", ukGuideNow, ukNow, true, null, null) { openProgramme(it.channel, HomeRowKey.UkGuideNow, it.fingerprint) } }
            }
            if (ukGuideNext.isNotEmpty()) item(key = HomeRowKey.UkGuideNext) {
                GuideBlock { UkGuideProgrammeRow("UK suivant", ukGuideNext, ukNow, false, null, null) { openProgramme(it.channel, HomeRowKey.UkGuideNext, it.fingerprint) } }
            }
            items(recommendationRows, key = { HomeRowKey.recommendation(it.kind) }) { row ->
                GuideBlock {
                    HomeRecommendationRow(row, null, null) { entry -> onOpenHomeEntry(entry, HomeRowKey.recommendation(row.kind), entry.key) }
                }
            }
        }
    }
}

/** Rangée reprise telle quelle de l'accueil TV, calée sur la marge mobile. */
@Composable
private fun GuideBlock(content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 26.dp, start = MobileGutter - 12.dp).fillMaxWidth()) { content() }
}

@Composable
private fun HomeTopBar(offline: Boolean, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = MobileGutter, end = MobileGutter, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.streamia_logo_mark),
            contentDescription = "Logo Streamia",
            modifier = Modifier.size(38.dp),
            contentScale = ContentScale.Fit,
        )
        Text("Streamia", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        if (offline) OfflineTag()
        MobileIconButton(StreamiaIconGlyph.Settings, onClick = onSettings)
    }
}

@Composable
private fun HomeInfoCard(place: HomePlace?, weather: CurrentWeather?) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            delay(15_000L)
            value = LocalDateTime.now()
        }
    }
    MobileCard(Modifier.fillMaxWidth().padding(start = MobileGutter, end = MobileGutter, top = 12.dp), radius = RadiusCard) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(now.format(TimeFormatter), color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text(now.format(DateFormatter), color = MutedInk, fontSize = 12.sp)
            }
            if (place != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        buildString {
                            weather?.let { append(weatherEmoji(it.weatherCode)).append(' ').append(it.temperatureC).append('°') }
                        }.ifBlank { "—" },
                        color = Ink,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(place.name.substringBefore(','), color = MutedInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun HomeTile(title: String, subtitle: String, glyph: StreamiaIconGlyph, modifier: Modifier, onClick: () -> Unit) {
    MobileCard(modifier.height(92.dp), onClick = onClick) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            StreamiaIcon(glyph, tint = AccentPink, size = 26.dp)
            Column {
                Text(title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, color = MutedInk, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun QuickAction(label: String, glyph: StreamiaIconGlyph, modifier: Modifier, onClick: () -> Unit) {
    MobileCard(modifier.height(68.dp), onClick = onClick) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            StreamiaIcon(glyph, tint = AccentPink, size = 19.dp)
            Text(label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Rangée horizontale d'affiches (14 dp d'écart) ou de vignettes de chaînes ([wide]). */
@Composable
private fun PosterRow(
    title: String,
    hint: String,
    items: List<Pair<MediaEntry, Float?>>,
    wide: Boolean = false,
    onClick: (MediaEntry) -> Unit,
) {
    Column(Modifier.padding(top = 26.dp)) {
        MobileSectionLabel(title, hint = hint)
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = MobileGutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.first.key }) { (entry, progress) ->
                val width = if (wide) 156.dp else 104.dp
                MobileCard(Modifier.width(width), onClick = { onClick(entry) }) {
                    Column {
                        Box(Modifier.fillMaxWidth().then(if (wide) Modifier.height(88.dp) else Modifier.aspectRatio(2f / 3f))) {
                            if (wide) {
                                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                                    ChannelLogo(entry.iconUrl, entry.displayName, Modifier.fillMaxSize(), imagePadding = 12)
                                }
                            } else {
                                MediaArtwork(entry.iconUrl, entry.displayName, Modifier.fillMaxSize())
                            }
                            if (progress != null && progress > 0f) {
                                ProgressBar(progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth().height(4.dp))
                            }
                        }
                        Text(
                            entry.displayName,
                            color = Ink,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchRow(cards: List<LiveMatchCard>, onSeeAll: () -> Unit, onOpenChannel: (MediaEntry, String) -> Unit) {
    Column(Modifier.padding(top = 26.dp)) {
        MobileSectionLabel("Matchs en direct", hint = "Voir tout")
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = MobileGutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cards, key = { it.key }) { card ->
                val match = card.match.match
                val channel = card.channel
                MobileCard(
                    Modifier.width(260.dp).height(150.dp),
                    onClick = { if (channel != null) onOpenChannel(channel, liveOnSatMatchKey(card.match)) else onSeeAll() },
                ) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(match.competition, color = MutedInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Box(Modifier.clip(RoundedCornerShape(50)).background(AccentPink).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Text("EN DIRECT", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(match.participantA, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(match.participantB, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        if (channel != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChannelLogo(channel.iconUrl, channel.displayName, Modifier.size(32.dp), imagePadding = 2)
                                Text(channel.displayName, color = MutedInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)
private val DateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)

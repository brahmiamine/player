package fr.streamia.tv.ui.mobile

import fr.streamia.tv.ui.visibleOnHome
import fr.streamia.tv.ui.BriefUiState
import fr.streamia.tv.ui.AiLoadingIndicator
import fr.streamia.tv.ui.AiSparkle
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import fr.streamia.tv.ui.theme.RadiusPanel
import fr.streamia.tv.ui.theme.RadiusPill
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
import fr.streamia.tv.ui.updatedLabel
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
    /** Assistant actif : cartes « Ce soir ? » et « Télécommande téléphone ». */
    aiActive: Boolean = false,
    onStartTonight: () -> Unit = {},
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
                Column(Modifier.padding(start = MobileGutter, end = MobileGutter, top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MobileAccentCard(Modifier.fillMaxWidth().height(76.dp), onClick = { onOpenSection(MediaType.Live) }) {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("TV en direct", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                                Text("${catalog.count(MediaType.Live).grouped()} chaînes", color = Ink.copy(alpha = 0.9f), fontSize = 13.sp)
                            }
                            Text("▶", color = Ink, fontSize = 24.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HomeTile("Films", Modifier.weight(1f)) { onOpenSection(MediaType.Movie) }
                        HomeTile("Séries", Modifier.weight(1f)) { onOpenSection(MediaType.Series) }
                    }
                }
            }
            if (aiActive) item(key = "ai-assistant") {
                Column(Modifier.padding(top = 18.dp)) {
                    MobileSectionLabel("✦ Assistant IA")
                    Column(Modifier.padding(start = MobileGutter, end = MobileGutter, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TonightCard(onStartTonight)
                        if (aiBrief.visibleOnHome()) {
                            BriefCard(aiBrief, onRefreshAiBrief) { channel -> onOpenHomeEntry(channel, HomeRowKey.AiBrief, channel.key) }
                        }
                        RemoteCard()
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
private fun HomeTile(title: String, modifier: Modifier, onClick: () -> Unit) {
    MobileCard(modifier.height(60.dp), onClick = onClick) {
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/** « 55 940 » : séparateur de milliers à la française. */
private fun Int.grouped(): String = java.text.NumberFormat.getIntegerInstance(Locale.FRENCH).format(this)

/** Carte principale de l'assistant : « ✦ Ce soir ? » et « Commencer ». */
@Composable
private fun TonightCard(onStart: () -> Unit) {
    val shape = RoundedCornerShape(RadiusCard)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .background(Brush.linearGradient(listOf(AccentPink.copy(alpha = 0.34f), Color(0x47BF5AF2))))
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
            .clickable(role = Role.Button, onClick = onStart)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("✦ Ce soir ?", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text("Trois questions, cinq propositions faites pour vous.", color = Ink.copy(alpha = 0.88f), fontSize = 14.sp, lineHeight = 19.sp)
        Box(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(RadiusPill)).background(AccentPink).clickable(role = Role.Button, onClick = onStart),
            contentAlignment = Alignment.Center,
        ) {
            Text("Commencer", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/**
 * « ✦ Quoi de neuf maintenant ? » : en-tête (heure de mise à jour, ↻), résumé en carte, puis les chaînes en cours en
 * tuiles qui défilent côte à côte (toucher une tuile l'ouvre).
 */
@Composable
private fun BriefCard(brief: BriefUiState, onRefresh: () -> Unit, onOpen: (MediaEntry) -> Unit) {
    val items = brief.items.filter { it.channel != null }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    "✦ Quoi de neuf maintenant ?".uppercase(java.util.Locale.FRENCH), color = MutedInk, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                brief.updatedLabel()?.let { Text(it, color = MutedInk, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)) }
            }
            Box(
                Modifier.size(MobileMinTouch).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                    .clickable(enabled = !brief.loading, role = Role.Button, onClickLabel = "Actualiser", onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                if (brief.loading) AiSparkle(size = 20.dp) else StreamiaIcon(StreamiaIconGlyph.Refresh, tint = Ink, size = 20.dp)
            }
        }
        val summary = if (brief.error != null && !brief.loading) brief.error else brief.headline ?: items.firstOrNull()?.text
        if (summary != null || brief.loading) {
            MobileCard(Modifier.fillMaxWidth(), radius = RadiusCard) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    summary?.let { Text(it, color = Ink, fontSize = if (items.isEmpty()) 15.sp else 21.sp, lineHeight = if (items.isEmpty()) 20.sp else 27.sp, fontWeight = FontWeight.Bold) }
                    AiLoadingIndicator("L'assistant résume ce qui passe en ce moment…", brief.loading)
                    if (items.isNotEmpty()) {
                        Text(if (items.size == 1) "1 chaîne à regarder maintenant" else "${items.size} chaînes à regarder maintenant", color = MutedInk, fontSize = 14.sp)
                    }
                }
            }
        }
        if (items.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.channel!!.key + it.text.hashCode() }) { item ->
                    val channel = item.channel ?: return@items
                    Column(
                        Modifier.width(270.dp).height(150.dp).clip(RoundedCornerShape(RadiusTile)).background(Color.White.copy(alpha = 0.1f))
                            .clickable(role = Role.Button) { onOpen(channel) }.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChannelLogo(channel.iconUrl, channel.displayName, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)), imagePadding = 4)
                            Row(
                                Modifier.height(24.dp).clip(RoundedCornerShape(RadiusPill)).background(AccentPink.copy(alpha = 0.24f)).padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(AccentPinkText))
                                Text("EN DIRECT", color = AccentPinkText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
                            }
                        }
                        Text(item.text, color = Ink, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        Text(channel.displayName, color = AccentPinkText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * Carte secondaire « ✦ Télécommande téléphone » : le QR code s'affiche sur l'accueil de la TV ; un appui explique
 * comment s'en servir depuis ce téléphone. QR en vignette d'attente pour l'instant.
 */
@Composable
private fun RemoteCard() {
    var help by remember { mutableStateOf(false) }
    MobileCard(Modifier.fillMaxWidth(), onClick = { help = true }, radius = RadiusPanel) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                Text("QR", color = Color(0xFF333333), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text("✦ Télécommande téléphone", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text("Écrivez à la TV depuis ce téléphone", color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Text("›", color = MutedInk, fontSize = 18.sp)
        }
    }
    if (help) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { help = false }) {
            MobileCard(Modifier.fillMaxWidth(), radius = RadiusCard) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("✦ Télécommande téléphone", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Sur la TV, le QR code est affiché sur l'accueil, à côté de « Ce soir ? ». Scannez-le avec l'appareil photo de ce téléphone, " +
                            "puis écrivez : « mets beIN Sports 1 », « reprends ma série ».",
                        color = Ink.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    Box(
                        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(RadiusPill)).background(Color.White.copy(alpha = 0.12f))
                            .clickable(role = Role.Button) { help = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("OK", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
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

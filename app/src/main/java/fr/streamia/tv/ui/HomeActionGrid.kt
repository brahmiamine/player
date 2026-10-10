package fr.streamia.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaType
import androidx.compose.ui.graphics.Color
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.HeroWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import java.text.NumberFormat
import java.util.Locale

// Grille des actions principales de l'accueil.

/**
 * Grille du haut de l'accueil, sur une seule rangée : « TV en direct » (300 dp), quatre tuiles égales
 * (Films, Séries, Recherche, Guide TV), puis les quatre actions rangées en grille 2 × 2 (300 dp).
 * Textes alignés à gauche, en bas de chaque tuile.
 */
internal val MainGridHeight = 150.dp

private val SideColumnWidth = 300.dp

@Composable
internal fun MainActionGrid(
    catalog: Catalog,
    catalogLoading: Boolean,
    busy: Boolean,
    firstFocus: FocusRequester?,
    focusTarget: HomeFocusTarget?,
    gridFocusRequester: FocusRequester,
    changePlaylistFocusRequester: FocusRequester,
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
        val liveCount = catalog.count(MediaType.Live)
        val liveTileEnabled = !catalogLoading && liveCount > 0
        LiveTile(
            count = if (catalogLoading) null else liveCount,
            modifier = Modifier
                .then(if (firstFocus != null && liveTileEnabled) Modifier.focusRequester(firstFocus) else Modifier)
                .gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.Live)
                .width(SideColumnWidth)
                .fillMaxHeight(),
            onClick = { onOpenSection(MediaType.Live) },
            enabled = liveTileEnabled,
        )
        HomeTile(
            title = "Films",
            icon = StreamiaIconGlyph.Movie,
            subtitle = if (catalogLoading) "Chargement…" else "${catalog.count(MediaType.Movie).grouped()} contenus",
            modifier = Modifier.weight(1f).fillMaxHeight()
                .gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.Movies),
            onClick = { onOpenSection(MediaType.Movie) },
            enabled = !catalogLoading && catalog.count(MediaType.Movie) > 0,
        )
        HomeTile(
            title = "Séries",
            icon = StreamiaIconGlyph.Series,
            subtitle = if (catalogLoading) "Chargement…" else "${catalog.count(MediaType.Series).grouped()} contenus",
            modifier = Modifier.weight(1f).fillMaxHeight()
                .gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.Series),
            onClick = { onOpenSection(MediaType.Series) },
            enabled = !catalogLoading && catalog.count(MediaType.Series) > 0,
        )
        HomeTile(
            title = "Recherche",
            icon = StreamiaIconGlyph.Search,
            subtitle = "Tout le catalogue",
            modifier = Modifier.weight(1f).fillMaxHeight()
                .gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.Search),
            onClick = onSearch,
            enabled = !catalogLoading,
        )
        HomeTile(
            title = "Guide TV",
            icon = StreamiaIconGlyph.Guide,
            subtitle = "EPG",
            modifier = Modifier.weight(1f).fillMaxHeight()
                .gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.Guide),
            onClick = onEpg,
            enabled = !catalogLoading && liveCount > 0,
        )

        Column(
            Modifier.width(SideColumnWidth).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeAction(
                    "Paramètres",
                    onSettings,
                    Modifier.weight(1f)
                        .then(if (firstFocus != null && !liveTileEnabled) Modifier.focusRequester(firstFocus) else Modifier)
                        .gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.Settings),
                )
                HomeAction(
                    if (busy || catalogLoading) "Chargement…" else "Actualiser",
                    onRefresh,
                    Modifier.weight(1f).gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.Refresh),
                    enabled = !busy && !catalogLoading,
                )
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeAction(
                    "Matchs du jour",
                    onOpenLiveMatches,
                    Modifier.weight(1f).gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.LiveMatches),
                )
                HomeAction(
                    "Changer de liste",
                    onChangePlaylist,
                    Modifier.weight(1f)
                        .focusRequester(changePlaylistFocusRequester)
                        .gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.ChangePlaylist),
                )
            }
        }
    }
}

private fun Modifier.gridFocus(requester: FocusRequester, matches: Boolean): Modifier =
    if (matches) focusRequester(requester) else this

/** « 55 940 » : séparateur de milliers à la française. */
private fun Int.grouped(): String = NumberFormat.getIntegerInstance(Locale.FRENCH).format(this)

/** Tuile principale « TV en direct » : aplat rose, nombre de chaînes en grand, en bas à gauche. */
@Composable
private fun LiveTile(count: Int?, modifier: Modifier, onClick: () -> Unit, enabled: Boolean) {
    FocusableSurface(onClick = onClick, enabled = enabled, accent = true, modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Bottom) {
            Text("TV en direct", color = Ink.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                count?.grouped() ?: "…",
                color = Ink,
                fontSize = 34.sp,
                lineHeight = 37.sp,
                fontWeight = HeroWeight,
            )
            Text(if (count == null) "Chargement…" else "chaînes", color = Ink.copy(alpha = 0.9f), fontSize = 15.sp)
        }
    }
}

@Composable
private fun HomeTile(
    title: String,
    icon: StreamiaIconGlyph,
    subtitle: String,
    modifier: Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    FocusableSurface(onClick = onClick, enabled = enabled, modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
            StreamiaIcon(icon, tint = AccentPinkText, size = 32.dp)
            Spacer(Modifier.weight(1f))
            Text(title, color = Ink, fontSize = 20.sp, fontWeight = HeadingWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = MutedInk, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HomeAction(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
) {
    FocusableSurface(onClick = onClick, enabled = enabled, idleBackground = Color.White.copy(alpha = 0.07f), modifier = modifier.fillMaxHeight()) {
        Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(title, color = Ink, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

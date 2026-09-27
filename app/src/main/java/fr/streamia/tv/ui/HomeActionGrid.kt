package fr.streamia.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk

// Grille des actions principales de l'accueil.

/** Hauteur allouée à la grille d'actions principale : proche de la surface qu'occupait
 * l'ancien `fillMaxSize()` (écran logique 1280x720, moins l'en-tête et les marges), pour que
 * l'accueil garde le même confort quand aucune rangée « Reprendre »/« Favoris » n'est affichée. */
// Grille compacte : laisse la place aux rangées de contenu sous la navigation principale.
internal val MainGridHeight = 300.dp

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
                Modifier.weight(1f)
                    .focusRequester(changePlaylistFocusRequester)
                    .gridFocus(gridFocusRequester, focusTarget == HomeFocusTarget.ChangePlaylist),
            )
        }
    }
}

private fun Modifier.gridFocus(requester: FocusRequester, matches: Boolean): Modifier =
    if (matches) focusRequester(requester) else this

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

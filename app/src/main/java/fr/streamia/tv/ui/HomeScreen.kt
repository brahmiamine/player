package fr.streamia.tv.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    catalog: Catalog,
    profileName: String?,
    offline: Boolean,
    busy: Boolean,
    onOpenSection: (MediaType) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onRefresh: () -> Unit,
    onChangePlaylist: () -> Unit,
) {
    val firstFocus = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Night)
            .padding(horizontal = 46.dp, vertical = 30.dp),
    ) {
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
                        if (expiry != null) append(" · expire le $expiry")
                    },
                    color = if (offline) FocusBlueBright else MutedInk,
                    fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            HomeTile(
                title = "TV en direct",
                subtitle = "${catalog.count(MediaType.Live)} chaînes",
                symbol = "▣",
                modifier = Modifier
                    .focusRequester(firstFocus)
                    .width(360.dp)
                    .fillMaxSize(),
                onClick = { onOpenSection(MediaType.Live) },
                enabled = catalog.count(MediaType.Live) > 0,
                prominent = true,
            )

            Column(
                Modifier.width(360.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    HomeTile(
                        title = "Films",
                        subtitle = "${catalog.count(MediaType.Movie)} contenus",
                        symbol = "▶",
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        onClick = { onOpenSection(MediaType.Movie) },
                        enabled = catalog.count(MediaType.Movie) > 0,
                    )
                    HomeTile(
                        title = "Séries",
                        subtitle = "${catalog.count(MediaType.Series)} contenus",
                        symbol = "▤",
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        onClick = { onOpenSection(MediaType.Series) },
                        enabled = catalog.count(MediaType.Series) > 0,
                    )
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    HomeTile(
                        title = "Recherche",
                        subtitle = "Tout le catalogue",
                        symbol = "⌕",
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        onClick = onSearch,
                    )
                    HomeTile(
                        title = "Guide TV",
                        subtitle = "EPG",
                        symbol = "≡",
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        onClick = onEpg,
                        enabled = catalog.count(MediaType.Live) > 0,
                    )
                }
            }

            Column(
                Modifier.weight(1f).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                HomeAction("⚙", "Paramètres", onSettings, Modifier.weight(1f))
                HomeAction("↻", if (busy) "Actualisation…" else "Actualiser", onRefresh, Modifier.weight(1f), enabled = !busy)
                HomeAction("⇄", "Changer de liste", onChangePlaylist, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HomeTile(
    title: String,
    subtitle: String,
    symbol: String,
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
            Text(symbol, color = FocusBlueBright, fontSize = if (prominent) 88.sp else 54.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(if (prominent) 24.dp else 12.dp))
            Text(title, color = Ink, fontSize = if (prominent) 29.sp else 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color = MutedInk, fontSize = if (prominent) 15.sp else 12.sp)
        }
    }
}

@Composable
private fun HomeAction(
    symbol: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
) {
    FocusableSurface(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(symbol, color = FocusBlueBright, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(18.dp))
            Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun formatExpiry(epochSeconds: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(epochSeconds * 1000L))

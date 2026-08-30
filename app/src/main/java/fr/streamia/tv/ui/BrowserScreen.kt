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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.LiveCategory
import fr.streamia.tv.domain.LiveChannel
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.WarmSignal
import kotlinx.coroutines.yield

@Composable
fun BrowserScreen(
    catalog: Catalog,
    offline: Boolean,
    busy: Boolean,
    message: String?,
    onChannelSelected: (LiveChannel) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val categories = remember(catalog) { listOf(Catalog.AllCategory) + catalog.categories }
    var selectedCategoryId by remember(catalog) { mutableStateOf(categories.first().id) }
    val channels = remember(catalog, selectedCategoryId) { catalog.channelsIn(selectedCategoryId) }

    Column(Modifier.fillMaxSize().background(Night)) {
        BrowserHeader(
            channelCount = catalog.channels.size,
            offline = offline,
            busy = busy,
            onRefresh = onRefresh,
            onLogout = onLogout,
        )
        if (message != null) {
            FocusableSurface(
                onClick = onDismissMessage,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp).height(52.dp),
            ) {
                Text(
                    "$message  ·  OK pour fermer",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        }
        Row(Modifier.fillMaxSize().padding(start = 26.dp, end = 26.dp, bottom = 24.dp)) {
            CategoryRail(
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                countFor = { catalog.channelsIn(it.id).size },
                onSelected = { selectedCategoryId = it.id },
                modifier = Modifier.width(310.dp).fillMaxHeight(),
            )
            Spacer(Modifier.width(26.dp))
            ChannelGrid(
                categoryName = categories.firstOrNull { it.id == selectedCategoryId }?.name.orEmpty(),
                channels = channels,
                onChannelSelected = onChannelSelected,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun BrowserHeader(
    channelCount: Int,
    offline: Boolean,
    busy: Boolean,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(98.dp).padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreamiaLogo(compact = true)
        Spacer(Modifier.width(24.dp))
        Box(Modifier.size(7.dp).background(if (offline) WarmSignal else Color(0xFF6CCB91)))
        Spacer(Modifier.width(9.dp))
        Text(
            if (offline) "Catalogue hors ligne" else "$channelCount chaînes disponibles",
            color = if (offline) WarmSignal else MutedInk,
            fontSize = 15.sp,
        )
        Spacer(Modifier.weight(1f))
        FocusableSurface(
            onClick = onRefresh,
            enabled = !busy,
            modifier = Modifier.width(164.dp).height(54.dp),
        ) {
            Text(
                if (busy) "Actualisation…" else "↻  Actualiser",
                color = Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        FocusableSurface(onClick = onLogout, modifier = Modifier.width(150.dp).height(54.dp)) {
            Text("Changer de compte", color = Ink, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun CategoryRail(
    categories: List<LiveCategory>,
    selectedCategoryId: String,
    countFor: (LiveCategory) -> Int,
    onSelected: (LiveCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            "Catégories",
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 14.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(categories, key = { it.id }) { category ->
                FocusableSurface(
                    onClick = { onSelected(category) },
                    selected = selectedCategoryId == category.id,
                    modifier = Modifier.fillMaxWidth().height(62.dp),
                    contentDescription = "Catégorie ${category.name}, ${countFor(category)} chaînes",
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            category.name,
                            color = Ink,
                            fontSize = 17.sp,
                            fontWeight = if (selectedCategoryId == category.id) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(countFor(category).toString(), color = MutedInk, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelGrid(
    categoryName: String,
    channels: List<LiveChannel>,
    onChannelSelected: (LiveChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstChannelFocus = remember(categoryName) { FocusRequester() }
    LaunchedEffect(categoryName, channels.size) {
        if (channels.isNotEmpty()) {
            yield()
            runCatching { firstChannelFocus.requestFocus() }
        }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                categoryName,
                style = MaterialTheme.typography.headlineSmall,
                color = Ink,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(14.dp))
            Text("${channels.size} chaînes", color = MutedInk, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text("↑ ↓ ← → naviguer    OK regarder", color = MutedInk, fontSize = 14.sp)
        }

        if (channels.isEmpty()) {
            Box(Modifier.fillMaxSize().background(DeepSurface), contentAlignment = Alignment.Center) {
                Text("Aucune chaîne dans cette catégorie", color = MutedInk, fontSize = 19.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(238.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(channels, key = { it.id }) { channel ->
                    ChannelCard(
                        channel = channel,
                        onClick = { onChannelSelected(channel) },
                        modifier = if (channel.id == channels.first().id) Modifier.focusRequester(firstChannelFocus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: LiveChannel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(116.dp),
        contentDescription = "Regarder ${channel.name}",
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(channel.iconUrl, channel.name, Modifier.size(82.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.name,
                    color = Ink,
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text("CH ${channel.number}", color = FocusBlueBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

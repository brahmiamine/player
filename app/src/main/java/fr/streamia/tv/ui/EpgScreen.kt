package fr.streamia.tv.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgGuide
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EpgScreen(
    catalog: Catalog,
    guide: EpgGuide?,
    loading: Boolean,
    message: String?,
    onOpenChannel: (MediaEntry) -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var categoryId by remember { mutableStateOf(Catalog.ALL_CATEGORY_ID) }
    var selectedChannel by remember { mutableStateOf<MediaEntry?>(null) }
    val categories = remember(catalog) { listOf(Catalog.allCategory(MediaType.Live)) + catalog.categoriesFor(MediaType.Live) }
    val channels = remember(catalog, categoryId) { catalog.entriesIn(MediaType.Live, categoryId) }
    if (selectedChannel == null || selectedChannel !in channels) selectedChannel = channels.firstOrNull()

    Column(Modifier.fillMaxSize().background(Night).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FocusableSurface(onClick = onBack, modifier = Modifier.width(115.dp).height(50.dp)) {
                Text("← Retour", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text("Guide TV · EPG complet", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (guide == null) "XMLTV / fournisseur" else "${guide.channels.size} chaînes EPG", color = MutedInk, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            FocusableSurface(onClick = onReload, enabled = !loading, modifier = Modifier.width(130.dp).height(50.dp)) {
                Text(if (loading) "Chargement…" else "↻ Recharger", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp))
            }
        }
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, color = MutedInk, fontSize = 13.sp)
        }
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(270.dp).fillMaxHeight()) {
                Text("Catégories", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(9.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(categories, key = { it.key }) { category ->
                        FocusableSurface(
                            onClick = { categoryId = category.id; selectedChannel = null },
                            selected = categoryId == category.id,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                        ) {
                            Text(category.name, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 13.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.width(390.dp).fillMaxHeight()) {
                Text("Chaînes (${channels.size})", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(9.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(channels, key = MediaEntry::key) { channel ->
                        val current = guide?.forEntry(channel)?.currentProgram()
                        FocusableSurface(
                            onClick = { selectedChannel = channel },
                            selected = selectedChannel?.key == channel.key,
                            modifier = Modifier.fillMaxWidth().height(76.dp),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                ChannelLogo(channel.iconUrl, channel.displayName, Modifier.size(50.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${channel.number} · ${channel.displayName}", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(current?.title ?: "Programme non renseigné", color = if (current != null) FocusBlueBright else MutedInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val channel = selectedChannel
                if (channel == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aucune chaîne", color = MutedInk, fontSize = 18.sp)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(channel.displayName, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Text("Programme complet de la chaîne", color = MutedInk, fontSize = 13.sp)
                        }
                        FocusableSurface(onClick = { onOpenChannel(channel) }, modifier = Modifier.width(150.dp).height(50.dp)) {
                            Text("▶ Regarder", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 15.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val programs = guide?.forEntry(channel).orEmpty()
                    when {
                        loading && guide == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Chargement du guide XMLTV…", color = MutedInk, fontSize = 18.sp)
                        }
                        programs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Aucun programme EPG associé à cette chaîne.", color = MutedInk, fontSize = 17.sp)
                        }
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(programs, key = { "${it.channelId}:${it.startEpochSeconds}:${it.title}" }) { program ->
                                ProgramRow(program)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgramRow(program: EpgProgram) {
    val now = System.currentTimeMillis() / 1000
    val isCurrent = (program.startEpochSeconds ?: Long.MIN_VALUE) <= now && (program.endEpochSeconds ?: Long.MAX_VALUE) >= now
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (isCurrent) fr.streamia.tv.ui.theme.DeepSurface else Night.copy(alpha = 0.65f))
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(program.timeRange(), color = if (isCurrent) FocusBlueBright else MutedInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            Text(program.title, color = Ink, fontSize = 16.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isCurrent) Text("EN COURS", color = FocusBlueBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        if (!program.description.isNullOrBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(program.description, color = MutedInk, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (!program.category.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(program.category, color = FocusBlueBright, fontSize = 11.sp)
        }
    }
}

private fun List<EpgProgram>.currentProgram(): EpgProgram? {
    val now = System.currentTimeMillis() / 1000
    return firstOrNull { (it.startEpochSeconds ?: Long.MIN_VALUE) <= now && (it.endEpochSeconds ?: Long.MAX_VALUE) >= now }
        ?: firstOrNull { (it.startEpochSeconds ?: Long.MAX_VALUE) > now }
}

private fun EpgProgram.timeRange(): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    fun format(epoch: Long?): String = epoch?.let { formatter.format(Date(it * 1000)) } ?: "--:--"
    return "${format(startEpochSeconds)} – ${format(endEpochSeconds)}"
}

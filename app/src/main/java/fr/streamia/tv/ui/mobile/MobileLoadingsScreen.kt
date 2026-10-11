package fr.streamia.tv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.LoadingGroup
import fr.streamia.tv.data.LoadingRow
import fr.streamia.tv.data.LoadingState
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.relative
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.WarmSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val FreshGreen = Color(0xFF30D158)
private const val RELOAD_AFTER_ACTION_MS = 3_000L
private const val CLOCK_TICK_MS = 30_000L

/**
 * Page « Chargements » au doigt : même contenu que celle de la TV (fraîcheur de chaque cache, prochaine
 * échéance, relance par source), en cartes empilées. Les relances sont suivies quelques secondes
 * (« En cours… ») puis la page se relit.
 */
@Composable
fun MobileLoadingsScreen(
    catalogBusy: Boolean,
    onLoad: suspend () -> List<LoadingRow>,
    onAction: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<LoadingRow>?>(null) }
    var pending by remember { mutableStateOf(setOf<String>()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey, catalogBusy) {
        rows = runCatching { onLoad() }.getOrNull() ?: rows
        now = System.currentTimeMillis()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MS)
            reloadKey++
        }
    }

    fun run(ids: List<String>) {
        scope.launch {
            ids.forEach { id ->
                pending = pending + id
                onAction(id)
                scope.launch {
                    delay(RELOAD_AFTER_ACTION_MS)
                    pending = pending - id
                    reloadKey++
                }
                delay(350)
            }
        }
    }

    val loaded = rows
    val running = pending.isNotEmpty() || catalogBusy
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MobileIconButton(StreamiaIconGlyph.ChevronLeft, onClick = onBack)
            Text("Chargements", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 4.dp))
        }
        if (loaded == null) {
            Text("Chargement…", color = MutedInk, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            return@Column
        }
        val actionable = loaded.filter { it.action != null }.map { it.id }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val catalog = loaded.firstOrNull { it.id == "catalog" }
            val stale = loaded.count { (it.group == LoadingGroup.Guides || it.group == LoadingGroup.Epg) && it.state == LoadingState.Stale && it.id !in pending }
            val nextTask = loaded.filter { it.group == LoadingGroup.Scheduled && it.state == LoadingState.Scheduled }.mapNotNull { it.nextAtMillis }.minOrNull()
            Group("Résumé") {
                SettingsRow(
                    "Catalogue",
                    catalog?.lastAtMillis?.let { "Dernier chargement " + relative(it, now) } ?: "Aucun chargement terminé",
                    onClick = {},
                ) {
                    val fresh = catalog?.state == LoadingState.Fresh
                    Text(if (fresh) "À jour" else "À rafraîchir", color = if (fresh) FreshGreen else WarmSignal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                SettingsRow("Guides", "Guide TV et guides de l'accueil", onClick = {}) {
                    Text(if (stale == 0) "Tout est frais" else "$stale à rafraîchir", color = if (stale == 0) FreshGreen else WarmSignal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                SettingsRow("Prochaine tâche", "Planifiée par Android", onClick = {}) {
                    Text(nextTask?.let { relative(it, now) } ?: "—", color = Ink, fontSize = 13.sp)
                }
                SettingsLink(if (running) "Actualisation…" else "Tout actualiser", null) { if (!running && actionable.isNotEmpty()) run(actionable) }
            }
            LoadingGroup.entries.forEach { group ->
                val groupRows = loaded.filter { it.group == group }
                if (groupRows.isEmpty()) return@forEach
                Group(group.title) {
                    groupRows.forEach { row ->
                        LoadingRowView(row, running = row.id in pending || (row.id == "catalog" && catalogBusy), now = now) { run(listOf(row.id)) }
                    }
                }
            }
            Text(
                "Les tâches planifiées attendent la fin d'une lecture vidéo et de la navigation ; leur heure exacte peut être décalée par Android.",
                color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun LoadingRowView(row: LoadingRow, running: Boolean, now: Long, onAction: () -> Unit) {
    val state = if (running) LoadingState.Running else row.state
    val stateInk = when (state) {
        LoadingState.Fresh -> FreshGreen
        LoadingState.Stale -> WarmSignal
        LoadingState.Running -> AccentPinkText
        LoadingState.Disabled -> MutedInk
        else -> Ink.copy(alpha = 0.85f)
    }
    val next = when {
        row.nextAtMillis == null -> "—"
        state == LoadingState.Stale && row.nextAtMillis < now -> "dès que possible"
        else -> relative(row.nextAtMillis, now)
    }
    val canAct = row.action != null && !running
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = canAct, role = Role.Button, onClick = onAction)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.name, color = if (state == LoadingState.Disabled) MutedInk else Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(state.label, color = stateInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(row.subtitle, color = MutedInk, fontSize = 12.sp)
        Text(
            "${row.frequency} · dernier : ${row.detail ?: relative(row.lastAtMillis, now)} · prochain : $next",
            color = MutedInk, fontSize = 12.sp,
        )
        if (row.action != null) Text(if (running) "…" else row.action.label, color = if (running) MutedInk else AccentPinkText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

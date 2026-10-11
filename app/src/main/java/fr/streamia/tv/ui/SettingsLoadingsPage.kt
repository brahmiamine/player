package fr.streamia.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.LoadingAction
import fr.streamia.tv.data.LoadingGroup
import fr.streamia.tv.data.LoadingRow
import fr.streamia.tv.data.LoadingState
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusTile
import fr.streamia.tv.ui.theme.WarmSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private val FreshGreen = Color(0xFF30D158)
private const val RELOAD_AFTER_ACTION_MS = 3_000L
private const val CLOCK_TICK_MS = 30_000L

/**
 * Première page de Paramètres : tout ce que l'appli charge, la fraîcheur de chaque cache et la
 * prochaine échéance connue. [onLoad] relit l'état (sans réseau) ; [onAction] relance une source.
 * Les relances sont suivies localement quelques secondes (« En cours… ») puis la page se relit.
 */
@Composable
internal fun SettingsLoadingsPage(
    catalogBusy: Boolean,
    onLoad: suspend () -> List<LoadingRow>,
    onAction: (String) -> Unit,
) {
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
    if (loaded == null) {
        Text("Chargement…", color = MutedInk, fontSize = 15.sp)
        return
    }
    val actionable = loaded.filter { it.action != null }.map { it.id }
    val running = pending.isNotEmpty() || catalogBusy

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val catalog = loaded.firstOrNull { it.id == "catalog" }
        val stale = loaded.count { (it.group == LoadingGroup.Guides || it.group == LoadingGroup.Epg) && it.state == LoadingState.Stale && it.id !in pending }
        val nextTask = loaded.filter { it.group == LoadingGroup.Scheduled && it.state == LoadingState.Scheduled }.mapNotNull { it.nextAtMillis }.minOrNull()
        SummaryTile(
            "Catalogue",
            if (catalog?.state == LoadingState.Fresh) "À jour" else "À rafraîchir",
            if (catalog?.state == LoadingState.Fresh) FreshGreen else WarmSignal,
            catalog?.lastAtMillis?.let { "Dernier chargement " + relative(it, now) } ?: "Aucun chargement terminé",
            Modifier.weight(1f),
        )
        SummaryTile(
            "Guides",
            if (stale == 0) "Tout est frais" else "$stale à rafraîchir",
            if (stale == 0) FreshGreen else WarmSignal,
            "Guide TV et guides de l'accueil",
            Modifier.weight(1f),
        )
        SummaryTile(
            "Prochaine tâche",
            nextTask?.let { relative(it, now) } ?: "—",
            Ink,
            "Planifiée par Android",
            Modifier.weight(1f),
        )
        FocusableSurface(
            onClick = { run(actionable) },
            enabled = !running && actionable.isNotEmpty(),
            accent = true,
            modifier = Modifier.width(210.dp).height(ButtonHeightPx),
        ) {
            Text(
                if (running) "Actualisation…" else "Tout actualiser",
                color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
    }

    LoadingGroup.entries.forEach { group ->
        val groupRows = loaded.filter { it.group == group }
        if (groupRows.isEmpty()) return@forEach
        Spacer(Modifier.height(6.dp))
        SectionLabel(group.title, fontSize = 13.sp)
        ColumnHeader()
        groupRows.forEach { row ->
            LoadingRowView(
                row = row,
                running = row.id in pending || (row.id == "catalog" && catalogBusy),
                now = now,
                onAction = { run(listOf(row.id)) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Les tâches planifiées attendent la fin d'une lecture vidéo et de la navigation ; leur heure exacte peut être décalée par Android.",
        color = MutedInk, fontSize = 12.sp,
    )
}

private val ButtonHeightPx = 52.dp
private val ActionWidth = 140.dp

@Composable
private fun SummaryTile(label: String, value: String, valueColor: Color, hint: String, modifier: Modifier) {
    Column(
        modifier.background(DeepSurface, RoundedCornerShape(RadiusTile)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label.uppercase(), color = MutedInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(value, color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(hint, color = MutedInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ColumnHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("SOURCE" to 2.6f, "FRÉQUENCE" to 1.3f, "DERNIER" to 1f, "PROCHAIN" to 1f, "ÉTAT" to 1.1f).forEach { (title, weight) ->
            Text(title, color = MutedInk.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(weight))
        }
        Spacer(Modifier.width(ActionWidth))
    }
}

@Composable
private fun LoadingRowView(row: LoadingRow, running: Boolean, now: Long, onAction: () -> Unit) {
    val state = if (running) LoadingState.Running else row.state
    val (stateInk, stateBg) = when (state) {
        LoadingState.Fresh -> FreshGreen to FreshGreen.copy(alpha = 0.16f)
        LoadingState.Stale -> WarmSignal to WarmSignal.copy(alpha = 0.16f)
        LoadingState.Running -> AccentPinkText to AccentPinkText.copy(alpha = 0.16f)
        LoadingState.Disabled -> MutedInk to Color.White.copy(alpha = 0.06f)
        else -> Ink.copy(alpha = 0.85f) to Color.White.copy(alpha = 0.10f)
    }
    val next = when {
        row.nextAtMillis == null -> "—"
        state == LoadingState.Stale && row.nextAtMillis < now -> "dès que possible"
        else -> relative(row.nextAtMillis, now)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(Color.White.copy(alpha = if (state == LoadingState.Disabled) 0.03f else 0.06f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(2.6f)) {
            Text(row.name, color = if (state == LoadingState.Disabled) MutedInk else Ink, fontSize = 15.sp, fontWeight = HeadingWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.subtitle, color = MutedInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(row.frequency, color = Ink.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 2, modifier = Modifier.weight(1.3f))
        Text(row.detail ?: relative(row.lastAtMillis, now), color = Ink.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(next, color = Ink.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Box(Modifier.weight(1.1f), contentAlignment = Alignment.CenterStart) {
            Row(
                Modifier.background(stateBg, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state == LoadingState.Running) ButtonSpinner(size = 12.dp, color = AccentPinkText)
                Text(state.label, color = stateInk, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        Box(Modifier.width(ActionWidth), contentAlignment = Alignment.CenterEnd) {
            if (row.action != null) {
                FocusableSurface(onClick = onAction, enabled = !running, focusScale = 1.03f, modifier = Modifier.width(ActionWidth).height(44.dp)) {
                    Text(
                        if (running) "…" else row.action.label,
                        color = if (running) MutedInk else Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

/** « il y a 12 min », « dans 3 h », « à l'instant » ; « — » quand la date est inconnue. */
internal fun relative(millis: Long?, now: Long): String {
    if (millis == null) return "—"
    val delta = millis - now
    val minutes = abs(delta) / 60_000
    if (minutes < 1) return "à l'instant"
    val span = when {
        minutes < 60 -> "$minutes min"
        minutes < 48 * 60 -> "${minutes / 60} h" + (minutes % 60).takeIf { it >= 5 && minutes < 6 * 60 }?.let { " %02d".format(it) }.orEmpty()
        else -> "${minutes / (24 * 60)} j"
    }
    return if (delta < 0) "il y a $span" else "dans $span"
}

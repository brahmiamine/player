package fr.streamia.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusPill

// En-tête des rangées à bascule de l'accueil : un titre et une ou deux séries de pastilles.
// La pastille focalisée devient la sélection (◀ ▶ suffisent, sans OK) ; ▼ entre dans la rangée.

/** Séparateur de la clé d'un élément : « onglet~~clé », pour rouvrir la bonne bascule au retour sur l'accueil. */
internal const val TAB_KEY_SEPARATOR = "~~"

internal fun tabItemKey(tab: String, itemKey: String) = tab + TAB_KEY_SEPARATOR + itemKey

internal fun String.tabPart(): String? = substringBefore(TAB_KEY_SEPARATOR, "").ifEmpty { null }

internal fun String.keyPart(): String = substringAfter(TAB_KEY_SEPARATOR, this)

@Composable
internal fun TabbedRowHeader(
    title: String,
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    secondTabs: List<String> = emptyList(),
    secondSelected: Int = 0,
    onSelectSecond: (Int) -> Unit = {},
) {
    // Deux lignes quand il y a des filtres : ◀ ▶ restent dans un groupe, ▲ ▼ passent de l'un à l'autre,
    // sinon « Maintenant » + « beIN » seraient inatteignables ensemble (on traverserait « Ce soir »).
    Column(modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SectionLabel(title, fontSize = 16.sp)
            if (tabs.size > 1) SegmentedPills(tabs, selected, onSelect, accent = true)
        }
        if (secondTabs.size > 1) SegmentedPills(secondTabs, secondSelected, onSelectSecond, accent = false)
    }
}

@Composable
private fun SegmentedPills(options: List<String>, selected: Int, onSelect: (Int) -> Unit, accent: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            FocusableSurface(
                onClick = { onSelect(index) },
                onFocused = { onSelect(index) },
                wrapContent = true,
                radius = RadiusPill,
                focusScale = 1.04f,
                idleBackground = when {
                    on && accent -> AccentPink
                    on -> Color.White.copy(alpha = 0.22f)
                    else -> Color.White.copy(alpha = 0.07f)
                },
                modifier = Modifier.height(40.dp),
            ) {
                Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        color = if (on) Ink else MutedInk,
                        fontSize = 15.sp,
                        fontWeight = if (on) HeadingWeight else FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

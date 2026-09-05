package fr.streamia.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.recommendation.RecommendedMedia
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink

/**
 * Rangée « Films/Séries similaires » réutilisée par les fiches détail. Utilise
 * [fr.streamia.tv.recommendation.RecommendationEngine.similarTo] : même moteur de similarité
 * métadonnées que l'accueil, calculé pour l'entrée actuellement affichée.
 */
@Composable
fun SimilarMediaRow(
    title: String,
    items: List<RecommendedMedia>,
    onOpenSimilar: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        SectionLabel(title, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(items, key = { _, recommended -> recommended.entry.key }) { _, recommended ->
                SimilarMediaCard(recommended = recommended, onClick = { onOpenSimilar(recommended.entry) })
            }
        }
    }
}

@Composable
private fun SimilarMediaCard(recommended: RecommendedMedia, onClick: () -> Unit) {
    val entry = recommended.entry
    FocusableSurface(onClick = onClick, modifier = Modifier.width(150.dp).height(196.dp)) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            MediaArtwork(entry.iconUrl, entry.displayName, Modifier.fillMaxWidth().height(112.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                entry.displayName,
                color = Ink,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            recommended.reason?.let { reason ->
                Spacer(Modifier.height(3.dp))
                Text(reason, color = FocusBlueBright, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

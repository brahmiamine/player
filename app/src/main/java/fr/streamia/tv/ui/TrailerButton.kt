package fr.streamia.tv.ui

import fr.streamia.tv.ui.theme.ButtonHeight
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.TrailerLink
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.TypeLabel

/**
 * Bouton « Bande-annonce » des fiches Film et Série. N'apparaît que si le fournisseur a donné une
 * bande-annonce exploitable ; l'ouvre dans l'appli YouTube (ou le lecteur/navigateur qui gère le
 * lien), et explique l'échec si aucune application de la TV ne sait l'ouvrir.
 */
@Composable
fun TrailerButton(trailer: String?, modifier: Modifier = Modifier) {
    val candidates = remember(trailer) { TrailerLink.candidates(trailer) }
    if (candidates.isEmpty()) return
    val context = LocalContext.current
    var failed by remember(trailer) { mutableStateOf(false) }
    Column(modifier) {
        FocusableSurface(
            onClick = { failed = !openTrailer(context, candidates) },
            wrapContent = true,
            modifier = Modifier.height(ButtonHeight),
        ) {
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                StreamiaIcon(StreamiaIconGlyph.Movie, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text("Bande-annonce", color = Ink, fontSize = TypeLabel, fontWeight = FontWeight.SemiBold)
            }
        }
        if (failed) {
            Spacer(Modifier.height(6.dp))
            Text("Aucune application de la TV ne peut lire cette bande-annonce (installez YouTube).", color = MutedInk, fontSize = TypeLabel)
        }
    }
}

private fun openTrailer(context: Context, candidates: List<String>): Boolean = candidates.any { uri ->
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

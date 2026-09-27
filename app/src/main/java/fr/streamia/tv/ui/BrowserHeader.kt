package fr.streamia.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RadiusPill
import fr.streamia.tv.ui.theme.WarmSignal

// En-tête du navigateur (Direct / Films / Séries) et ses actions.

/**
 * Hauteur du bandeau haut, partagée entre [BrowserHeader] (qui l'utilise comme hauteur réelle) et
 * [LiveCatalogLayout] (qui décale ses panneaux catégories/chaînes de cette même valeur quand le
 * bandeau flotte en transparence par-dessus la vidéo plein écran, pour ne pas se faire recouvrir).
 */
internal val BROWSER_HEADER_HEIGHT = 74.dp

@Composable
internal fun BrowserHeader(
    catalog: Catalog,
    selectedType: MediaType,
    offline: Boolean,
    busy: Boolean,
    // Le Direct affiche ce bandeau flottant par-dessus la vidéo plein écran plutôt que dans sa
    // propre bande opaque : il porte alors son propre fond assombri (même valeur que les panneaux
    // catégories/chaînes) et ses boutons au repos deviennent transparents. VOD garde le bandeau
    // opaque habituel, posé sur le fond plein de son écran.
    translucent: Boolean = false,
    onHome: () -> Unit,
    onTypeSelected: (MediaType) -> Unit,
    onSearch: () -> Unit,
    onEpg: () -> Unit,
    onSettings: () -> Unit,
) {
    val idleBackground = if (translucent) Color.Transparent else DeepSurface
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BROWSER_HEADER_HEIGHT)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StreamiaLogo(compact = true)
            Spacer(Modifier.width(16.dp))
            HeaderAction("Accueil", 100.dp, onHome, idleBackground = idleBackground)
            Spacer(Modifier.width(6.dp))

            for (type in MediaType.entries) {
                FocusableSurface(
                    onClick = { onTypeSelected(type) },
                    selected = selectedType == type,
                    enabled = catalog.count(type) > 0,
                    accent = selectedType == type,
                    idleBackground = idleBackground,
                    modifier = Modifier.width(116.dp).height(48.dp),
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            type.displayName,
                            color = Ink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            catalog.count(type).toString(),
                            color = MutedInk,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
            }

            Spacer(Modifier.weight(1f))
            HeaderAction("Recherche", 56.dp, onSearch, glyph = StreamiaIconGlyph.Search, idleBackground = idleBackground)
            Spacer(Modifier.width(6.dp))
            HeaderAction("EPG", 76.dp, onEpg, catalog.count(MediaType.Live) > 0, idleBackground = idleBackground)
            Spacer(Modifier.width(6.dp))
            HeaderAction("Paramètres", 56.dp, onSettings, glyph = StreamiaIconGlyph.Settings, idleBackground = idleBackground)
            if (busy || offline) {
                Spacer(Modifier.width(10.dp))
                Text(
                    if (busy) "Chargement…" else "Cache",
                    color = if (offline) WarmSignal else MutedInk,
                    fontSize = 14.sp,
                )
            }
        }
    }
    if (translucent) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RadiusPill))
                .background(Night.copy(alpha = 0.72f))
                .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(RadiusPill)),
        ) { row() }
    } else {
        GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(RadiusPill)) { row() }
    }
}

@Composable
private fun HeaderAction(
    label: String,
    width: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
    glyph: StreamiaIconGlyph? = null,
    idleBackground: Color = DeepSurface,
) {
    FocusableSurface(
        onClick = onClick,
        enabled = enabled,
        idleBackground = idleBackground,
        modifier = Modifier.width(width).height(48.dp),
        contentDescription = if (glyph != null) label else null,
    ) {
        if (glyph != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StreamiaIcon(glyph, size = 22.dp) }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

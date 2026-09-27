package fr.streamia.tv.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.type
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.Format
import fr.streamia.tv.player.isDolbyAtmosFormat
import java.util.Locale

// Pistes audio/sous-titres et formats vidéo du lecteur.

internal enum class VideoAspect(val label: String, val resizeMode: Int) {
    Fit("Ajuster", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    Fill("Remplir", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    Zoom("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
}

internal data class TrackChoice(
    val label: String,
    val language: String?,
    // Piste exacte : sélectionner par langue seule laissait ExoPlayer choisir, pour une même langue,
    // la piste « forcés » ou une autre variante que celle affichée dans le menu.
    val group: TrackGroup? = null,
    val trackIndex: Int = 0,
    val forced: Boolean = false,
)

/** Tag de langue "indéterminée" (BCP-47) posé sur tout sous-titre externe chargé manuellement. */
internal const val EXTERNAL_SUBTITLE_LANGUAGE_TAG = "und"

internal fun selectedVideoFormat(tracks: Tracks): androidx.media3.common.Format? {
    tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { group ->
        for (index in 0 until group.length) {
            if (group.isTrackSelected(index)) return group.getTrackFormat(index)
        }
    }
    return null
}

internal fun selectedAudioFormat(tracks: Tracks): androidx.media3.common.Format? {
    tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
        for (index in 0 until group.length) {
            if (group.isTrackSelected(index)) return group.getTrackFormat(index)
        }
    }
    return null
}

internal fun extractChoices(tracks: Tracks, type: Int): List<TrackChoice> = buildList {
    val seen = mutableSetOf<String>()
    tracks.groups.filter { it.type == type }.forEach { group ->
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            // Pistes sans langue gardées (fréquentes en IPTV) : sinon impossible de les choisir.
            val language = format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            val forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0
            val name = format.label?.takeIf(String::isNotBlank)
                ?: language?.let { Locale.forLanguageTag(it).displayLanguage.takeIf(String::isNotBlank) ?: it }
                ?: "Piste ${size + 1}"
            val baseLabel = if (forced) "$name (forcés)" else name
            val label = if (type == C.TRACK_TYPE_AUDIO && isDolbyAtmosFormat(format.sampleMimeType)) {
                "Dolby Atmos · $baseLabel"
            } else {
                baseLabel
            }
            val key = "$label:$language"
            if (seen.add(key)) add(TrackChoice(label, language, group.mediaTrackGroup, index, forced))
        }
    }
}

/**
 * Style + taille des sous-titres pilotés depuis Paramètres. Noms de couleur/type pleinement
 * qualifiés : `android.graphics.Color` entrerait en collision avec `androidx.compose.ui.graphics.Color`
 * déjà importé dans ce fichier pour le reste de l'UI Compose.
 */
internal fun androidx.media3.ui.SubtitleView.applySubtitleStyle(sizeScale: Float, backgroundEnabled: Boolean) {
    setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * sizeScale)
    setStyle(
        androidx.media3.ui.CaptionStyleCompat(
            android.graphics.Color.WHITE,
            if (backgroundEnabled) android.graphics.Color.argb(160, 0, 0, 0) else android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            null,
        ),
    )
}

internal fun subtitleMimeTypeFor(name: String): String? =
    when (name.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        else -> null
    }

/** Nom d'affichage d'un document SAF (souvent différent du dernier segment d'un content://). */
internal fun documentDisplayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
    }
}.getOrNull()

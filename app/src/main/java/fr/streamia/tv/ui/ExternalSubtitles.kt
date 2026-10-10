package fr.streamia.tv.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.SubtitleView
import fr.streamia.tv.data.decodeSubtitle
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Au-delà, ce n'est pas un sous-titre (évite de lire en mémoire un fichier vidéo choisi par erreur). */
private const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024

/** Fréquence de mise à jour de la réplique affichée : imperceptible à l'œil, négligeable pour le processeur. */
private const val SUBTITLE_TICK_MS = 80L

/** Réplique sans fin connue : affichée au plus ce temps-là. */
private const val DEFAULT_CUE_DURATION_MS = 5_000L

/**
 * Répliques triées par début, avec recherche en O(log n) de celles affichées à un instant donné
 * (chevauchements compris, grâce au maximum courant des fins).
 */
internal class TimedCueIndex<T>(entries: List<TimedCue<T>>) {
    private val sorted = entries.sortedBy { it.startMs }
    private val starts = LongArray(sorted.size) { sorted[it].startMs }
    private val maxEndSoFar = LongArray(sorted.size).also { max ->
        var running = Long.MIN_VALUE
        sorted.forEachIndexed { i, cue -> running = maxOf(running, cue.endMs); max[i] = running }
    }

    val size: Int get() = sorted.size

    fun payload(index: Int): T = sorted[index].payload

    /** Indices (croissants) des répliques affichées à [positionMs]. */
    fun activeAt(positionMs: Long): List<Int> {
        var low = 0
        var high = starts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= positionMs) low = mid + 1 else high = mid
        }
        var i = low - 1
        if (i < 0) return emptyList()
        val active = ArrayList<Int>(2)
        while (i >= 0 && maxEndSoFar[i] > positionMs) {
            if (sorted[i].endMs > positionMs) active += i
            i--
        }
        active.reverse()
        return active
    }
}

internal data class TimedCue<T>(val startMs: Long, val endMs: Long, val payload: T)

/** Sous-titre externe prêt à afficher : répliques déjà découpées, mises en forme par Media3. */
internal typealias ExternalSubtitleCues = TimedCueIndex<List<Cue>>

/**
 * Lit et découpe un .srt/.vtt (fichier, content:// ou lien http) hors du thread principal.
 * Rien n'est confié au lecteur vidéo : charger ou décaler un sous-titre ne touche plus au flux.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal suspend fun loadExternalSubtitleCues(context: Context, uri: Uri, mimeType: String): ExternalSubtitleCues =
    withContext(Dispatchers.IO) {
        val bytes = when (uri.scheme) {
            "http", "https" -> {
                val request = Request.Builder().url(uri.toString()).header("User-Agent", fr.streamia.tv.net.HttpClients.USER_AGENT).build()
                fr.streamia.tv.net.HttpClients.player.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Réponse vide")
                    if (body.contentLength() > MAX_SUBTITLE_BYTES) throw IOException("Fichier trop volumineux")
                    body.bytes()
                }
            }
            else -> context.contentResolver.openInputStream(uri)?.use { input ->
                input.readNBytesCompat(MAX_SUBTITLE_BYTES + 1)
            } ?: throw IOException("Fichier illisible")
        }
        if (bytes.size > MAX_SUBTITLE_BYTES) throw IOException("Fichier trop volumineux")
        // Encodage deviné (UTF-8, UTF-16, sinon page de code historique) puis donné en UTF-8 au parseur.
        val text = decodeSubtitle(bytes, Locale.getDefault().language)
        parseSubtitleCues(text.toByteArray(Charsets.UTF_8), mimeType)
    }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun parseSubtitleCues(bytes: ByteArray, mimeType: String): ExternalSubtitleCues {
    val format = Format.Builder().setSampleMimeType(mimeType).build()
    val factory = DefaultSubtitleParserFactory()
    if (!factory.supportsFormat(format)) throw IOException("Format non pris en charge")
    val parser = factory.create(format)
    val cues = ArrayList<TimedCue<List<Cue>>>()
    try {
        parser.parse(bytes, SubtitleParser.OutputOptions.allCues()) { timed ->
            if (timed.startTimeUs == C.TIME_UNSET || timed.cues.isEmpty()) return@parse
            val startMs = timed.startTimeUs / 1000
            val endMs = if (timed.durationUs == C.TIME_UNSET) startMs + DEFAULT_CUE_DURATION_MS else timed.endTimeUs / 1000
            if (endMs > startMs) cues += TimedCue(startMs, endMs, timed.cues)
        }
    } finally {
        parser.reset()
    }
    if (cues.isEmpty()) throw IOException("Aucune réplique trouvée")
    return TimedCueIndex(cues)
}

private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (total < limit) {
        val read = read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        total += read
    }
    return out.toByteArray()
}

/**
 * Affiche le sous-titre externe au-dessus de la vidéo, synchronisé sur la position du lecteur.
 * [offsetMs] (+ = plus tard) s'applique instantanément : aucun fichier réécrit, aucune reconnexion.
 * La vue n'est touchée que lorsque la réplique change (pas de recomposition à chaque tick).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun ExternalSubtitleOverlay(
    player: Player,
    cues: ExternalSubtitleCues,
    offsetMs: Long,
    sizeScale: Float,
    backgroundEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var view by remember { mutableStateOf<SubtitleView?>(null) }
    AndroidView(
        factory = { context -> SubtitleView(context).also { view = it } },
        update = { it.applySubtitleStyle(sizeScale, backgroundEnabled) },
        modifier = modifier,
    )
    LaunchedEffect(view, cues, offsetMs, player) {
        val target = view ?: return@LaunchedEffect
        var shown: List<Int>? = null
        while (true) {
            val active = cues.activeAt(player.currentPosition - offsetMs)
            if (active != shown) {
                shown = active
                target.setCues(active.flatMap(cues::payload))
            }
            delay(SUBTITLE_TICK_MS)
        }
    }
}

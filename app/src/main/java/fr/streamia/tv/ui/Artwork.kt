package fr.streamia.tv.ui

import android.graphics.Bitmap
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import android.graphics.BitmapFactory
import android.content.Context
import android.content.ComponentCallbacks2
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Night
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.Request

// Logos et affiches distants : chargeur d'images, cache et préchargement.

@Composable
fun ChannelLogo(
    url: String?,
    channelName: String,
    modifier: Modifier = Modifier,
    imagePadding: Int = 8,
) {
    RemoteArtwork(
        url = url,
        name = channelName,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        imagePadding = imagePadding,
        maxDecodePx = LOGO_DECODE_PX,
        opaque = false,
    )
}

@Composable
fun MediaArtwork(url: String?, name: String, modifier: Modifier = Modifier) {
    RemoteArtwork(
        url = url,
        name = name,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        imagePadding = 0,
        maxDecodePx = ARTWORK_DECODE_PX,
        opaque = true,
    )
}

// Tailles de décodage selon l'usage : un logo affiché autour de 42–60 dp n'a pas besoin d'une
// bitmap de 640 px (jusqu'à 1,6 Mo chacune), ce qui vidait le cache mémoire en quelques dizaines
// d'images et forçait des redécodages permanents en défilement.
private const val LOGO_DECODE_PX = 160

private const val ARTWORK_DECODE_PX = 480

@Composable
private fun RemoteArtwork(
    url: String?,
    name: String,
    modifier: Modifier,
    contentScale: ContentScale,
    imagePadding: Int,
    maxDecodePx: Int,
    opaque: Boolean,
) {
    val context = LocalContext.current.applicationContext
    // Retour du réseau : les images restées vides pendant la coupure sont redemandées (celles déjà
    // en cache ressortent tout de suite, sans requête ; les échecs antérieurs sont oubliés).
    val networkReconnections = LocalNetworkReconnections.current
    // produceState est annulé quand l'élément quitte l'écran : un élément dépassé pendant un
    // défilement rapide abandonne sa place dans la file au lieu de retarder les logos visibles.
    val bitmap by produceState<ImageBitmap?>(initialValue = ArtworkLoader.get(url, maxDecodePx), key1 = url, key2 = networkReconnections) {
        // produceState garde la valeur précédente quand l'URL change : sans cette remise à zéro,
        // l'image de l'ancien contenu restait affichée et la nouvelle n'était jamais chargée.
        value = ArtworkLoader.get(url, maxDecodePx)
        if (url.isNullOrBlank() || value != null) return@produceState
        value = ArtworkLoader.load(context, url, maxDecodePx, opaque, networkReconnections)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Night.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = "Illustration de $name",
                modifier = Modifier.fillMaxSize().padding(imagePadding.dp),
                contentScale = contentScale,
            )
        } else {
            Text(
                text = name.trim().take(2).uppercase().ifBlank { "TV" },
                color = FocusBlueBright,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Mémoire demandée par le système (boîtier à peu de RAM, lecture 4K, app passée en arrière-plan) :
 * les affiches déjà décodées sont libérées plutôt que de laisser Android tuer l'app ou le lecteur.
 * Le cache disque HTTP reste : les réafficher ne coûte qu'un décodage.
 */
internal fun trimArtworkCache(level: Int) = ArtworkLoader.trim(level)

/**
 * Précharge (téléchargement + décodage, en priorité basse) les images qui vont apparaître au
 * prochain défilement : quand la ligne arrive à l'écran, l'image sort déjà du cache mémoire.
 */
internal suspend fun prefetchArtwork(context: Context, urls: List<String?>, logo: Boolean) {
    val maxPx = if (logo) LOGO_DECODE_PX else ARTWORK_DECODE_PX
    ArtworkLoader.prefetch(context.applicationContext, urls, maxPx, opaque = !logo)
}

private object ArtworkLoader {
    // Dimensionné en octets réels (⅛ du tas max) plutôt qu'en nombre d'entrées.
    private val cache = object : LruCache<String, ImageBitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }
    // Au plus 6 téléchargements/décodages simultanés (au lieu de jusqu'à 64 threads IO vers le même
    // fournisseur) : les éléments visibles passent avant, les autres attendent ou sont annulés.
    private val permits = Semaphore(6)
    // Préchargement : 2 à la fois au plus, jamais au détriment des images visibles.
    private val prefetchPermits = Semaphore(2)

    /**
     * URL en échec (404, logo mort, serveur injoignable — très fréquent dans les playlists IPTV) :
     * pas de nouvelle requête à chaque réapparition de la ligne pendant [FAILURE_TTL_MS], ni avant
     * le prochain retour du réseau (numéro de reconnexion différent).
     */
    private class Failure(val atMs: Long, val reconnection: Int)
    private val failures = LruCache<String, Failure>(MAX_TRACKED_FAILURES)

    private fun cacheKey(url: String, maxPx: Int) = "$maxPx|$url"

    fun trim(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> cache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> cache.trimToSize(cache.maxSize() / 2)
        }
    }

    fun get(url: String?, maxPx: Int): ImageBitmap? = url?.takeIf(String::isNotBlank)?.let { cache.get(cacheKey(it, maxPx)) }

    private fun recentlyFailed(url: String, reconnection: Int): Boolean {
        val failure = failures.get(url) ?: return false
        val fresh = android.os.SystemClock.elapsedRealtime() - failure.atMs < FAILURE_TTL_MS
        return fresh && failure.reconnection == reconnection
    }

    /** Chargements en cours par image : les demandes identiques attendent le même résultat. */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()

    /**
     * Même logo demandé par plusieurs éléments à l'écran (chaînes d'un même bouquet, logo de
     * catégorie) : un seul téléchargement et un seul décodage, partagés. Si l'élément qui charge
     * quitte l'écran, ceux qui attendaient relancent le chargement eux-mêmes.
     */
    suspend fun load(context: Context, url: String, maxPx: Int, opaque: Boolean, reconnection: Int = 0): ImageBitmap? {
        get(url, maxPx)?.let { return it }
        if (recentlyFailed(url, reconnection)) return null
        val key = cacheKey(url, maxPx)
        val mine = CompletableDeferred<ImageBitmap?>()
        val pending = inFlight.putIfAbsent(key, mine)
        if (pending != null) {
            val shared = try {
                pending.await()
            } catch (cancelled: CancellationException) {
                // Annulation du chargement partagé (et non de cette attente) : on charge soi-même.
                currentCoroutineContext().ensureActive()
                null
            }
            return shared ?: get(url, maxPx) ?: if (recentlyFailed(url, reconnection)) null else loadNow(context, url, maxPx, opaque, reconnection, permits)
        }
        return try {
            loadNow(context, url, maxPx, opaque, reconnection, permits).also { mine.complete(it) }
        } catch (error: Throwable) {
            mine.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(key, mine)
        }
    }

    suspend fun prefetch(context: Context, urls: List<String?>, maxPx: Int, opaque: Boolean) {
        for (url in urls) {
            if (url.isNullOrBlank() || get(url, maxPx) != null || recentlyFailed(url, 0)) continue
            if (inFlight.containsKey(cacheKey(url, maxPx))) continue
            currentCoroutineContext().ensureActive()
            loadNow(context, url, maxPx, opaque, reconnection = 0, gate = prefetchPermits)
        }
    }

    private suspend fun loadNow(
        context: Context,
        url: String,
        maxPx: Int,
        opaque: Boolean,
        reconnection: Int,
        gate: Semaphore,
    ): ImageBitmap? =
        gate.withPermit {
            get(url, maxPx)?.let { return@withPermit it }
            withContext(Dispatchers.IO) {
                val decoded = download(fr.streamia.tv.net.HttpClients.artwork(context), url, maxPx, opaque)
                if (decoded == null) {
                    failures.put(url, Failure(android.os.SystemClock.elapsedRealtime(), reconnection))
                } else {
                    failures.remove(url)
                    cache.put(cacheKey(url, maxPx), decoded)
                }
                decoded
            }
        }

    private fun download(client: OkHttpClient, url: String, maxPx: Int, opaque: Boolean): ImageBitmap? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", fr.streamia.tv.net.HttpClients.USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val bytes = response.body?.bytes() ?: return@runCatching null
            decode(bytes, maxPx, opaque, hardware = HARDWARE_BITMAPS)
                ?: if (HARDWARE_BITMAPS) decode(bytes, maxPx, opaque, hardware = false) else null
        }
    }.getOrNull()

    /**
     * Décodage à la taille d'affichage : sous-échantillonnage par puissance de 2 puis mise à
     * l'échelle exacte pendant le décodage (plus d'image jusqu'à 2 fois trop grande). Sur Android 9+,
     * bitmap matérielle : ses pixels vivent dans la mémoire graphique, et l'image n'est plus
     * téléversée vers le GPU au premier dessin pendant le défilement.
     */
    private fun decode(bytes: ByteArray, maxPx: Int, opaque: Boolean, hardware: Boolean): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        if (largest <= 0) return null
        var sample = 1
        while (largest / (sample * 2) >= maxPx) sample *= 2
        val sampled = largest / sample
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                if (sampled > maxPx) {
                    inScaled = true
                    inDensity = sampled
                    inTargetDensity = maxPx
                }
                inPreferredConfig = when {
                    hardware && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P -> Bitmap.Config.HARDWARE
                    // Affiches/vignettes recadrées : pas de transparence utile, moitié de mémoire.
                    opaque -> Bitmap.Config.RGB_565
                    else -> Bitmap.Config.ARGB_8888
                }
            },
        )?.asImageBitmap()
    }

    private fun cacheSizeBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceIn(4L * 1024 * 1024, Int.MAX_VALUE.toLong()).toInt()

    private val HARDWARE_BITMAPS = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
    private const val FAILURE_TTL_MS = 30 * 60_000L
    private const val MAX_TRACKED_FAILURES = 2_000
}

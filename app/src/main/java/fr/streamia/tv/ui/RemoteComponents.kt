package fr.streamia.tv.ui

import android.graphics.Bitmap
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import android.graphics.BitmapFactory
import android.content.Context
import android.util.LruCache
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import fr.streamia.tv.R
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkLight
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlue
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.KickerLetterSpacing
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RadiusTile
import fr.streamia.tv.ui.theme.RaisedSurface
import fr.streamia.tv.ui.theme.TypeSectionTitle
import kotlinx.coroutines.Dispatchers
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    // Permet à un panneau affiché par-dessus une vidéo plein écran (ex. catégories/chaînes du
    // Direct) de rendre ses lignes au repos transparentes — seul le focus/la sélection reste plein
    // — sans changer l'aspect opaque par défaut des autres écrans (Accueil, VOD, Organiser…).
    idleBackground: Color = DeepSurface,
    // Action principale (équivalent `.btn-accent` du prototype) : pilule dégradé accent avec
    // lueur permanente, plutôt que le remplissage verre neutre des autres tuiles.
    accent: Boolean = false,
    // Par défaut la surface se déploie sur toute la largeur qui lui est offerte (lignes de listes,
    // tuiles). Mis à `true`, elle se cintre à son contenu : indispensable dans un FlowRow, où un
    // `fillMaxSize()` interne forçait sinon chaque « chip » à occuper toute une ligne.
    wrapContent: Boolean = false,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longPressConsumed by remember { mutableStateOf(false) }
    // Le focus doit rester visible depuis l'autre bout du salon : un agrandissement net, une
    // lueur accent (pas seulement un changement de teinte) et une bordure large — jamais un
    // simple aplat de couleur — voir PRODUCT.md § Accessibilité & inclusion.
    // Animation courte (appui maintenu = focus qui défile vite) et limitée au scale, appliqué en
    // graphicsLayer : bon marché. Pas d'ombre au repos ni d'élévation animée — une ombre animée
    // force son re-rendu à chaque image, et une ombre par ligne dans des listes de centaines de
    // chaînes suffisait à faire saccader les petits GPU des boîtiers TV.
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, animationSpec = tween(110), label = "focus-scale")
    val elevation = when {
        focused -> 18.dp
        accent -> 10.dp
        else -> 0.dp
    }
    val shape = RoundedCornerShape(RadiusTile)
    val background = when {
        focused -> FocusBlue
        selected -> RaisedSurface
        else -> idleBackground
    }
    val border = when {
        focused -> BorderStroke(3.dp, FocusBlueBright)
        accent -> BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
        selected -> BorderStroke(2.dp, FocusBlue)
        else -> BorderStroke(1.dp, GlassBorder)
    }
    val glowColor = when {
        focused -> AccentPink.copy(alpha = 0.55f)
        accent -> AccentPink.copy(alpha = 0.45f)
        else -> Color.Transparent
    }

    // Le scale/l'ombre de focus sont appliqués à une Box interne, jamais à `modifier` lui-même :
    // Modifier.scale() fait grandir les bounds vus par le système de focus (boundsInParent inclut
    // les graphicsLayer), donc les mettre sur le nœud focusable faisait grandir sa zone à chaque
    // frame de l'animation et déclenchait un bringIntoView vertical de la LazyColumn parente à
    // chaque changement de focus horizontal dans une LazyRow — d'où le tremblement de tout l'écran
    // en se déplaçant entre les cards. La Box externe (focus/clic) garde une taille fixe ; seule la
    // Box interne se redimensionne visuellement.
    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .onPreviewKeyEvent { composeEvent ->
                val longAction = onLongClick ?: return@onPreviewKeyEvent false
                if (!enabled) return@onPreviewKeyEvent false
                val event = composeEvent.nativeKeyEvent
                if (!event.isTvSelectKey()) return@onPreviewKeyEvent false

                when {
                    event.action == AndroidKeyEvent.ACTION_DOWN && event.repeatCount > 0 && !longPressConsumed -> {
                        longPressConsumed = true
                        longAction()
                        true
                    }
                    event.action == AndroidKeyEvent.ACTION_DOWN && longPressConsumed -> true
                    event.action == AndroidKeyEvent.ACTION_UP && longPressConsumed -> {
                        longPressConsumed = false
                        true
                    }
                    event.action == AndroidKeyEvent.ACTION_UP -> {
                        longPressConsumed = false
                        false
                    }
                    else -> false
                }
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .focusable(enabled)
            .then(
                if (contentDescription == null) Modifier
                else Modifier.semantics { this.contentDescription = contentDescription },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .then(if (wrapContent) Modifier else Modifier.fillMaxSize())
                .scale(scale)
                .then(
                    if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false, ambientColor = glowColor, spotColor = glowColor)
                    else Modifier,
                )
                .clip(shape)
                .then(
                    if (accent) {
                        Modifier.background(Brush.verticalGradient(listOf(AccentPinkLight, AccentPink)), shape)
                    } else {
                        Modifier.background(background, shape)
                    },
                )
                .border(border, shape),
            contentAlignment = Alignment.CenterStart,
        ) {
            content()
        }
    }
}

private fun AndroidKeyEvent.isTvSelectKey(): Boolean = when (keyCode) {
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
    AndroidKeyEvent.KEYCODE_BUTTON_A,
    -> true
    else -> false
}

@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val fieldBackground = if (enabled) RaisedSurface else DeepSurface.copy(alpha = 0.5f)
    val textColor = if (enabled) Ink else MutedInk.copy(alpha = 0.66f)
    val labelColor = when {
        !enabled -> MutedInk.copy(alpha = 0.55f)
        focused -> FocusBlueBright
        else -> MutedInk
    }
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        visualTransformation = visualTransformation,
        textStyle = TextStyle(color = textColor, fontSize = 19.sp, fontWeight = FontWeight.Medium),
        modifier = modifier
            .height(if (supportingText == null) 68.dp else 88.dp)
            .clip(shape)
            .background(fieldBackground)
            .border(
                if (focused && enabled) 3.dp else 1.dp,
                if (focused && enabled) FocusBlueBright else GlassBorder,
                shape,
            )
            .onFocusChanged { focused = enabled && it.isFocused }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        decorationBox = { input ->
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.Center) {
                Text(label, color = labelColor, fontSize = 14.sp)
                Spacer(Modifier.height(3.dp))
                input()
                if (supportingText != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(supportingText, color = if (enabled) MutedInk else MutedInk.copy(alpha = 0.55f), fontSize = 12.sp)
                }
            }
        },
    )
}

@Composable
fun StreamiaLogo(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.streamia_logo_mark),
            contentDescription = "Logo Streamia TV",
            modifier = Modifier.size(if (compact) 42.dp else 58.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.width(if (compact) 12.dp else 16.dp))
        Text(
            text = "Streamia TV",
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            color = Ink,
            fontWeight = HeadingWeight,
        )
    }
}

/**
 * Étiquette au-dessus d'une liste ou d'une rangée ("Catégories", "Chaînes", "Reprendre la
 * lecture"…) : capitales espacées sur un ton neutre plutôt qu'un titre plein, pour la distinguer
 * du contenu qu'elle introduit — celui-ci garde sa pleine lisibilité, seule l'étiquette s'efface.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = TypeSectionTitle) {
    Text(
        text.asKickerLabel(),
        color = MutedInk,
        fontSize = fontSize,
        fontWeight = HeadingWeight,
        letterSpacing = KickerLetterSpacing,
        modifier = modifier,
    )
}

// En dehors du composable : lire une locale directement dans un @Composable n'est pas observable
// par la recomposition (Lint : "Reading locale in a non-observable way"). Locale.FRENCH plutôt que
// getDefault() : l'app n'affiche que du texte français (androidResources.localeFilters = "fr"),
// donc la casse ne doit pas dépendre de la locale système de l'appareil.
private fun String.asKickerLabel(): String = uppercase(java.util.Locale.FRENCH)

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
    // produceState est annulé quand l'élément quitte l'écran : un élément dépassé pendant un
    // défilement rapide abandonne sa place dans la file au lieu de retarder les logos visibles.
    val bitmap by produceState<ImageBitmap?>(initialValue = ArtworkLoader.get(url, maxDecodePx), key1 = url) {
        if (url.isNullOrBlank() || value != null) return@produceState
        value = ArtworkLoader.load(context, url, maxDecodePx, opaque)
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

private object ArtworkLoader {
    // Dimensionné en octets réels (⅛ du tas max) plutôt qu'en nombre d'entrées.
    private val cache = object : LruCache<String, ImageBitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }
    // Au plus 6 téléchargements/décodages simultanés (au lieu de jusqu'à 64 threads IO vers le même
    // fournisseur) : les éléments visibles passent avant, les autres attendent ou sont annulés.
    private val permits = Semaphore(6)
    @Volatile private var client: OkHttpClient? = null

    private fun cacheKey(url: String, maxPx: Int) = "$maxPx|$url"

    fun get(url: String?, maxPx: Int): ImageBitmap? = url?.takeIf(String::isNotBlank)?.let { cache.get(cacheKey(it, maxPx)) }

    suspend fun load(context: Context, url: String, maxPx: Int, opaque: Boolean): ImageBitmap? =
        permits.withPermit {
            get(url, maxPx)?.let { return@withPermit it }
            withContext(Dispatchers.IO) {
                download(client(context), url, maxPx, opaque)?.also { cache.put(cacheKey(url, maxPx), it) }
            }
        }

    @Synchronized
    private fun client(context: Context): OkHttpClient = client ?: OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "artwork-http"), 64L * 1024L * 1024L))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
        .also { client = it }

    private fun download(client: OkHttpClient, url: String, maxPx: Int, opaque: Boolean): ImageBitmap? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", "Streamia-TV/1.5").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val bytes = response.body?.bytes() ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    // Affiches/vignettes recadrées : pas de transparence utile, moitié de mémoire.
                    if (opaque) inPreferredConfig = Bitmap.Config.RGB_565
                },
            )?.asImageBitmap()
        }
    }.getOrNull()

    private fun cacheSizeBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceIn(4L * 1024 * 1024, Int.MAX_VALUE.toLong()).toInt()
}

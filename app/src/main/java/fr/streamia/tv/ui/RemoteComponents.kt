package fr.streamia.tv.ui

import android.graphics.BitmapFactory
import android.content.Context
import android.util.LruCache
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlue
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RaisedSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longPressConsumed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.035f else 1f, label = "focus-scale")
    val shape = RoundedCornerShape(12.dp)
    val background = when {
        focused -> FocusBlue
        selected -> RaisedSurface
        else -> DeepSurface
    }
    val border = when {
        focused -> BorderStroke(3.dp, FocusBlueBright)
        selected -> BorderStroke(2.dp, FocusBlue)
        else -> BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(background)
            .border(border, shape)
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
        content()
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
    val shape = RoundedCornerShape(10.dp)
    val fieldBackground = if (enabled) RaisedSurface else DeepSurface.copy(alpha = 0.72f)
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
                if (focused && enabled) FocusBlueBright else Color.White.copy(if (enabled) 0.12f else 0.05f),
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
        Box(
            Modifier
                .size(if (compact) 42.dp else 58.dp)
                .drawBehind {
                    drawCircle(FocusBlue, radius = size.minDimension / 2)
                    val w = size.width
                    val h = size.height
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.40f, h * 0.29f)
                        lineTo(w * 0.73f, h * 0.50f)
                        lineTo(w * 0.40f, h * 0.71f)
                        close()
                    }
                    drawPath(path, color = Ink)
                    drawArc(
                        color = FocusBlueBright,
                        startAngle = -58f,
                        sweepAngle = 116f,
                        useCenter = false,
                        topLeft = Offset(w * 0.10f, h * 0.10f),
                        size = Size(w * 0.80f, h * 0.80f),
                        style = Stroke(width = w * 0.055f),
                    )
                },
        )
        Spacer(Modifier.width(if (compact) 12.dp else 16.dp))
        Text(
            text = "Streamia TV",
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            color = Ink,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun ChannelLogo(url: String?, channelName: String, modifier: Modifier = Modifier) {
    RemoteArtwork(
        url = url,
        name = channelName,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        imagePadding = 8,
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
    )
}

@Composable
private fun RemoteArtwork(
    url: String?,
    name: String,
    modifier: Modifier,
    contentScale: ContentScale,
    imagePadding: Int,
) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = url) {
        value = ArtworkLoader.get(url)
        if (url.isNullOrBlank() || value != null) return@produceState
        value = ArtworkLoader.load(context, url)
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
    private val cache = LruCache<String, ImageBitmap>(128)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    @Volatile private var client: OkHttpClient? = null

    fun get(url: String?): ImageBitmap? = url?.let(cache::get)

    suspend fun load(context: Context, url: String): ImageBitmap? {
        get(url)?.let { return it }
        val deferred = inFlight.computeIfAbsent(url) {
            scope.async { download(client(context), url)?.also { cache.put(url, it) } }
        }
        return try {
            deferred.await()
        } finally {
            inFlight.remove(url, deferred)
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

    private fun download(client: OkHttpClient, url: String): ImageBitmap? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", "Streamia-TV/1.5").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val bytes = response.body?.bytes() ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
            )?.asImageBitmap()
        }
    }.getOrNull()
}

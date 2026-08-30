package fr.streamia.tv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.XtreamUrlBuilder
import fr.streamia.tv.ui.theme.DeepSurface
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun PlayerScreen(
    catalog: Catalog,
    credentials: ServerCredentials,
    entry: MediaEntry,
    epg: List<EpgProgram>,
    onBack: () -> Unit,
    onZap: (Int) -> Unit,
    onEntrySelected: (MediaEntry) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    val rootFocus = remember { FocusRequester() }
    var guideOpen by remember { mutableStateOf(false) }
    var hudVisible by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = "Cette chaîne ne peut pas être lue pour le moment."
                buffering = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(entry.key) {
        playbackError = null
        buffering = true
        hudVisible = true
        val streamUrl = XtreamUrlBuilder(credentials).stream(entry)
        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()
        player.play()
    }

    LaunchedEffect(Unit) { rootFocus.requestFocus() }
    LaunchedEffect(hudVisible, guideOpen, entry.key) {
        if (hudVisible && !guideOpen) {
            delay(4_000)
            hudVisible = false
        }
    }

    BackHandler {
        if (guideOpen) guideOpen = false else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || guideOpen) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP,
                    AndroidKeyEvent.KEYCODE_DPAD_UP,
                    -> if (entry.type == MediaType.Live) { onZap(-1); true } else false

                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN,
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                    -> if (entry.type == MediaType.Live) { onZap(1); true } else false

                    AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                    AndroidKeyEvent.KEYCODE_MENU,
                    -> if (entry.type == MediaType.Live) { guideOpen = true; hudVisible = true; true } else false

                    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    AndroidKeyEvent.KEYCODE_ENTER,
                    -> { hudVisible = !hudVisible; true }

                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (player.isPlaying) player.pause() else player.play()
                        hudVisible = true
                        true
                    }

                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("●", color = FocusBlueBright, fontSize = 34.sp)
                Spacer(Modifier.height(12.dp))
                Text("Chargement de ${entry.displayName}…", color = Ink, fontSize = 18.sp)
            }
        }

        if (playbackError != null) {
            FocusableSurface(
                onClick = {
                    playbackError = null
                    player.prepare()
                    player.play()
                },
                modifier = Modifier.align(Alignment.Center).width(560.dp).height(112.dp),
            ) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(playbackError!!, color = MaterialTheme.colorScheme.error, fontSize = 18.sp)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        if (entry.type == MediaType.Live) "OK pour réessayer · ↑ ↓ pour changer de chaîne" else "OK pour réessayer",
                        color = MutedInk,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        if (hudVisible && !guideOpen) {
            PlayerHud(entry = entry, epg = epg, isPlaying = player.isPlaying, modifier = Modifier.fillMaxSize())
        }

        if (guideOpen) {
            PlayerGuide(
                catalog = catalog,
                currentEntry = entry,
                onEntrySelected = {
                    onEntrySelected(it)
                    guideOpen = false
                    rootFocus.requestFocus()
                },
                onClose = {
                    guideOpen = false
                    rootFocus.requestFocus()
                },
            )
        }
    }
}

@Composable
private fun PlayerHud(
    entry: MediaEntry,
    epg: List<EpgProgram>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(36.dp)
                .background(Night.copy(alpha = 0.94f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(entry.iconUrl, entry.displayName, Modifier.size(58.dp))
            Spacer(Modifier.width(15.dp))
            Column {
                Text(entry.displayName, color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                val subtitle = if (entry.type == MediaType.Live) {
                    epg.firstOrNull()?.title ?: "Chaîne ${entry.number}"
                } else {
                    entry.type.displayName
                }
                Text(subtitle, color = FocusBlueBright, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp)
                .background(Night.copy(alpha = 0.94f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .padding(horizontal = 22.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (isPlaying) "⏸ Lecture" else "▶ Pause", color = Ink, fontSize = 15.sp)
            Spacer(Modifier.width(28.dp))
            if (entry.type == MediaType.Live) {
                Text("↑ ↓ Changer de chaîne", color = MutedInk, fontSize = 15.sp)
                Spacer(Modifier.width(28.dp))
                Text("← Guide", color = MutedInk, fontSize = 15.sp)
                Spacer(Modifier.width(28.dp))
            }
            Text("Retour Quitter", color = MutedInk, fontSize = 15.sp)
        }
    }
}

@Composable
private fun PlayerGuide(
    catalog: Catalog,
    currentEntry: MediaEntry,
    onEntrySelected: (MediaEntry) -> Unit,
    onClose: () -> Unit,
) {
    val categories = remember(catalog) {
        listOf(Catalog.allCategory(MediaType.Live)) + catalog.categoriesFor(MediaType.Live)
    }
    var selectedCategoryId by remember(currentEntry.categoryId) { mutableStateOf(currentEntry.categoryId) }
    val channels = remember(catalog, selectedCategoryId) {
        catalog.entriesIn(MediaType.Live, selectedCategoryId)
    }
    val firstFocus = remember(selectedCategoryId) { FocusRequester() }

    LaunchedEffect(selectedCategoryId, channels.size) {
        if (channels.isNotEmpty()) {
            yield()
            runCatching { firstFocus.requestFocus() }
        }
    }

    Row(
        Modifier
            .fillMaxHeight()
            .width(790.dp)
            .background(Night.copy(alpha = 0.98f))
            .padding(24.dp),
    ) {
        Column(Modifier.width(285.dp).fillMaxHeight()) {
            Text("Catégories", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories, key = { it.key }) { category ->
                    FocusableSurface(
                        onClick = { selectedCategoryId = category.id },
                        selected = selectedCategoryId == category.id,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text(
                            category.name,
                            color = Ink,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Chaînes", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                FocusableSurface(onClick = onClose, modifier = Modifier.width(110.dp).height(48.dp)) {
                    Text("Fermer", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 15.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(channels, key = { it.key }) { guideChannel ->
                    FocusableSurface(
                        onClick = { onEntrySelected(guideChannel) },
                        selected = guideChannel.key == currentEntry.key,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(70.dp)
                            .then(if (guideChannel.key == channels.firstOrNull()?.key) Modifier.focusRequester(firstFocus) else Modifier),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChannelLogo(guideChannel.iconUrl, guideChannel.name, Modifier.size(48.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                guideChannel.name,
                                color = Ink,
                                fontSize = 16.sp,
                                fontWeight = if (guideChannel.key == currentEntry.key) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(guideChannel.number.toString(), color = MutedInk, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

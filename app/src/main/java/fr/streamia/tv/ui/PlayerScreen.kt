package fr.streamia.tv.ui

import android.os.SystemClock
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
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
import fr.streamia.tv.player.DolbyCapabilityDetector
import fr.streamia.tv.player.PlaybackDiagnostics
import fr.streamia.tv.player.PlaybackDiagnosticsTracker
import fr.streamia.tv.player.PlaybackRemoteAction
import fr.streamia.tv.player.PlaybackRemoteButton
import fr.streamia.tv.player.PlaybackTransportStore
import fr.streamia.tv.player.PlaybackTrackPreferenceStore
import fr.streamia.tv.player.PlaybackUrlStrategy
import fr.streamia.tv.player.StreamTechnicalInfo
import fr.streamia.tv.player.StreamiaPlayerFactory
import fr.streamia.tv.player.codecLabel
import fr.streamia.tv.player.dolbyPlaybackLabel
import fr.streamia.tv.player.hdrLabel
import fr.streamia.tv.player.isDolbyAtmosFormat
import fr.streamia.tv.player.isDolbyVisionFormat
import fr.streamia.tv.player.playbackRemoteAction
import fr.streamia.tv.player.resolveSeekPosition
import fr.streamia.tv.player.LivePlaybackSession
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class VideoAspect(val label: String, val resizeMode: Int) {
    Fit("Ajuster", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    Fill("Remplir", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    Zoom("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
}

internal data class TrackChoice(val label: String, val language: String?)

private const val VOD_SEEK_STEP_MS = 10_000L

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun PlayerScreen(
    catalog: Catalog,
    credentials: ServerCredentials,
    entry: MediaEntry,
    epg: List<EpgProgram>,
    resumePositionMs: Long,
    livePlaybackSession: LivePlaybackSession,
    liveVideoSurface: @Composable (LiveVideoSurfacePlacement) -> Unit,
    onBack: () -> Unit,
    onZap: (Int) -> Unit,
    onEntrySelected: (MediaEntry) -> Unit,
    onProgress: (MediaEntry, Long, Long) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedLivePlayer = entry.type == MediaType.Live
    val player = remember(entry.type, livePlaybackSession) {
        if (sharedLivePlayer) livePlaybackSession.player else StreamiaPlayerFactory.create(context.applicationContext, entry.type)
    }
    val transportStore = remember { PlaybackTransportStore(context.applicationContext) }
    val trackPreferenceStore = remember { PlaybackTrackPreferenceStore(context.applicationContext) }
    val diagnosticsTracker = remember { PlaybackDiagnosticsTracker() }
    val dolbyCapabilities = remember { DolbyCapabilityDetector.detect(context.applicationContext) }
    val livePickerOpen by PlayerOverlayController.livePickerOpen.collectAsStateWithLifecycle()
    val rootFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }

    var guideOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var hudVisible by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var streamCandidates by remember { mutableStateOf(emptyList<String>()) }
    var candidateIndex by remember { mutableStateOf(0) }
    var activeStreamUrl by remember { mutableStateOf("") }
    var numberBuffer by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf(VideoAspect.Fit) }
    var audioTracks by remember { mutableStateOf(listOf(TrackChoice("Auto", null))) }
    var subtitleTracks by remember { mutableStateOf(listOf(TrackChoice("Désactivés", null))) }
    var audioIndex by remember { mutableStateOf(0) }
    var subtitleIndex by remember { mutableStateOf(0) }
    var trackPreferencesApplied by remember(entry.key) { mutableStateOf(false) }
    var technicalInfo by remember { mutableStateOf(StreamTechnicalInfo()) }
    var diagnostics by remember { mutableStateOf(PlaybackDiagnostics()) }
    var dolbyVisionDetected by remember { mutableStateOf(false) }
    var dolbyAtmosDetected by remember { mutableStateOf(false) }
    var seekFeedback by remember(entry.key) { mutableStateOf<String?>(null) }
    var positionMs by remember(entry.key) { mutableStateOf(0L) }
    var durationMs by remember(entry.key) { mutableStateOf(0L) }

    fun startCandidate(url: String, positionMs: Long = 0L) {
        activeStreamUrl = url
        playbackError = null
        buffering = true
        if (sharedLivePlayer) {
            livePlaybackSession.playUrl(entry.key, url)
            return
        }
        runCatching {
            player.stop()
            player.setMediaItem(MediaItem.fromUri(url))
            if (positionMs > 0 && entry.type != MediaType.Live) player.seekTo(positionMs)
            player.prepare()
            player.play()
        }.onFailure {
            buffering = false
            playbackError = "Ce contenu ne peut pas être démarré pour le moment."
        }
    }

    DisposableEffect(player) {
        onDispose { if (!sharedLivePlayer) player.release() }
    }

    DisposableEffect(player, entry.key) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val now = SystemClock.elapsedRealtime()
                when (playbackState) {
                    Player.STATE_BUFFERING,
                    Player.STATE_IDLE,
                    -> {
                        buffering = true
                        diagnosticsTracker.onBufferingStarted(now)
                    }
                    Player.STATE_READY,
                    Player.STATE_ENDED,
                    -> {
                        buffering = false
                        diagnosticsTracker.onBufferingEnded(now)
                    }
                }
                diagnostics = diagnosticsTracker.snapshot(now)
            }

            override fun onRenderedFirstFrame() {
                val now = SystemClock.elapsedRealtime()
                diagnosticsTracker.onFirstFrame(now)
                diagnosticsTracker.onBufferingEnded(now)
                diagnostics = diagnosticsTracker.snapshot(now)
                buffering = false
                transportStore.recordSuccess(activeStreamUrl, entry.type)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                technicalInfo = technicalInfo.copy(width = videoSize.width, height = videoSize.height)
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (sharedLivePlayer) livePlaybackSession.recoverAudio(tracks)
                audioTracks = listOf(TrackChoice("Auto", null)) + extractChoices(tracks, C.TRACK_TYPE_AUDIO)
                subtitleTracks = listOf(TrackChoice("Désactivés", null)) + extractChoices(tracks, C.TRACK_TYPE_TEXT)
                if (!trackPreferencesApplied) {
                    val saved = trackPreferenceStore.load()
                    audioIndex = audioTracks.indexOfFirst { it.language == saved.audioLanguage }.coerceAtLeast(0)
                    subtitleIndex = subtitleTracks.indexOfFirst { it.language == saved.subtitleLanguage }.coerceAtLeast(0)
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setPreferredAudioLanguage(audioTracks[audioIndex].language)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleTracks[subtitleIndex].language == null)
                        .setPreferredTextLanguage(subtitleTracks[subtitleIndex].language)
                        .build()
                    trackPreferencesApplied = true
                } else {
                    audioIndex = audioIndex.coerceIn(0, audioTracks.lastIndex.coerceAtLeast(0))
                    subtitleIndex = subtitleIndex.coerceIn(0, subtitleTracks.lastIndex.coerceAtLeast(0))
                }

                selectedVideoFormat(tracks)?.let { format ->
                    val isDolbyVision = isDolbyVisionFormat(format.sampleMimeType, format.codecs)
                    dolbyVisionDetected = isDolbyVision
                    technicalInfo = technicalInfo.copy(
                        width = format.width.takeIf { it > 0 } ?: technicalInfo.width,
                        height = format.height.takeIf { it > 0 } ?: technicalInfo.height,
                        frameRate = format.frameRate.takeIf { it > 0f },
                        codec = if (isDolbyVision) "Dolby Vision" else codecLabel(format.sampleMimeType, format.codecs),
                        bitrate = format.bitrate.takeIf { it > 0 },
                        hdr = if (isDolbyVision) "Dolby Vision" else hdrLabel(format.sampleMimeType, format.colorInfo?.colorTransfer),
                    )
                } ?: run { dolbyVisionDetected = false }

                selectedAudioFormat(tracks)?.let { format ->
                    dolbyAtmosDetected = isDolbyAtmosFormat(format.sampleMimeType)
                } ?: run { dolbyAtmosDetected = false }
            }

            override fun onPlayerError(error: PlaybackException) {
                val next = candidateIndex + 1
                if (next < streamCandidates.size) {
                    val previousPosition = player.currentPosition.coerceAtLeast(0L)
                    candidateIndex = next
                    startCandidate(streamCandidates[next], previousPosition)
                    return
                }
                playbackError = "Ce contenu ne peut pas être lu pour le moment."
                buffering = false
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    DisposableEffect(entry.key) {
        onDispose {
            runCatching {
                val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                onProgress(entry, player.currentPosition.coerceAtLeast(0), duration)
            }
        }
    }

    LaunchedEffect(entry.key, credentials) {
        playbackError = null
        buffering = true
        hudVisible = true
        numberBuffer = ""
        technicalInfo = StreamTechnicalInfo()
        dolbyVisionDetected = false
        dolbyAtmosDetected = false
        diagnosticsTracker.reset(SystemClock.elapsedRealtime())
        diagnostics = diagnosticsTracker.snapshot(SystemClock.elapsedRealtime())

        val baseUrl = XtreamUrlBuilder(credentials).stream(entry)
        streamCandidates = PlaybackUrlStrategy.candidates(
            initialUrl = baseUrl,
            type = entry.type,
            preference = transportStore.preferenceFor(baseUrl),
        )
        candidateIndex = 0
        if (sharedLivePlayer && livePlaybackSession.isCurrent(entry)) {
            activeStreamUrl = livePlaybackSession.activeUrl
            buffering = player.playbackState != Player.STATE_READY
            player.play()
        } else {
            val url = streamCandidates.firstOrNull() ?: baseUrl
            startCandidate(url, resumePositionMs)
        }
    }

    LaunchedEffect(entry.key) {
        if (entry.type == MediaType.Live) {
            delay(2_500)
            onProgress(entry, 0L, 0L)
        } else {
            var lastSavedAt = 0L
            while (true) {
                delay(1_000)
                val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = duration
                val now = SystemClock.elapsedRealtime()
                if (positionMs > 0 && now - lastSavedAt >= 15_000) {
                    lastSavedAt = now
                    onProgress(entry, positionMs, duration)
                }
            }
        }
    }

    LaunchedEffect(numberBuffer) {
        if (numberBuffer.isBlank()) return@LaunchedEffect
        delay(1_250)
        val number = numberBuffer.toIntOrNull()
        numberBuffer = ""
        if (number != null) {
            catalog.entriesFor(MediaType.Live).firstOrNull { it.number == number }?.let(onEntrySelected)
        }
    }

    LaunchedEffect(seekFeedback) {
        if (seekFeedback == null) return@LaunchedEffect
        delay(1_100)
        seekFeedback = null
    }

    LaunchedEffect(Unit) { rootFocus.requestFocus() }
    LaunchedEffect(settingsOpen) {
        if (settingsOpen) {
            yield()
            runCatching { settingsFocus.requestFocus() }
        }
    }
    LaunchedEffect(hudVisible, guideOpen, settingsOpen, entry.key, livePickerOpen) {
        if (hudVisible && !guideOpen && !settingsOpen && !livePickerOpen) {
            delay(6_000)
            hudVisible = false
        }
    }

    fun applyAudio(choice: TrackChoice) {
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setPreferredAudioLanguage(choice.language)
        player.trackSelectionParameters = builder.build()
    }

    fun applySubtitle(choice: TrackChoice) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (choice.language == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setPreferredTextLanguage(choice.language)
        }
        player.trackSelectionParameters = builder.build()
    }

    BackHandler {
        when {
            settingsOpen -> { settingsOpen = false; rootFocus.requestFocus() }
            guideOpen -> { guideOpen = false; rootFocus.requestFocus() }
            else -> onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || guideOpen || settingsOpen || livePickerOpen) return@onPreviewKeyEvent false
                val keyCode = event.nativeKeyEvent.keyCode
                val digit = keyCode.toTvDigit()
                if (digit != null && entry.type == MediaType.Live) {
                    numberBuffer = (numberBuffer + digit).takeLast(5)
                    hudVisible = true
                    return@onPreviewKeyEvent true
                }
                val remoteAction = playbackRemoteAction(entry.type, keyCode.toPlaybackRemoteButton())
                when (remoteAction) {
                    PlaybackRemoteAction.ZapPrevious -> { onZap(-1); true }
                    PlaybackRemoteAction.ZapNext -> { onZap(1); true }
                    PlaybackRemoteAction.OpenLivePicker -> {
                        PlayerOverlayController.openLivePicker()
                        hudVisible = false
                        true
                    }
                    PlaybackRemoteAction.OpenSettings -> { settingsOpen = true; hudVisible = true; true }
                    PlaybackRemoteAction.ToggleHud -> { hudVisible = true; true }
                    PlaybackRemoteAction.TogglePlayback -> {
                        if (player.isPlaying) player.pause() else player.play()
                        hudVisible = true
                        true
                    }
                    PlaybackRemoteAction.SeekBackward,
                    PlaybackRemoteAction.SeekForward,
                    -> {
                        val delta = if (remoteAction == PlaybackRemoteAction.SeekBackward) -VOD_SEEK_STEP_MS else VOD_SEEK_STEP_MS
                        val duration = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
                        val target = resolveSeekPosition(player.currentPosition, duration, delta)
                        player.seekTo(target)
                        positionMs = target
                        seekFeedback = if (delta < 0L) "−10 s" else "+10 s"
                        hudVisible = true
                        true
                    }
                    PlaybackRemoteAction.None -> false
                }
            },
    ) {
        if (sharedLivePlayer) {
            liveVideoSurface(LiveVideoSurfacePlacement(Modifier.fillMaxSize(), aspect.resizeMode))
        } else {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        keepScreenOn = true
                        this.player = player
                    }
                },
                update = {
                    it.player = player
                    it.resizeMode = aspect.resizeMode
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (buffering) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("●", color = FocusBlueBright, fontSize = 34.sp)
                Spacer(Modifier.height(12.dp))
                Text("Chargement de ${entry.displayName}…", color = Ink, fontSize = 18.sp)
                if (resumePositionMs > 0 && entry.type != MediaType.Live) {
                    Spacer(Modifier.height(6.dp))
                    Text("Reprise de la lecture", color = MutedInk, fontSize = 13.sp)
                }
            }
        }

        if (playbackError != null) {
            FocusableSurface(
                onClick = {
                    playbackError = null
                    candidateIndex = 0
                    val retryUrl = streamCandidates.firstOrNull() ?: activeStreamUrl
                    startCandidate(retryUrl, if (entry.type == MediaType.Live) 0L else player.currentPosition.coerceAtLeast(0L))
                },
                modifier = Modifier.align(Alignment.Center).width(560.dp).height(112.dp),
            ) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(playbackError!!, color = MaterialTheme.colorScheme.error, fontSize = 18.sp)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        if (entry.type == MediaType.Live) "OK réessayer · ↑ ↓ zapper · 0–9 numéro de chaîne" else "OK pour réessayer",
                        color = MutedInk,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        if (hudVisible && !guideOpen && !settingsOpen && !livePickerOpen) {
            PlayerInfoBand(
                entry = entry,
                categoryName = catalog.categoriesFor(entry.type).firstOrNull { it.id == entry.categoryId }?.name,
                epg = epg,
                isPlaying = player.isPlaying,
                numberBuffer = numberBuffer,
                technicalInfo = technicalInfo,
                diagnostics = diagnostics,
                transport = streamTransportLabel(activeStreamUrl),
                audioLabel = audioTracks.getOrNull(audioIndex)?.label ?: "Auto",
                subtitleLabel = subtitleTracks.getOrNull(subtitleIndex)?.label ?: "Désactivés",
                dolbyVisionLabel = dolbyPlaybackLabel("Dolby Vision", dolbyVisionDetected, dolbyCapabilities.dolbyVision),
                dolbyAtmosLabel = dolbyPlaybackLabel("Dolby Atmos", dolbyAtmosDetected, dolbyCapabilities.dolbyAtmos),
                resumePositionMs = resumePositionMs,
                positionMs = positionMs,
                durationMs = durationMs,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (numberBuffer.isNotBlank()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(34.dp)
                    .background(Night.copy(alpha = 0.96f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
            ) {
                Text(numberBuffer, color = FocusBlueBright, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
        }

        seekFeedback?.let { feedback ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Night.copy(alpha = 0.94f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MediaArtwork(entry.iconUrl, entry.displayName, Modifier.width(260.dp).aspectRatio(16f / 9f))
                Spacer(Modifier.height(8.dp))
                Text("$feedback · ${formatDuration(positionMs)}", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
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

        if (settingsOpen) {
            PlayerSettings(
                audioTracks = audioTracks,
                audioIndex = audioIndex,
                subtitleTracks = subtitleTracks,
                subtitleIndex = subtitleIndex,
                aspect = aspect,
                dolbyVisionLabel = dolbyPlaybackLabel("Dolby Vision", dolbyVisionDetected, dolbyCapabilities.dolbyVision),
                dolbyAtmosLabel = dolbyPlaybackLabel("Dolby Atmos", dolbyAtmosDetected, dolbyCapabilities.dolbyAtmos),
                firstFocus = settingsFocus,
                onAudioSelected = { selectedIndex ->
                    audioIndex = selectedIndex.coerceIn(audioTracks.indices)
                    applyAudio(audioTracks[audioIndex])
                    trackPreferenceStore.saveAudio(audioTracks[audioIndex].language)
                },
                onSubtitleSelected = { selectedIndex ->
                    subtitleIndex = selectedIndex.coerceIn(subtitleTracks.indices)
                    applySubtitle(subtitleTracks[subtitleIndex])
                    trackPreferenceStore.saveSubtitle(subtitleTracks[subtitleIndex].language)
                },
                onNextAspect = { aspect = VideoAspect.entries[(aspect.ordinal + 1) % VideoAspect.entries.size] },
                onClose = { settingsOpen = false; rootFocus.requestFocus() },
            )
        }
    }
}

@Composable
private fun PlayerInfoBand(
    entry: MediaEntry,
    categoryName: String?,
    epg: List<EpgProgram>,
    isPlaying: Boolean,
    numberBuffer: String,
    technicalInfo: StreamTechnicalInfo,
    diagnostics: PlaybackDiagnostics,
    transport: String,
    audioLabel: String,
    subtitleLabel: String,
    dolbyVisionLabel: String?,
    dolbyAtmosLabel: String?,
    resumePositionMs: Long,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 22.dp)
            .background(Night.copy(alpha = 0.96f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(entry.iconUrl, entry.displayName, Modifier.size(74.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                val prefix = if (entry.type == MediaType.Live) "${entry.number} · " else ""
                Text(
                    prefix + entry.displayName,
                    color = Ink,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                if (entry.type == MediaType.Live) {
                    val current = epg.firstOrNull()
                    if (current != null) {
                        Text(
                            listOfNotNull(current.timeRange(), current.title).joinToString(" · "),
                            color = FocusBlueBright,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        epg.getOrNull(1)?.let { next ->
                            Text(
                                "À suivre ${next.timeRange()?.let { "$it · " }.orEmpty()}${next.title}",
                                color = MutedInk,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(categoryName ?: "Direct", color = FocusBlueBright, fontSize = 13.sp)
                    }
                } else {
                    val resume = resumePositionMs.takeIf { it > 0 }?.let { " · reprise ${formatDuration(it)}" }.orEmpty()
                    Text(
                        "${entry.type.displayName}${categoryName?.let { " · $it" }.orEmpty()}$resume",
                        color = FocusBlueBright,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.plot?.takeIf(String::isNotBlank)?.let {
                        Text(it, color = MutedInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.width(20.dp))
            Column(Modifier.width(500.dp), horizontalAlignment = Alignment.End) {
                Text(
                    "${technicalInfo.qualityLabel} · ${technicalInfo.resolutionText} · ${technicalInfo.fpsText}",
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    "${technicalInfo.codec ?: "Codec —"} · ${technicalInfo.bitrateText} · ${technicalInfo.hdr ?: "HDR/SDR —"}",
                    color = FocusBlueBright,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                val dolbyText = listOfNotNull(dolbyVisionLabel, dolbyAtmosLabel).joinToString(" · ")
                if (dolbyText.isNotBlank()) {
                    Text(
                        dolbyText,
                        color = FocusBlueBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "$transport · Audio $audioLabel · ST $subtitleLabel",
                    color = MutedInk,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    diagnosticsText(diagnostics),
                    color = MutedInk,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }

        if (entry.type != MediaType.Live && durationMs > 0L) {
            Spacer(Modifier.height(12.dp))
            PlaybackTimeline(positionMs, durationMs)
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (isPlaying) "⏸ Lecture" else "▶ Pause", color = Ink, fontSize = 13.sp)
            Spacer(Modifier.width(20.dp))
            if (entry.type == MediaType.Live) {
                Text("OK infos · ← liste · ↑ ↓ zap · 0–9 chaîne", color = MutedInk, fontSize = 13.sp)
                Spacer(Modifier.width(20.dp))
            } else {
                Text("← −10 s · +10 s → · Lecture/Pause", color = MutedInk, fontSize = 13.sp)
                Spacer(Modifier.width(20.dp))
            }
            Text("⚙ audio / sous-titres / écran", color = MutedInk, fontSize = 13.sp)
            if (numberBuffer.isNotBlank()) {
                Spacer(Modifier.weight(1f))
                Text("CH $numberBuffer", color = FocusBlueBright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PlaybackTimeline(positionMs: Long, durationMs: Long) {
    val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatDuration(positionMs), color = Ink, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Canvas(Modifier.weight(1f).height(8.dp)) {
            drawRoundRect(Color.White.copy(alpha = 0.22f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            drawRoundRect(FocusBlueBright, size = Size(size.width * progress, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        }
        Spacer(Modifier.width(12.dp))
        Text(formatDuration(durationMs), color = Ink, fontSize = 12.sp)
    }
}

@Composable
private fun PlayerGuide(
    catalog: Catalog,
    currentEntry: MediaEntry,
    onEntrySelected: (MediaEntry) -> Unit,
    onClose: () -> Unit,
) {
    val categories = remember(catalog) { listOf(Catalog.allCategory(MediaType.Live)) + catalog.categoriesFor(MediaType.Live) }
    var selectedCategoryId by remember(currentEntry.categoryId) { mutableStateOf(currentEntry.categoryId) }
    val channels = remember(catalog, selectedCategoryId) { catalog.entriesIn(MediaType.Live, selectedCategoryId) }
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
            .width(820.dp)
            .background(Night.copy(alpha = 0.98f))
            .padding(24.dp),
    ) {
        Column(Modifier.width(290.dp).fillMaxHeight()) {
            Text("Catégories", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(categories, key = { it.key }) { category ->
                    FocusableSurface(
                        onClick = { selectedCategoryId = category.id },
                        selected = selectedCategoryId == category.id,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) {
                        Text(category.name, color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
            }
        }

        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Chaînes", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                FocusableSurface(onClick = onClose, modifier = Modifier.width(105.dp).height(46.dp)) {
                    Text("Fermer", color = Ink, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(channels, key = { it.key }) { channel ->
                    FocusableSurface(
                        onClick = { onEntrySelected(channel) },
                        selected = channel.key == currentEntry.key,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .then(if (channel.key == channels.firstOrNull()?.key) Modifier.focusRequester(firstFocus) else Modifier),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChannelLogo(channel.iconUrl, channel.name, Modifier.size(46.dp))
                            Spacer(Modifier.width(11.dp))
                            Text(channel.name, color = Ink, fontSize = 15.sp, fontWeight = if (channel.key == currentEntry.key) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(channel.number.toString(), color = FocusBlueBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun selectedVideoFormat(tracks: Tracks): androidx.media3.common.Format? {
    tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { group ->
        for (index in 0 until group.length) {
            if (group.isTrackSelected(index)) return group.getTrackFormat(index)
        }
    }
    return null
}

private fun selectedAudioFormat(tracks: Tracks): androidx.media3.common.Format? {
    tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
        for (index in 0 until group.length) {
            if (group.isTrackSelected(index)) return group.getTrackFormat(index)
        }
    }
    return null
}

private fun extractChoices(tracks: Tracks, type: Int): List<TrackChoice> = buildList {
    val seen = mutableSetOf<String>()
    tracks.groups.filter { it.type == type }.forEach { group ->
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            val language = format.language?.takeIf(String::isNotBlank) ?: continue
            val baseLabel = format.label?.takeIf(String::isNotBlank)
                ?: Locale.forLanguageTag(language).displayLanguage.takeIf(String::isNotBlank)
                ?: language
            val label = if (type == C.TRACK_TYPE_AUDIO && isDolbyAtmosFormat(format.sampleMimeType)) {
                "Dolby Atmos · $baseLabel"
            } else {
                baseLabel
            }
            val key = "$label:$language"
            if (seen.add(key)) add(TrackChoice(label, language))
        }
    }
}

private fun Int.toTvDigit(): Int? = when {
    this in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> this - AndroidKeyEvent.KEYCODE_0
    this in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> this - AndroidKeyEvent.KEYCODE_NUMPAD_0
    else -> null
}

private fun Int.toPlaybackRemoteButton(): PlaybackRemoteButton = when (this) {
    AndroidKeyEvent.KEYCODE_CHANNEL_UP,
    AndroidKeyEvent.KEYCODE_DPAD_UP,
    -> PlaybackRemoteButton.Up

    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN,
    AndroidKeyEvent.KEYCODE_DPAD_DOWN,
    -> PlaybackRemoteButton.Down

    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> PlaybackRemoteButton.Left
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> PlaybackRemoteButton.Right
    AndroidKeyEvent.KEYCODE_MENU -> PlaybackRemoteButton.Menu
    AndroidKeyEvent.KEYCODE_SETTINGS -> PlaybackRemoteButton.Settings
    AndroidKeyEvent.KEYCODE_INFO -> PlaybackRemoteButton.Info
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
    AndroidKeyEvent.KEYCODE_BUTTON_A,
    -> PlaybackRemoteButton.Ok

    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PlaybackRemoteButton.PlayPause

    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> PlaybackRemoteButton.Rewind
    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlaybackRemoteButton.FastForward
    else -> PlaybackRemoteButton.Other
}

private fun EpgProgram.timeRange(): String? {
    val start = startEpochSeconds ?: return null
    val end = endEpochSeconds ?: return null
    val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    return "${format.format(Date(start * 1000L))}–${format.format(Date(end * 1000L))}"
}

private fun streamTransportLabel(url: String): String {
    if (url.isBlank()) return "Transport —"
    val scheme = url.substringBefore("://", "").uppercase().ifBlank { "HTTP" }
    val extension = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
    val container = when (extension) {
        "m3u8" -> "HLS"
        "ts" -> "TS"
        else -> extension.uppercase().ifBlank { "AUTO" }
    }
    return "$scheme · $container"
}

private fun diagnosticsText(value: PlaybackDiagnostics): String {
    val startup = value.startupTimeMs?.let { "démarrage ${it} ms" } ?: "démarrage…"
    val rebuffer = if (value.rebufferCount == 0) {
        "0 rebuffer"
    } else {
        "${value.rebufferCount} rebuffer · ${value.totalRebufferTimeMs} ms"
    }
    return "$startup · $rebuffer"
}

private fun formatDuration(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

package fr.streamia.tv.ui

import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import fr.streamia.tv.data.DisplayModeSwitch
import fr.streamia.tv.player.unsupportedFormatMessage
import fr.streamia.tv.player.isDecoderError
import fr.streamia.tv.player.MAX_STREAM_RECOVERY_ATTEMPTS
import fr.streamia.tv.player.StreamRecovery
import fr.streamia.tv.player.isRecoverableStreamError
import fr.streamia.tv.player.streamRecoveryDelayMs
import fr.streamia.tv.player.DisplayModeSwitcher
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.LiveStreamFormat
import fr.streamia.tv.data.VideoAspectSetting
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgNowContext
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.SeriesEpisode
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
import fr.streamia.tv.player.playbackMetadata
import fr.streamia.tv.player.codecLabel
import fr.streamia.tv.player.dolbyPlaybackLabel
import fr.streamia.tv.player.hdrLabel
import fr.streamia.tv.player.isDolbyAtmosFormat
import fr.streamia.tv.player.isDolbyVisionFormat
import fr.streamia.tv.player.playbackRemoteAction
import fr.streamia.tv.player.resolveSeekPosition
import fr.streamia.tv.player.shouldPersistVodProgress
import fr.streamia.tv.player.LivePlaybackSession
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.GlassBorder
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RadiusCard
import fr.streamia.tv.ui.theme.RadiusTile
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
private const val EXTERNAL_SUBTITLE_LANGUAGE_TAG = "und"
private const val NEXT_EPISODE_COUNTDOWN_SECONDS = 8
private const val LIVE_EPG_PROGRESS_REFRESH_MS = 5_000L

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun PlayerScreen(
    catalog: Catalog,
    credentials: ServerCredentials,
    entry: MediaEntry,
    epg: EpgNowContext,
    resumePositionMs: Long,
    appSettings: AppSettings,
    hiddenEntries: Set<String>,
    lockedCategories: Set<String>,
    parentalControlEnabled: Boolean,
    parentalUnlocked: Boolean,
    nextEpisode: SeriesEpisode?,
    livePlaybackSession: LivePlaybackSession,
    liveVideoSurface: @Composable (LiveVideoSurfacePlacement) -> Unit,
    liveReturnsToSource: Boolean,
    onBack: () -> Unit,
    onZap: (Int) -> Unit,
    /** Dernière chaîne regardée (⏪ ou touche « dernière chaîne »). */
    onPreviousChannel: () -> Unit = {},
    /** Chaîne annoncée par un zap rapide, affichée avant que son flux ne démarre. */
    pendingZapEntry: MediaEntry? = null,
    onEntrySelected: (MediaEntry) -> Unit,
    onProgress: (MediaEntry, Long, Long) -> Unit,
    onCycleVideoAspect: () -> Unit,
    onPlayNextEpisode: () -> Unit,
    /** Film lu jusqu'au bout : retour sur sa fiche au lieu de rester sur l'écran noir de fin. */
    onMovieFinished: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedLivePlayer = entry.type == MediaType.Live
    val player = remember(entry.type, livePlaybackSession, appSettings.bufferMode, appSettings.tunnelingEnabled) {
        if (sharedLivePlayer) {
            livePlaybackSession.player
        } else {
            StreamiaPlayerFactory.create(context.applicationContext, entry.type, appSettings.bufferMode, appSettings.tunnelingEnabled)
        }
    }
    val mediaSession = remember(player) { MediaSession.Builder(context.applicationContext, player).build() }
    val activity = remember(context) { context.findActivity() }
    val switchDisplayMode = rememberUpdatedState(
        when (appSettings.displayModeSwitch) {
            DisplayModeSwitch.Off -> false
            DisplayModeSwitch.Vod -> entry.type != MediaType.Live
            DisplayModeSwitch.All -> true
        },
    )
    // Lecteur quitté : l'écran reprend le mode de l'interface.
    DisposableEffect(activity) { onDispose { activity?.let(DisplayModeSwitcher::reset) } }
    val transportStore = remember { PlaybackTransportStore(context.applicationContext) }
    val trackPreferenceStore = remember { PlaybackTrackPreferenceStore(context.applicationContext) }
    val diagnosticsTracker = remember { PlaybackDiagnosticsTracker() }
    val dolbyCapabilities = remember { DolbyCapabilityDetector.detect(context.applicationContext) }
    val rootFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }

    var guideOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    // OK/gauche/menu/retour sur le Live ne rouvrent plus un sélecteur superposé : ils demandent un
    // retour vers le Browser principal (PlayerOverlayController.requestReturnToBrowser), qui peut être
    // différé le temps que le catalogue restauré au démarrage finisse de s'hydrater
    // (shouldDeferLiveBrowserReturn). Sans ce drapeau, l'écran ne montrait plus aucun retour
    // visuel pendant cette attente (juste le HUD masqué) : à l'utilisateur, l'appui semblait
    // ignoré alors que le retour est en réalité déjà programmé et va aboutir.
    var returningToBrowser by remember(entry.key) { mutableStateOf(false) }
    var hudVisible by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var streamCandidates by remember { mutableStateOf(emptyList<String>()) }
    var candidateIndex by remember { mutableStateOf(0) }
    var activeStreamUrl by remember { mutableStateOf("") }
    var numberBuffer by remember { mutableStateOf("") }
    var playbackEnded by remember(entry.key) { mutableStateOf(false) }
    val showNextEpisodePrompt = playbackEnded && entry.type == MediaType.Series &&
        nextEpisode != null && appSettings.autoPlayNextEpisode
    var nextEpisodeCountdown by remember(playbackEnded) { mutableStateOf(NEXT_EPISODE_COUNTDOWN_SECONDS) }
    LaunchedEffect(playbackEnded) {
        if (playbackEnded && entry.type == MediaType.Movie) onMovieFinished()
    }
    LaunchedEffect(showNextEpisodePrompt) {
        if (!showNextEpisodePrompt) return@LaunchedEffect
        while (nextEpisodeCountdown > 0) {
            delay(1_000)
            nextEpisodeCountdown -= 1
        }
        onPlayNextEpisode()
    }
    val aspect = when (appSettings.videoAspect) {
        VideoAspectSetting.Fit -> VideoAspect.Fit
        VideoAspectSetting.Fill -> VideoAspect.Fill
        VideoAspectSetting.Zoom -> VideoAspect.Zoom
    }
    var audioTracks by remember { mutableStateOf(listOf(TrackChoice("Auto", null))) }
    var subtitleTracks by remember { mutableStateOf(listOf(TrackChoice("Désactivés", null))) }
    var audioIndex by remember { mutableStateOf(0) }
    var subtitleIndex by remember { mutableStateOf(0) }
    var audioPreferencePending by remember(entry.key) { mutableStateOf(true) }
    var subtitlePreferencePending by remember(entry.key) { mutableStateOf(true) }
    var technicalInfo by remember { mutableStateOf(StreamTechnicalInfo()) }
    var diagnostics by remember { mutableStateOf(PlaybackDiagnostics()) }
    var dolbyVisionDetected by remember { mutableStateOf(false) }
    var dolbyAtmosDetected by remember { mutableStateOf(false) }
    var seekFeedback by remember(entry.key) { mutableStateOf<String?>(null) }
    var positionMs by remember(entry.key) { mutableStateOf(0L) }
    var durationMs by remember(entry.key) { mutableStateOf(0L) }
    var watchdogRecoveryCount by remember(entry.key) { mutableStateOf(0) }
    // Flux déjà affiché au moins une fois : une erreur réseau ensuite (coupure après des heures de
    // lecture) relance la même URL au lieu d'afficher un écran d'erreur définitif.
    var streamHasPlayed by remember(entry.key) { mutableStateOf(false) }
    var recoveryAttempt by remember(entry.key) { mutableIntStateOf(0) }
    var pendingRecovery by remember(entry.key) { mutableStateOf<StreamRecovery?>(null) }
    // Abandon après une erreur réseau (coupure plus longue que les relances) : repris au retour du réseau.
    var gaveUpOnNetworkError by remember(entry.key) { mutableStateOf(false) }
    // Sous-titre externe (.srt/.vtt) chargé pour cette session de lecture uniquement : pas de
    // persistance entre relectures, il repart à null à chaque nouvelle entrée (remember(entry.key)).
    var externalSubtitle by remember(entry.key) { mutableStateOf<MediaItem.SubtitleConfiguration?>(null) }
    var externalSubtitleError by remember(entry.key) { mutableStateOf<String?>(null) }
    // Une fois le sous-titre externe demandé, le prochain onTracksChanged doit pointer subtitleIndex
    // sur la piste "und" qui vient d'apparaître, sinon le HUD/Réglages continuent d'afficher
    // "Désactivés" bien que le sous-titre externe soit réellement actif dans le lecteur.
    var externalSubtitlePendingSync by remember(entry.key) { mutableStateOf(false) }

    fun startCandidate(url: String, positionMs: Long = 0L) {
        activeStreamUrl = url
        playbackError = null
        buffering = true
        if (sharedLivePlayer) {
            livePlaybackSession.playUrl(entry, url)
            return
        }
        runCatching {
            player.stop()
            // Un MediaItem.SubtitleConfiguration ne peut être attaché qu'à la construction du
            // MediaItem : on le réinjecte ici pour que le sous-titre externe survive à un
            // changement de candidat ou à une reconnexion du watchdog sur ce même flux.
            val mediaItem = MediaItem.Builder().setUri(url).setMediaMetadata(entry.playbackMetadata()).apply {
                externalSubtitle?.let { setSubtitleConfigurations(listOf(it)) }
            }.build()
            player.setMediaItem(mediaItem)
            if (positionMs > 0 && entry.type != MediaType.Live) player.seekTo(positionMs)
            player.prepare()
            player.play()
        }.onFailure {
            buffering = false
            playbackError = "Ce contenu ne peut pas être démarré pour le moment."
        }
    }

    fun loadExternalSubtitle(subtitleUri: Uri, displayName: String) {
        if (sharedLivePlayer) return
        val mimeType = subtitleMimeTypeFor(displayName)
        if (mimeType == null) {
            externalSubtitleError = "Format non reconnu : utilisez un fichier .srt ou .vtt."
            return
        }
        externalSubtitleError = null
        externalSubtitle = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
            .setMimeType(mimeType)
            .setLanguage(EXTERNAL_SUBTITLE_LANGUAGE_TAG)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setLabel(displayName)
            .build()
        // "Désactivés" (aucune piste embarquée choisie) désactive tout le type TRACK_TYPE_TEXT dans
        // les TrackSelectionParameters au premier onTracksChanged. Sans réactivation explicite ici,
        // ExoPlayer ignore le sous-titre externe même marqué SELECTION_FLAG_DEFAULT : le type entier
        // du renderer resterait coupé.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(EXTERNAL_SUBTITLE_LANGUAGE_TAG)
            .build()
        externalSubtitlePendingSync = true
        startCandidate(activeStreamUrl, player.currentPosition.coerceAtLeast(0L))
    }

    val pickSubtitleFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val displayName = documentDisplayName(context, uri) ?: uri.lastPathSegment.orEmpty()
        loadExternalSubtitle(uri, displayName)
    }

    DisposableEffect(mediaSession) {
        onDispose { mediaSession.release() }
    }

    DisposableEffect(player) {
        onDispose { if (!sharedLivePlayer) player.release() }
    }

    fun applyAudio(choice: TrackChoice) {
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setPreferredAudioLanguage(choice.language)
        choice.group?.let { builder.setOverrideForType(TrackSelectionOverride(it, choice.trackIndex)) }
        player.trackSelectionParameters = builder.build()
    }

    fun applySubtitle(choice: TrackChoice) {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (choice.language == null && choice.group == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setPreferredTextLanguage(choice.language)
            choice.group?.let { builder.setOverrideForType(TrackSelectionOverride(it, choice.trackIndex)) }
        }
        player.trackSelectionParameters = builder.build()
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
                // Pas seulement à `true` sur STATE_ENDED : un seek en arrière après la fin (reprendre la
                // lecture, rejouer) ramène le lecteur à STATE_READY/STATE_BUFFERING sans autre signal
                // dédié — sans ce reset, le bandeau « épisode suivant » resterait affiché (et son
                // interception clavier bloquée) alors que la lecture a repris.
                playbackEnded = playbackState == Player.STATE_ENDED
                diagnostics = diagnosticsTracker.snapshot(now)
            }

            override fun onRenderedFirstFrame() {
                val now = SystemClock.elapsedRealtime()
                diagnosticsTracker.onFirstFrame(now)
                diagnosticsTracker.onBufferingEnded(now)
                diagnostics = diagnosticsTracker.snapshot(now)
                buffering = false
                streamHasPlayed = true
                recoveryAttempt = 0
                transportStore.recordSuccess(activeStreamUrl, entry.type)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                technicalInfo = technicalInfo.copy(width = videoSize.width, height = videoSize.height)
                // Sortie HDMI adaptée à la vidéo (4K, 24/25/50 Hz) selon le réglage « Adapter l'affichage ».
                if (switchDisplayMode.value) {
                    activity?.let { DisplayModeSwitcher.apply(it, videoSize.width, videoSize.height, player.videoFormat?.frameRate ?: -1f) }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (sharedLivePlayer) livePlaybackSession.recoverAudio(tracks)
                audioTracks = listOf(TrackChoice("Auto", null)) + extractChoices(tracks, C.TRACK_TYPE_AUDIO)
                subtitleTracks = listOf(TrackChoice("Désactivés", null)) + extractChoices(tracks, C.TRACK_TYPE_TEXT)
                // Les pistes arrivent souvent en plusieurs fois (sous-titres MKV/HLS annoncés après
                // l'audio et la vidéo) : la préférence enregistrée reste en attente jusqu'à ce qu'une
                // piste de la bonne langue apparaisse, au lieu d'être tranchée au premier appel.
                val saved = trackPreferenceStore.load()
                if (audioPreferencePending) {
                    val index = audioTracks.indexOfFirst { it.language != null && it.language == saved.audioLanguage }
                    if (index > 0) {
                        audioIndex = index
                        applyAudio(audioTracks[index])
                        audioPreferencePending = false
                    }
                }
                audioIndex = audioIndex.coerceIn(0, audioTracks.lastIndex.coerceAtLeast(0))
                val externalIndex = if (externalSubtitlePendingSync) {
                    subtitleTracks.indexOfFirst { it.language == EXTERNAL_SUBTITLE_LANGUAGE_TAG }
                } else {
                    -1
                }
                if (externalIndex >= 0) {
                    subtitleIndex = externalIndex
                    externalSubtitlePendingSync = false
                    subtitlePreferencePending = false
                } else if (subtitlePreferencePending) {
                    // Piste complète plutôt que « forcés » (quelques répliques seulement) pour la même langue.
                    val index = subtitleTracks.indices
                        .filter { subtitleTracks[it].language != null && subtitleTracks[it].language == saved.subtitleLanguage }
                        .minByOrNull { if (subtitleTracks[it].forced) 1 else 0 } ?: -1
                    if (saved.subtitleLanguage == null) {
                        subtitleIndex = 0
                        applySubtitle(subtitleTracks[0])
                        subtitlePreferencePending = false
                    } else if (index > 0) {
                        subtitleIndex = index
                        applySubtitle(subtitleTracks[index])
                        subtitlePreferencePending = false
                    }
                } else {
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
                // Format que le boîtier ne sait pas décoder : une autre URL (TS/HLS…) n'y changera rien.
                if (isDecoderError(error.errorCode)) {
                    val format = player.videoFormat
                    val mimeType = (error.cause as? MediaCodecRenderer.DecoderInitializationException)?.mimeType ?: format?.sampleMimeType
                    playbackError = unsupportedFormatMessage(mimeType, format?.width ?: 0, format?.height ?: 0)
                    buffering = false
                    return
                }
                if (streamHasPlayed && isRecoverableStreamError(error.errorCode) && recoveryAttempt < MAX_STREAM_RECOVERY_ATTEMPTS) {
                    recoveryAttempt += 1
                    buffering = true
                    pendingRecovery = StreamRecovery(activeStreamUrl, player.currentPosition.coerceAtLeast(0L), recoveryAttempt)
                    return
                }
                val next = candidateIndex + 1
                if (next < streamCandidates.size) {
                    val previousPosition = player.currentPosition.coerceAtLeast(0L)
                    candidateIndex = next
                    startCandidate(streamCandidates[next], previousPosition)
                    return
                }
                gaveUpOnNetworkError = isRecoverableStreamError(error.errorCode)
                playbackError = "Ce contenu ne peut pas être lu pour le moment."
                buffering = false
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val networkReconnections = LocalNetworkReconnections.current
    LaunchedEffect(networkReconnections) {
        if (!gaveUpOnNetworkError || playbackError == null) return@LaunchedEffect
        gaveUpOnNetworkError = false
        recoveryAttempt = 0
        startCandidate(activeStreamUrl.ifBlank { streamCandidates.firstOrNull() ?: return@LaunchedEffect }, player.currentPosition.coerceAtLeast(0L))
    }

    LaunchedEffect(pendingRecovery) {
        val recovery = pendingRecovery ?: return@LaunchedEffect
        delay(streamRecoveryDelayMs(recovery.attempt))
        startCandidate(recovery.url, recovery.positionMs)
        pendingRecovery = null
    }

    DisposableEffect(entry.key) {
        onDispose {
            // Direct : compté comme regardé après 2,5 s (effet plus bas), pas à chaque chaîne
            // traversée en zappant.
            if (entry.type == MediaType.Live) return@onDispose
            runCatching {
                val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                onProgress(entry, player.currentPosition.coerceAtLeast(0), duration)
            }
        }
    }

    LaunchedEffect(entry.key, credentials, appSettings.liveStreamFormat) {
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
        val storedPreference = transportStore.preferenceFor(baseUrl)
        val preferredLiveExtension = when (appSettings.liveStreamFormat) {
            LiveStreamFormat.Auto -> storedPreference.liveExtension
            LiveStreamFormat.Ts -> "ts"
            LiveStreamFormat.Hls -> "m3u8"
        }
        streamCandidates = PlaybackUrlStrategy.candidates(
            initialUrl = baseUrl,
            type = entry.type,
            preference = storedPreference.copy(liveExtension = preferredLiveExtension),
        )
        candidateIndex = 0
        if (sharedLivePlayer && livePlaybackSession.isCurrent(entry)) {
            activeStreamUrl = livePlaybackSession.activeUrl
            buffering = player.playbackState != Player.STATE_READY
            livePlaybackSession.continuePlayback()
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
                if (shouldPersistVodProgress(positionMs, lastSavedAt, now)) {
                    lastSavedAt = now
                    onProgress(entry, positionMs, duration)
                }
            }
        }
    }

    LaunchedEffect(entry.key, sharedLivePlayer) {
        if (sharedLivePlayer) return@LaunchedEffect
        var previousPosition = -1L
        var stalledChecks = 0
        while (true) {
            delay(5_000)
            val currentPosition = player.currentPosition.coerceAtLeast(0L)
            val shouldAdvance = player.isPlaying && player.playbackState == Player.STATE_READY
            // abs(): a manual backward seek (the −10 s remote button) drops currentPosition below
            // previousPosition, which a plain subtraction misreads as a stall and can force an
            // unwanted full stream restart on perfectly healthy playback.
            val stalled = shouldAdvance && previousPosition >= 0L && kotlin.math.abs(currentPosition - previousPosition) < 500L
            stalledChecks = if (stalled) stalledChecks + 1 else 0
            if (!stalled) watchdogRecoveryCount = 0

            if (stalledChecks >= 2) {
                if (watchdogRecoveryCount < 2) {
                    watchdogRecoveryCount += 1
                    stalledChecks = 0
                    buffering = true
                    startCandidate(activeStreamUrl, currentPosition)
                } else if (playbackError == null) {
                    // Both automatic recovery attempts already failed to unstick this stream.
                    // Without this branch the loop keeps silently doing nothing forever: the
                    // screen stays frozen with no error and no visible sign anything is wrong.
                    gaveUpOnNetworkError = true
                    playbackError = "La lecture semble bloquée."
                    buffering = false
                }
            }
            previousPosition = currentPosition
        }
    }

    // Comme pour le zapping CH+/CH-, la saisie directe d'un numéro n'a pas d'écran de code : une
    // chaîne d'une catégorie verrouillée et pas encore déverrouillée cette session est donc exclue
    // au même titre qu'une chaîne masquée plutôt que de silencieusement contourner le verrouillage.
    val numericJumpLockedCategoryIds = remember(catalog, lockedCategories, parentalControlEnabled, parentalUnlocked) {
        if (!parentalControlEnabled || parentalUnlocked) emptySet()
        else catalog.categoriesFor(MediaType.Live)
            .filter { it.key in lockedCategories }
            .mapTo(mutableSetOf(), MediaCategory::id)
    }
    // Numéro sans chaîne (inexistant, masqué ou verrouillé) : message plutôt qu'un silence.
    var missingChannelNumber by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(missingChannelNumber) {
        if (missingChannelNumber == null) return@LaunchedEffect
        delay(2_000)
        missingChannelNumber = null
    }
    LaunchedEffect(numberBuffer) {
        if (numberBuffer.isBlank()) return@LaunchedEffect
        delay(1_250)
        val number = numberBuffer.toIntOrNull()
        numberBuffer = ""
        if (number != null) {
            val target = catalog.entriesFor(MediaType.Live)
                .firstOrNull {
                    it.number == number &&
                        it.key !in hiddenEntries &&
                        it.categoryId !in numericJumpLockedCategoryIds
                }
            if (target != null) onEntrySelected(target) else missingChannelNumber = number
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
    LaunchedEffect(hudVisible, guideOpen, settingsOpen, entry.key, returningToBrowser) {
        if (hudVisible && !guideOpen && !settingsOpen && !returningToBrowser) {
            delay(6_000)
            hudVisible = false
        }
    }


    BackHandler {
        when {
            settingsOpen -> { settingsOpen = false; rootFocus.requestFocus() }
            guideOpen -> { guideOpen = false; rootFocus.requestFocus() }
            // Passe par le même chemin que OK/gauche (PlayerOverlayController.requestReturnToBrowser) au
            // lieu d'appeler onBack()/closePlayer() directement : sans ce report, un retour appuyé
            // pendant que le catalogue restauré au démarrage (resumeStartup) est encore en cours de
            // relecture atterrit sur l'accueil au lieu du navigateur Live, car closePlayer() retombe
            // sur l'accueil tant que catalogHydrating est vrai.
            // Exception : une chaîne lancée depuis un écran qui sait se restaurer (accueil, matchs
            // du jour) y revient, sur la carte d'origine (liveReturnsToSource) — onBack() laisse
            // alors closePlayer() honorer le ContentReturnContext.
            sharedLivePlayer && !liveReturnsToSource -> { returningToBrowser = true; PlayerOverlayController.requestReturnToBrowser() }
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
                if (event.type != KeyEventType.KeyDown || guideOpen || settingsOpen || returningToBrowser || showNextEpisodePrompt) return@onPreviewKeyEvent false
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
                    PlaybackRemoteAction.PreviousChannel -> { onPreviousChannel(); true }
                    PlaybackRemoteAction.OpenLivePicker -> {
                        returningToBrowser = true
                        PlayerOverlayController.requestReturnToBrowser()
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
                        val delta = if (remoteAction == PlaybackRemoteAction.SeekBackward) -appSettings.vodSeekStepMs else appSettings.vodSeekStepMs
                        val duration = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
                        val target = resolveSeekPosition(player.currentPosition, duration, delta)
                        player.seekTo(target)
                        positionMs = target
                        seekFeedback = if (delta < 0L) "−${appSettings.vodSeekStepSeconds} s" else "+${appSettings.vodSeekStepSeconds} s"
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
                    it.subtitleView?.applySubtitleStyle(appSettings.subtitleSizeScale, appSettings.subtitleBackgroundEnabled)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (returningToBrowser) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                StatusDot(diameter = 24.dp)
                Spacer(Modifier.height(12.dp))
                Text("Retour à la liste des chaînes…", color = Ink, fontSize = 18.sp)
            }
        } else if (buffering) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                StatusDot(diameter = 24.dp)
                Spacer(Modifier.height(12.dp))
                if (!sharedLivePlayer && watchdogRecoveryCount > 0) {
                    // Distingue une reconnexion automatique après un flux figé (watchdog) du
                    // chargement initial générique : même habillage visuel, message différent.
                    Text("Reconnexion en cours… (tentative $watchdogRecoveryCount/2)", color = Ink, fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Le flux s'est interrompu, nouvelle tentative automatique", color = MutedInk, fontSize = 13.sp)
                } else {
                    Text("Chargement de ${entry.displayName}…", color = Ink, fontSize = 18.sp)
                    if (resumePositionMs > 0 && entry.type != MediaType.Live) {
                        Spacer(Modifier.height(6.dp))
                        Text("Reprise de la lecture", color = MutedInk, fontSize = 13.sp)
                    }
                }
            }
        }

        if (playbackError != null) {
            FocusableSurface(
                onClick = {
                    playbackError = null
                    candidateIndex = 0
                    watchdogRecoveryCount = 0
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

        if (hudVisible && !guideOpen && !settingsOpen && !returningToBrowser) {
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

        pendingZapEntry?.let { target ->
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(34.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(RadiusTile))
                    .background(Night.copy(alpha = 0.86f))
                    .border(BorderStroke(1.dp, GlassBorder), androidx.compose.foundation.shape.RoundedCornerShape(RadiusTile))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(target.number.toString(), color = FocusBlueBright, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(14.dp))
                ChannelLogo(target.iconUrl, target.displayName, Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Text(target.displayName, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }

        if (numberBuffer.isNotBlank()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(34.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(RadiusTile))
                    .background(Night.copy(alpha = 0.82f))
                    .border(BorderStroke(1.dp, GlassBorder), androidx.compose.foundation.shape.RoundedCornerShape(RadiusTile))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
            ) {
                Text(numberBuffer, color = FocusBlueBright, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
        }
        missingChannelNumber?.let { number ->
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(34.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(RadiusTile))
                    .background(Night.copy(alpha = 0.82f))
                    .border(BorderStroke(1.dp, GlassBorder), androidx.compose.foundation.shape.RoundedCornerShape(RadiusTile))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
            ) {
                Text("Chaîne $number introuvable", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        seekFeedback?.let { feedback ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(RadiusTile))
                    .background(Night.copy(alpha = 0.8f))
                    .border(BorderStroke(1.dp, GlassBorder), androidx.compose.foundation.shape.RoundedCornerShape(RadiusTile))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
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
                    audioPreferencePending = false
                    trackPreferenceStore.saveAudio(audioTracks[audioIndex].language)
                },
                onSubtitleSelected = { selectedIndex ->
                    subtitleIndex = selectedIndex.coerceIn(subtitleTracks.indices)
                    applySubtitle(subtitleTracks[subtitleIndex])
                    subtitlePreferencePending = false
                    trackPreferenceStore.saveSubtitle(subtitleTracks[subtitleIndex].language)
                },
                onNextAspect = onCycleVideoAspect,
                onClose = { settingsOpen = false; rootFocus.requestFocus() },
                externalSubtitleAvailable = !sharedLivePlayer,
                externalSubtitleLabel = externalSubtitle?.label,
                externalSubtitleError = externalSubtitleError,
                onPickExternalSubtitleFile = {
                    // Les fournisseurs de documents décrivent rarement .srt/.vtt avec un type MIME
                    // fiable (souvent text/plain ou application/octet-stream) : on filtre large côté
                    // sélecteur puis on valide réellement via l'extension dans subtitleMimeTypeFor().
                    pickSubtitleFile.launch(
                        arrayOf("text/plain", "text/vtt", "application/x-subrip", "application/octet-stream"),
                    )
                },
                onLoadExternalSubtitleUrl = { url ->
                    val uri = url.toUri()
                    if (uri.scheme != "http" && uri.scheme != "https") {
                        externalSubtitleError = "URL de sous-titre invalide (http/https attendu)."
                    } else {
                        val fileName = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
                        loadExternalSubtitle(uri, fileName.ifBlank { url })
                    }
                },
            )
        }

        if (showNextEpisodePrompt) {
            NextEpisodePrompt(
                episode = nextEpisode,
                secondsLeft = nextEpisodeCountdown,
                onPlayNow = onPlayNextEpisode,
                onCancel = { playbackEnded = false },
            )
        }
    }
}

@Composable
private fun NextEpisodePrompt(
    episode: SeriesEpisode?,
    secondsLeft: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
) {
    if (episode == null) return
    val playNowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playNowFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier
                .padding(34.dp)
                .width(420.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(RadiusCard))
                .background(Night.copy(alpha = 0.85f))
                .border(BorderStroke(1.dp, GlassBorder), androidx.compose.foundation.shape.RoundedCornerShape(RadiusCard))
                .padding(22.dp),
        ) {
            Text("Épisode suivant dans ${secondsLeft}s", color = MutedInk, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "S${episode.season.toString().padStart(2, '0')}E${episode.number.toString().padStart(2, '0')} · ${episode.title}",
                color = Ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusableSurface(onClick = onPlayNow, accent = true, modifier = Modifier.weight(1f).height(48.dp).focusRequester(playNowFocus)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Lire maintenant", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                FocusableSurface(onClick = onCancel, modifier = Modifier.weight(1f).height(48.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Annuler", color = Ink, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerInfoBand(
    entry: MediaEntry,
    categoryName: String?,
    epg: EpgNowContext,
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
    var epgClockEpochSeconds by remember(
        entry.key,
        epg.current?.startEpochSeconds,
        epg.current?.endEpochSeconds,
    ) { mutableStateOf(System.currentTimeMillis() / 1000L) }

    LaunchedEffect(entry.key, epg.current?.startEpochSeconds, epg.current?.endEpochSeconds) {
        if (entry.type != MediaType.Live || epg.current == null) return@LaunchedEffect
        while (true) {
            epgClockEpochSeconds = System.currentTimeMillis() / 1000L
            delay(LIVE_EPG_PROGRESS_REFRESH_MS)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 22.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(RadiusCard))
            .background(Night.copy(alpha = 0.68f))
            .border(BorderStroke(1.dp, GlassBorder), androidx.compose.foundation.shape.RoundedCornerShape(RadiusCard))
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
                    fontWeight = HeadingWeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                if (entry.type == MediaType.Live) {
                    if (!epg.isEmpty) {
                        epg.current?.let { current ->
                            Text(
                                "EN CE MOMENT${current.timeRange()?.let { " · $it" }.orEmpty()}",
                                color = MutedInk,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            Text(
                                current.title,
                                color = FocusBlueBright,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            LiveProgramTimeline(
                                program = current,
                                nowEpochSeconds = epgClockEpochSeconds,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        epg.next?.let { next ->
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
                    fontSize = 12.sp,
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
                Text("OK infos · ← liste · ↑ ↓ zap · ⏪ dernière chaîne · 0–9 chaîne", color = MutedInk, fontSize = 13.sp)
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
private fun LiveProgramTimeline(
    program: EpgProgram,
    nowEpochSeconds: Long,
) {
    val start = program.startEpochSeconds ?: return
    val end = program.endEpochSeconds ?: return
    if (end <= start) return

    val progress = ((nowEpochSeconds - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatter.format(Date(start * 1000L)), color = MutedInk, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.weight(1f).height(6.dp)) {
            val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            drawRoundRect(Ink.copy(alpha = 0.18f), cornerRadius = radius)
            drawRoundRect(
                FocusBlueBright,
                size = Size(size.width * progress, size.height),
                cornerRadius = radius,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(formatter.format(Date(end * 1000L)), color = MutedInk, fontSize = 12.sp)
    }
}

@Composable
private fun PlaybackTimeline(positionMs: Long, durationMs: Long) {
    val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatDuration(positionMs), color = Ink, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Canvas(Modifier.weight(1f).height(8.dp)) {
            drawRoundRect(Ink.copy(alpha = 0.22f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
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
            .background(Night.copy(alpha = 0.85f))
            .border(BorderStroke(1.dp, GlassBorder))
            .padding(24.dp),
    ) {
        Column(Modifier.width(290.dp).fillMaxHeight()) {
            SectionLabel("Catégories", fontSize = 22.sp)
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
                SectionLabel("Chaînes", fontSize = 22.sp)
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
                            Text(channel.number.toString(), color = FocusBlueBright, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
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
    AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> PlaybackRemoteButton.LastChannel
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

private fun subtitleMimeTypeFor(name: String): String? =
    when (name.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        else -> null
    }

/** Nom d'affichage d'un document SAF (souvent différent du dernier segment d'un content://). */
private fun documentDisplayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
    }
}.getOrNull()

internal fun formatDuration(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

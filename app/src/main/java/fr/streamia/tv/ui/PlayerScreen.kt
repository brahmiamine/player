package fr.streamia.tv.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import fr.streamia.tv.data.DisplayModeSwitch
import fr.streamia.tv.data.LiveVersionStatsStore
import fr.streamia.tv.data.NetworkMonitor
import fr.streamia.tv.player.unsupportedFormatMessage
import fr.streamia.tv.player.isDecoderError
import fr.streamia.tv.player.MAX_STREAM_RECOVERY_ATTEMPTS
import fr.streamia.tv.player.StreamRecovery
import fr.streamia.tv.player.isRecoverableStreamError
import fr.streamia.tv.player.streamRecoveryDelayMs
import fr.streamia.tv.player.DisplayModeSwitcher
import fr.streamia.tv.player.LiveAudioIssue
import fr.streamia.tv.player.VideoFrameMonitor
import fr.streamia.tv.player.liveAudioIssue
import androidx.media3.common.Format
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.LiveStreamFormat
import fr.streamia.tv.data.VideoAspectSetting
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgNowContext
import fr.streamia.tv.domain.LiveCutCounter
import fr.streamia.tv.domain.LiveFailoverChain
import fr.streamia.tv.domain.LiveFailoverRules
import fr.streamia.tv.domain.LiveVersionCheck
import fr.streamia.tv.domain.LiveVersionHealth
import fr.streamia.tv.domain.LiveVersionIndex
import fr.streamia.tv.domain.decideLiveFailover
import fr.streamia.tv.domain.hasFailoverCandidates
import fr.streamia.tv.domain.LiveVersionStats
import fr.streamia.tv.domain.rankLiveVersions
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
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.RadiusTile
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

private const val NEXT_EPISODE_COUNTDOWN_SECONDS = 8

internal const val LIVE_EPG_PROGRESS_REFRESH_MS = 5_000L

private const val LIVE_VERSION_WATCH_SAMPLE_MS = 60_000L

private const val VOD_PLAYER_RELEASE_DELAY_MS = 1_000L

/** Contrôle réel du Direct : un relevé par seconde, verdict après 2 s de lecture, mesure enregistrée à 4 s. */
private const val LIVE_CHECK_INTERVAL_MS = 1_000L

private const val LIVE_CHECK_SETTLE_MS = 2_000L

private const val LIVE_CHECK_RECORD_AFTER_MS = 4_000L

/** « Tester toutes les versions » : délai maximal par version, et nombre de versions testées. */
private const val LIVE_SCAN_TIMEOUT_MS = 12_000L

private const val MAX_SCANNED_VERSIONS = 12

/** Changement de version en attente de sa première image : [from] est relancée s'il échoue. */
private data class LiveVersionSwitch(val from: MediaEntry, val targetKey: String)

/** Début d'une coupure du Direct ; [afterPlayback] : l'image avait déjà été affichée. */
private data class LiveOutageStart(val atMs: Long, val afterPlayback: Boolean)

/** Test de toutes les versions : [queue] est lancée à l'écran une version après l'autre. */
private data class LiveVersionScan(val origin: MediaEntry, val queue: List<MediaEntry>, val index: Int)

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
    /** Autre version de la chaîne en cours choisie dans le panneau « Versions ». */
    onSwitchVersion: (MediaEntry) -> Unit = onEntrySelected,
    /** Versions des chaînes du Direct, construit une fois par le ViewModel (toutes catégories). */
    liveVersionIndex: LiveVersionIndex? = null,
    /** Position, durée, et `true` quand la lecture est quittée ou passe en arrière-plan. */
    onProgress: (MediaEntry, Long, Long, Boolean) -> Unit,
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
    // Qualité réellement mesurée de chaque version de chaîne (panneau « Versions » du Direct).
    val versionStatsStore = remember { LiveVersionStatsStore(context.applicationContext) }
    val versionScope = remember(credentials) { LiveVersionStatsStore.scopeFor(credentials) }
    var versionStatsRevision by remember { mutableIntStateOf(0) }
    val trackPreferenceStore = remember { PlaybackTrackPreferenceStore(context.applicationContext) }
    val diagnosticsTracker = remember { PlaybackDiagnosticsTracker() }
    val dolbyCapabilities = remember { DolbyCapabilityDetector.detect(context.applicationContext) }
    val rootFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }

    var guideOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    // Panneau « Versions » affiché à la place du panneau « Lecture » (settingsOpen reste vrai).
    var versionsOpen by remember { mutableStateOf(false) }
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
    // Lus seulement par la timeline et le retour de saut (lambdas) : mis à jour chaque seconde, ils
    // recomposaient tout le lecteur tant que le bandeau était affiché.
    val positionState = remember(entry.key) { mutableLongStateOf(0L) }
    val durationState = remember(entry.key) { mutableLongStateOf(0L) }
    var positionMs by positionState
    var durationMs by durationState
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
        onDispose {
            if (!sharedLivePlayer) {
                // release() attend sur le thread principal que le décodeur soit libéré (jusqu'à
                // 0,5 s sur certains boîtiers) : stop() le libère d'abord en arrière-plan, et le
                // release() différé n'a presque plus rien à attendre une fois l'écran suivant affiché.
                player.stop()
                Handler(Looper.getMainLooper()).postDelayed({ player.release() }, VOD_PLAYER_RELEASE_DELAY_MS)
            }
        }
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
                if (entry.type == MediaType.Live) {
                    versionStatsStore.recordSuccess(versionScope, entry.key, diagnostics.startupTimeMs)
                    versionStatsRevision++
                }
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

    // Application passée en arrière-plan pendant un film : dernière position enregistrée et
    // « Continuer à regarder » de Google TV mis à jour tout de suite.
    val playerLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(entry.key, playerLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && entry.type != MediaType.Live) {
                runCatching {
                    val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                    onProgress(entry, player.currentPosition.coerceAtLeast(0), duration, true)
                }
            }
        }
        playerLifecycle.addObserver(observer)
        onDispose { playerLifecycle.removeObserver(observer) }
    }

    DisposableEffect(entry.key) {
        onDispose {
            // Direct : compté comme regardé après 2,5 s (effet plus bas), pas à chaque chaîne
            // traversée en zappant.
            if (entry.type == MediaType.Live) return@onDispose
            runCatching {
                val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                onProgress(entry, player.currentPosition.coerceAtLeast(0), duration, true)
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
            // Flux déjà lancé par l'aperçu : ses pistes ne seront pas renotifiées, on relit le format
            // en cours (bandeau d'infos et mesure de la version pour le panneau « Versions »).
            player.videoFormat?.let(::liveTechnicalInfo)?.let { technicalInfo = it }
        } else {
            val url = streamCandidates.firstOrNull() ?: baseUrl
            startCandidate(url, resumePositionMs)
        }
    }

    LaunchedEffect(entry.key) {
        if (entry.type == MediaType.Live) {
            delay(2_500)
            onProgress(entry, 0L, 0L, false)
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
                    onProgress(entry, positionMs, duration, false)
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

    // Versions de la chaîne en cours (« TF1 », « TF1 FHD », « FR| TF1 UHD »…), filtrées comme le
    // zapping (masquées, verrouillées). L'index vient du ViewModel : prêt avant l'ouverture du lecteur.
    val liveVersions = remember(liveVersionIndex, entry.key, hiddenEntries, numericJumpLockedCategoryIds) {
        if (entry.type != MediaType.Live) {
            emptyList()
        } else {
            liveVersionIndex?.versionsOf(entry).orEmpty().filter {
                it.key == entry.key || (it.key !in hiddenEntries && it.categoryId !in numericJumpLockedCategoryIds)
            }
        }
    }
    val hasOtherVersions = liveVersions.size > 1
    val maxDisplayHeight = remember(activity) { activity?.let(DisplayModeSwitcher::maxDisplayHeight) }
    val versionOptions = remember(liveVersions, versionStatsRevision, technicalInfo, versionsOpen) {
        if (!hasOtherVersions || !versionsOpen) {
            emptyList()
        } else {
            val stored = versionStatsStore.load(versionScope, liveVersions.map { it.key })
            // Version en cours : mesure en direct, sans attendre qu'elle soit enregistrée.
            val width = technicalInfo.width
            val height = technicalInfo.height
            val merged = if (width != null && height != null) {
                val previous = stored[entry.key] ?: LiveVersionStats()
                stored + (
                    entry.key to previous.copy(
                        width = width,
                        height = height,
                        frameRate = technicalInfo.frameRate ?: previous.frameRate,
                        codec = technicalInfo.codec ?: previous.codec,
                        bitrate = technicalInfo.bitrate ?: previous.bitrate,
                        hdr = technicalInfo.hdr ?: previous.hdr,
                        lastSuccessAtMs = maxOf(previous.lastSuccessAtMs, previous.lastFailureAtMs + 1),
                        consecutiveFailures = 0,
                    )
                    )
            } else {
                stored
            }
            rankLiveVersions(entry, liveVersions, merged, System.currentTimeMillis(), maxDisplayHeight)
        }
    }
    // Contrôle réel du Direct, un relevé par seconde et seulement en plein écran Direct : images
    // réellement affichées (fps réels, images perdues, image figée) et son. Enregistré 4 s après le
    // début de la lecture puis chaque minute ; alimente le classement des versions et le secours.
    val frameMonitor = remember(entry.key) { VideoFrameMonitor() }
    // Des images ont été affichées (y compris une chaîne reprise de l'aperçu, sans onRenderedFirstFrame).
    var liveFramesSeen by remember(entry.key) { mutableStateOf(false) }
    // Première mesure réelle de cette version enregistrée.
    var liveChecked by remember(entry.key) { mutableStateOf(false) }
    var noPicture by remember(entry.key) { mutableStateOf(false) }
    var audioIssue by remember(entry.key) { mutableStateOf<LiveAudioIssue?>(null) }
    // Début réel du problème d'image ou de son (elapsedRealtime) : les 6 s du secours partent de là.
    var mediaProblemSinceMs by remember(entry.key) { mutableStateOf<Long?>(null) }
    // Erreurs audio de la session, comptées hors état Compose : aucune recomposition.
    val audioErrors = remember(entry.key) { IntArray(1) }
    DisposableEffect(player, entry.key, sharedLivePlayer) {
        if (!sharedLivePlayer) return@DisposableEffect onDispose {}
        val listener = object : AnalyticsListener {
            override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
                audioErrors[0]++
            }

            override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
                audioErrors[0]++
            }
        }
        player.addAnalyticsListener(listener)
        onDispose { player.removeAnalyticsListener(listener) }
    }
    LaunchedEffect(entry.key, player, sharedLivePlayer) {
        if (!sharedLivePlayer) return@LaunchedEffect
        frameMonitor.reset(SystemClock.elapsedRealtime())
        var playingSinceMs = -1L
        var lastRecordAtMs = 0L
        while (true) {
            delay(LIVE_CHECK_INTERVAL_MS)
            val now = SystemClock.elapsedRealtime()
            val counters = player.videoDecoderCounters
            counters?.ensureUpdated()
            val rendered = counters?.renderedOutputBufferCount ?: 0
            val playing = player.isPlaying && player.playbackState == Player.STATE_READY
            val frozenMs = frameMonitor.sample(now, rendered, counters?.droppedBufferCount ?: 0, playing)
            if (!playing) {
                playingSinceMs = -1L
                continue
            }
            if (playingSinceMs < 0L) playingSinceMs = now
            if (!liveFramesSeen && rendered > 0) liveFramesSeen = true
            if (now - playingSinceMs < LIVE_CHECK_SETTLE_MS) continue

            val tracks = player.currentTracks
            val hasVideoTrack = tracks.containsType(C.TRACK_TYPE_VIDEO)
            // Mode tunnel : l'image va directement au matériel, les compteurs d'images ne bougent pas.
            val pictureMissing = !hasVideoTrack || (!appSettings.tunnelingEnabled && frozenMs >= LIVE_CHECK_SETTLE_MS)
            val audio = liveAudioIssue(
                hasVideoTrack = hasVideoTrack,
                audioTrackGroups = tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO },
                anyAudioSelected = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO),
                audioErrors = audioErrors[0],
            )
            if (pictureMissing != noPicture || audio != audioIssue) {
                val problem = pictureMissing || audio != null
                val since = if (pictureMissing && hasVideoTrack) now - frozenMs else playingSinceMs
                mediaProblemSinceMs = if (problem) mediaProblemSinceMs ?: since else null
                noPicture = pictureMissing
                audioIssue = audio
            }
            if (now - playingSinceMs >= LIVE_CHECK_RECORD_AFTER_MS && (!liveChecked || now - lastRecordAtMs >= LIVE_VERSION_WATCH_SAMPLE_MS)) {
                val info = player.videoFormat?.let(::liveTechnicalInfo)
                versionStatsStore.recordCheck(
                    versionScope,
                    entry.key,
                    LiveVersionCheck(
                        width = info?.width,
                        height = info?.height,
                        declaredFrameRate = info?.frameRate,
                        realFrameRate = frameMonitor.realFps,
                        codec = info?.codec,
                        bitrate = info?.bitrate,
                        hdr = info?.hdr,
                        droppedRatio = frameMonitor.droppedRatio,
                        noPicture = pictureMissing,
                        noSound = audio != null,
                    ),
                )
                lastRecordAtMs = now
                if (!liveChecked) {
                    liveChecked = true
                    versionStatsRevision++
                }
            }
        }
    }
    // Stabilité : temps regardé et coupures, relevés chaque minute de lecture effective.
    LaunchedEffect(entry.key, player) {
        if (entry.type != MediaType.Live) return@LaunchedEffect
        var reportedRebuffers = 0
        while (true) {
            delay(LIVE_VERSION_WATCH_SAMPLE_MS)
            val rebuffers = diagnostics.rebufferCount
            if (player.isPlaying || rebuffers > reportedRebuffers) {
                versionStatsStore.recordWatch(versionScope, entry.key, LIVE_VERSION_WATCH_SAMPLE_MS, (rebuffers - reportedRebuffers).coerceAtLeast(0))
            }
            reportedRebuffers = rebuffers
        }
    }
    // Version choisie dans le panneau qui ne démarre pas : échec mémorisé et retour sur la précédente.
    var versionSwitch by remember { mutableStateOf<LiveVersionSwitch?>(null) }
    var versionNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.key, playbackError, streamHasPlayed) {
        if (entry.type != MediaType.Live) return@LaunchedEffect
        val pending = versionSwitch
        if (pending != null && pending.targetKey != entry.key) {
            // Zap ailleurs entre-temps : plus de retour automatique.
            versionSwitch = null
            return@LaunchedEffect
        }
        if (streamHasPlayed) {
            versionSwitch = null
            return@LaunchedEffect
        }
        if (playbackError == null) return@LaunchedEffect
        versionStatsStore.recordFailure(versionScope, entry.key)
        versionStatsRevision++
        if (pending != null) {
            versionSwitch = null
            versionNotice = "${entry.displayName} ne répond pas · retour sur ${pending.from.displayName}"
            onSwitchVersion(pending.from)
        }
    }
    LaunchedEffect(versionNotice) {
        if (versionNotice == null) return@LaunchedEffect
        delay(4_000)
        versionNotice = null
    }

    // Secours automatique (Paramètres › Lecture & direct) : voir LiveFailoverRules. Actif seulement
    // s'il existe une autre version de la même langue ; ni compteur ni minuterie sinon.
    val online by remember(context) { NetworkMonitor.get(context.applicationContext).online }.collectAsState()
    // « Tester toutes les versions » : chaque version est lancée à l'écran l'une après l'autre (une
    // seule connexion à la fois) le temps d'un contrôle réel, puis la meilleure est gardée.
    var versionScan by remember { mutableStateOf<LiveVersionScan?>(null) }
    val hasFailoverTargets = remember(entry.key, liveVersions) { hasFailoverCandidates(entry, liveVersions) }
    val failoverActive = appSettings.liveVersionFailover && sharedLivePlayer && versionScan == null && hasFailoverTargets
    var failoverChain by remember { mutableStateOf<LiveFailoverChain?>(null) }
    // Compteur de coupures propre à la version en cours : il repart de zéro sur la suivante.
    val cutCounter = remember(entry.key) { LiveCutCounter() }
    // Début de la coupure en cours (horloge elapsedRealtime) et si l'image avait déjà été affichée.
    var outageStart by remember(entry.key) { mutableStateOf<LiveOutageStart?>(null) }
    val hasPlayed = streamHasPlayed || liveFramesSeen
    // Coupure : chargement, erreur, image absente ou figée, ou son absent.
    val outage = buffering || playbackError != null || noPicture || audioIssue != null

    fun rankedVersionsNow() = rankLiveVersions(
        entry, liveVersions, versionStatsStore.load(versionScope, liveVersions.map { it.key }), System.currentTimeMillis(), maxDisplayHeight,
    )

    fun runFailover(reason: String) {
        if (!failoverActive) return
        if (!hasPlayed) versionStatsStore.recordFailure(versionScope, entry.key)
        // Version choisie à la main dans le panneau qui ne répond pas : retour sur la précédente.
        val pending = versionSwitch
        if (pending != null && pending.targetKey == entry.key) {
            versionSwitch = null
            versionNotice = "${entry.displayName} ne répond pas · retour sur ${pending.from.displayName}"
            onSwitchVersion(pending.from)
            return
        }
        val decision = decideLiveFailover(entry, rankedVersionsNow(), failoverChain, System.currentTimeMillis())
        failoverChain = decision.chain
        val target = decision.target
        when {
            target != null && decision.backToOrigin ->
                versionNotice = "Aucune autre version ne fonctionne · retour sur ${target.displayName}"
            target != null ->
                versionNotice = "${entry.displayName} $reason · passage sur ${target.displayName}"
            decision.justExhausted ->
                versionNotice = "Aucune autre version disponible · nouvel essai sur ${entry.displayName}"
        }
        if (target != null) onSwitchVersion(target)
    }

    // Suivi des coupures : début quand l'image s'arrête, fin quand elle revient. Internet coupé chez
    // l'utilisateur : rien n'est compté (toutes les versions seraient coupées), la minuterie repart
    // au retour du réseau.
    LaunchedEffect(entry.key, outage, online) {
        val now = SystemClock.elapsedRealtime()
        when {
            outage && online -> if (outageStart == null) {
                // Image figée ou son absent : détectés après coup, la coupure date de leur vrai début.
                val mediaOnly = !buffering && playbackError == null
                outageStart = LiveOutageStart(mediaProblemSinceMs?.takeIf { mediaOnly } ?: now, afterPlayback = hasPlayed)
            }
            outage -> outageStart = null
            else -> {
                val ended = outageStart ?: return@LaunchedEffect
                outageStart = null
                // Le chargement initial n'est pas une coupure ; seules comptent celles après l'image.
                if (failoverActive && ended.afterPlayback && cutCounter.onCutEnded(now - ended.atMs, System.currentTimeMillis())) {
                    runFailover("coupe souvent")
                }
            }
        }
    }
    // Coupure (ou démarrage) qui dure : bascule au bout de 6 s.
    LaunchedEffect(entry.key, outageStart, failoverActive) {
        val start = outageStart ?: return@LaunchedEffect
        if (!failoverActive) return@LaunchedEffect
        val remaining = LiveFailoverRules.SWITCH_AFTER_OUTAGE_MS - (SystemClock.elapsedRealtime() - start.atMs)
        if (remaining > 0) delay(remaining)
        runFailover(
            when {
                !start.afterPlayback -> "ne démarre pas"
                noPicture -> "n'affiche pas d'image"
                audioIssue != null -> "n'a pas de son"
                else -> "coupe"
            },
        )
    }

    fun startVersionScan() {
        if (!hasOtherVersions || versionScan != null) return
        val others = rankedVersionsNow().map { it.entry }.filter { it.key != entry.key }
        versionSwitch = null
        versionScan = LiveVersionScan(origin = entry, queue = (listOf(entry) + others).take(MAX_SCANNED_VERSIONS), index = 0)
    }

    fun cancelVersionScan() {
        val scan = versionScan ?: return
        versionScan = null
        versionNotice = "Test annulé"
        if (entry.key != scan.origin.key) onSwitchVersion(scan.origin)
    }

    LaunchedEffect(versionScan?.index, entry.key) {
        val scan = versionScan ?: return@LaunchedEffect
        val target = scan.queue.getOrNull(scan.index) ?: return@LaunchedEffect
        if (target.key != entry.key) {
            onSwitchVersion(target)
            return@LaunchedEffect
        }
        // Contrôle réel de cette version (image, fps, son), ou échec au bout du délai.
        val deadline = SystemClock.elapsedRealtime() + LIVE_SCAN_TIMEOUT_MS
        while (!liveChecked && playbackError == null && SystemClock.elapsedRealtime() < deadline) delay(250)
        // Aucune image dans le délai : échec. Une image lente à se stabiliser n'est pas un échec.
        if (!liveChecked && !liveFramesSeen && !streamHasPlayed) versionStatsStore.recordFailure(versionScope, entry.key)
        versionStatsRevision++
        if (scan.index + 1 < scan.queue.size) {
            versionScan = scan.copy(index = scan.index + 1)
            return@LaunchedEffect
        }
        versionScan = null
        val best = rankedVersionsNow().firstOrNull {
            it.sameLanguage && (it.health == LiveVersionHealth.Stable || it.health == LiveVersionHealth.Choppy)
        }?.entry
        when {
            best == null -> {
                versionNotice = "Test terminé · aucune version ne fonctionne correctement"
                if (entry.key != scan.origin.key) onSwitchVersion(scan.origin)
            }
            best.key != entry.key -> {
                versionNotice = "Test terminé · meilleure version : ${best.displayName}"
                onSwitchVersion(best)
            }
            else -> versionNotice = "Test terminé · meilleure version : ${best.displayName}"
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
    // Retour du panneau « Versions » : le focus revient sur la ligne « Version ».
    LaunchedEffect(versionsOpen) {
        if (!versionsOpen && settingsOpen) {
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
            versionScan != null -> cancelVersionScan()
            settingsOpen && versionsOpen -> { settingsOpen = false; versionsOpen = false; rootFocus.requestFocus() }
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
            // Direct : la vidéo est posée sous l'écran par StreamiaApp, un fond ici la masquerait.
            .then(if (sharedLivePlayer) Modifier else Modifier.background(Color.Black))
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
                    PlaybackRemoteAction.OpenSettings -> { versionsOpen = false; settingsOpen = true; hudVisible = true; true }
                    // → en Direct : directement la liste des versions (réglages de lecture s'il n'y en a qu'une).
                    PlaybackRemoteAction.OpenVersions -> { versionsOpen = hasOtherVersions; settingsOpen = true; hudVisible = true; true }
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
                positionMs = { positionState.longValue },
                durationMs = { durationState.longValue },
                otherVersionsCount = (liveVersions.size - 1).coerceAtLeast(0),
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
        versionNotice?.let { notice ->
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(34.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(RadiusTile))
                    .background(Night.copy(alpha = 0.86f))
                    .border(BorderStroke(1.dp, GlassBorder), androidx.compose.foundation.shape.RoundedCornerShape(RadiusTile))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
            ) {
                Text(notice, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
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
                SeekFeedbackText(feedback) { positionState.longValue }
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

        if (settingsOpen && versionsOpen && hasOtherVersions) {
            LiveVersionsPanel(
                channelName = entry.displayName,
                options = versionOptions,
                currentLoading = buffering && playbackError == null,
                currentFailed = playbackError != null,
                currentProblem = if (noPicture) "Pas d'image" else audioIssue?.label?.replaceFirstChar(Char::uppercase),
                scanProgress = versionScan?.let { "Test ${it.index + 1} / ${it.queue.size} · ${it.queue[it.index].displayName}" },
                scanCount = minOf(liveVersions.size, MAX_SCANNED_VERSIONS),
                onSelect = { target ->
                    versionSwitch = LiveVersionSwitch(from = entry, targetKey = target.key)
                    onSwitchVersion(target)
                },
                // Deuxième OK sur la version en cours : fermeture du panneau.
                onClose = { settingsOpen = false; versionsOpen = false; rootFocus.requestFocus() },
                onStartScan = ::startVersionScan,
                onCancelScan = ::cancelVersionScan,
                onOpenPlaybackSettings = { versionsOpen = false },
            )
        } else if (settingsOpen) {
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
                onClose = { settingsOpen = false; versionsOpen = false; rootFocus.requestFocus() },
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

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

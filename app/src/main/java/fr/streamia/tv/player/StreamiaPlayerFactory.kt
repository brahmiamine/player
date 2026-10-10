package fr.streamia.tv.player

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import fr.streamia.tv.data.BufferMode
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.logging.CrashReporter
import okhttp3.OkHttpClient

/** ~35 % de la mémoire Java de l'app (tampon ExoPlayer alloué sur le tas), entre 32 et 200 Mo. */
internal fun bufferBytesForHeap(heapMb: Int): Int =
    (heapMb * 0.35 * 1024 * 1024).toLong().coerceIn(32L * 1024 * 1024, 200L * 1024 * 1024).toInt()

private fun targetBufferBytes(context: Context): Int {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    // largeHeap déclaré dans le manifeste : c'est largeMemoryClass qui s'applique.
    return bufferBytesForHeap(activityManager.largeMemoryClass)
}

/** Lecture déjà passée gardée en mémoire (VOD), à partir de l'image clé qui la précède. */
private const val VOD_BACK_BUFFER_MS = 10_000

/** Plus longue attente imposée après des coupures répétées. */
private const val MAX_REBUFFER_MARGIN_MS = 12_000

object StreamiaPlayerFactory {
    // Client partagé avec l'API et les images : la connexion au serveur du fournisseur (TCP, TLS)
    // est souvent déjà ouverte au moment du zap.
    private val httpClient: OkHttpClient get() = fr.streamia.tv.net.HttpClients.player

    fun create(
        context: Context,
        mediaType: MediaType,
        bufferMode: BufferMode = BufferMode.Auto,
        tunneling: Boolean = false,
    ): ExoPlayer {
        val profile = PlaybackTuning.forType(mediaType, bufferMode)
        val bufferBytes = targetBufferBytes(context)
        val defaultLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                // Minimum = maximum : chargement continu par petites touches. Avec un écart (25 s →
                // 90 s), le lecteur cessait de lire la connexion jusqu'à redescendre au minimum,
                // souvent plus d'une minute en 4K (mémoire pleine avant 90 s) : le serveur IPTV
                // coupait cette connexion inactive, d'où coupures et reconnexions après quelques
                // minutes de lecture. Sur le Direct, ne plus lire le flux fait aussi décrocher le serveur.
                profile.maxBufferMs,
                profile.maxBufferMs,
                profile.bufferForPlaybackMs,
                profile.bufferForPlaybackAfterRebufferMs,
            )
            // La durée ne passe plus avant la taille : 90 s d'un film 4K à 50 Mbit/s représentaient
            // plus de 500 Mo en mémoire (coupures, voire plantage sur un boîtier à 2 Go). Le tampon
            // s'arrête désormais à une part de la mémoire allouée à l'app.
            .setTargetBufferBytes(bufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .apply {
                // Films/séries : quelques secondes déjà vues gardées depuis l'image clé précédente.
                // Activer une piste de sous-titres intégrée oblige ExoPlayer à repositionner le flux :
                // sans image clé en mémoire, il coupait la connexion et retéléchargeait depuis le
                // serveur (image figée, longue reconnexion). Avec elle, le repositionnement se fait
                // en mémoire (un court rafraîchissement du décodeur). Sert aussi aux petits retours arrière.
                if (mediaType != MediaType.Live) setBackBuffer(VOD_BACK_BUFFER_MS, true)
            }
            .build()
        // Coupures répétées : marge de reprise doublée à chaque fois (plafonnée sous le tampon maximal).
        val loadControl = AdaptiveRebufferLoadControl(
            delegate = defaultLoadControl,
            baseRebufferMarginMs = profile.bufferForPlaybackAfterRebufferMs,
            maxRebufferMarginMs = (profile.maxBufferMs / 2).coerceAtMost(MAX_REBUFFER_MARGIN_MS),
            targetBufferBytes = bufferBytes,
        )
        // Mode tunnel : le boîtier synchronise lui-même image et son (4K HDR plus fluide sur les
        // TV qui le gèrent). Ignoré automatiquement quand le décodeur ou l'audio ne le permettent pas.
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(tunneling))
        }
        val httpDataSourceFactory = OkHttpDataSource.Factory(httpClient)
            .setUserAgent(fr.streamia.tv.net.HttpClients.USER_AGENT)
        // DefaultMediaSourceFactory reuses this exact factory for every source it builds, including
        // externally loaded subtitle files (SingleSampleMediaSource). An OkHttpDataSource.Factory alone
        // only understands http(s) — a local subtitle picked via SAF (content://) would fail to load.
        // DefaultDataSource.Factory keeps OkHttp for http(s) and adds file/content/asset resolution on top.
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        // Direct en MPEG-TS : décodage dès la première image clé (même non IDR, fréquent en IPTV) et
        // unités d'accès détectées sans délimiteur, pour une première image plus rapide au zap.
        val extractorsFactory = DefaultExtractorsFactory().apply {
            if (mediaType == MediaType.Live) {
                setTsExtractorFlags(
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
                )
            }
        }
        val liveErrorPolicy = if (mediaType == MediaType.Live) LiveLoadErrorPolicy() else null
        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(dataSourceFactory)
            // Le Direct a sa propre bascule d'URL (TS/HLS, HTTP→HTTPS) : 5 relances par URL avant d'y
            // passer laissaient l'écran noir plusieurs dizaines de secondes sur une chaîne morte.
            // Une fois l'image affichée, les relances passent à 6 (voir LiveLoadErrorPolicy).
            .setLoadErrorHandlingPolicy(liveErrorPolicy ?: DefaultLoadErrorHandlingPolicy(5))
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            // Décodeurs du boîtier d'abord, FFmpeg seulement pour les formats audio qu'il ne sait pas lire.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                playWhenReady = true
                setHandleAudioBecomingNoisy(true)
            }

        if (liveErrorPolicy != null) {
            player.addListener(
                object : Player.Listener {
                    override fun onRenderedFirstFrame() { liveErrorPolicy.playing = true }
                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { liveErrorPolicy.playing = false }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_IDLE) liveErrorPolicy.playing = false
                    }
                },
            )
        }

        var trackedUrl = ""
        var bufferStarts = 0
        var readyLoggedForUrl = ""

        fun currentUrl(): String =
            player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()

        fun trackNewUrlIfNeeded() {
            val url = currentUrl()
            if (url.isBlank() || url == trackedUrl) return
            trackedUrl = url
            bufferStarts = 0
            readyLoggedForUrl = ""
            CrashReporter.playerAttempt(mediaType, url)
        }

        val playerId = System.identityHashCode(player)
        player.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) =
                    PlaybackActivity.update(playerId, isPlaying)

                override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) =
                    PlaybackActivity.update(playerId, isPlaying = false)
            },
        )

        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    trackNewUrlIfNeeded()
                    if (playbackState == Player.STATE_BUFFERING) {
                        bufferStarts += 1
                    } else if (playbackState == Player.STATE_READY) {
                        val url = currentUrl()
                        if (url.isNotBlank() && readyLoggedForUrl != url) {
                            readyLoggedForUrl = url
                            CrashReporter.playerReady(mediaType, url, bufferStarts)
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    trackNewUrlIfNeeded()
                    CrashReporter.recordPlayerError(
                        mediaType = mediaType,
                        rawUrl = currentUrl(),
                        errorCode = error.errorCode,
                        errorType = error::class.java.simpleName,
                        bufferStarts = bufferStarts,
                    )
                }
            },
        )

        return player
    }
}

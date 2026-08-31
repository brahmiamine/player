package fr.streamia.tv.player

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.logging.CrashReporter
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object StreamiaPlayerFactory {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }

    fun create(context: Context, mediaType: MediaType): ExoPlayer {
        val profile = PlaybackTuning.forType(mediaType)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minBufferMs,
                profile.maxBufferMs,
                profile.bufferForPlaybackMs,
                profile.bufferForPlaybackAfterRebufferMs,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
            .setUserAgent("Streamia-TV/1.5")
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(5))
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                setHandleAudioBecomingNoisy(true)
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

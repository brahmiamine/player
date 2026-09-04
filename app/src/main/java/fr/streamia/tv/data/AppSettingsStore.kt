package fr.streamia.tv.data

import android.content.Context

enum class VideoAspectSetting { Fit, Fill, Zoom }
enum class BufferMode { LowLatency, Auto, Stable }
enum class LiveStreamFormat { Auto, Ts, Hls }

data class AppSettings(
    val livePreviewEnabled: Boolean = true,
    val livePreviewDelayMs: Int = DEFAULT_LIVE_PREVIEW_DELAY_MS,
    val vodSeekStepSeconds: Int = DEFAULT_VOD_SEEK_STEP_SECONDS,
    val videoAspect: VideoAspectSetting = VideoAspectSetting.Fit,
    val bufferMode: BufferMode = BufferMode.Auto,
    val liveStreamFormat: LiveStreamFormat = LiveStreamFormat.Auto,
) {
    val vodSeekStepMs: Long
        get() = vodSeekStepSeconds * 1_000L

    fun nextLivePreviewDelayMs(): Int =
        nextValue(LIVE_PREVIEW_DELAYS_MS, livePreviewDelayMs)

    fun nextVodSeekStepSeconds(): Int =
        nextValue(VOD_SEEK_STEPS_SECONDS, vodSeekStepSeconds)

    fun nextVideoAspect(): VideoAspectSetting =
        VideoAspectSetting.entries[(videoAspect.ordinal + 1) % VideoAspectSetting.entries.size]

    fun nextBufferMode(): BufferMode =
        BufferMode.entries[(bufferMode.ordinal + 1) % BufferMode.entries.size]

    fun nextLiveStreamFormat(): LiveStreamFormat =
        LiveStreamFormat.entries[(liveStreamFormat.ordinal + 1) % LiveStreamFormat.entries.size]

    companion object {
        val LIVE_PREVIEW_DELAYS_MS = listOf(0, 250, 500, 1_000, 2_000)
        val VOD_SEEK_STEPS_SECONDS = listOf(10, 30, 60)
        const val DEFAULT_LIVE_PREVIEW_DELAY_MS = 250
        const val DEFAULT_VOD_SEEK_STEP_SECONDS = 10

        private fun nextValue(values: List<Int>, current: Int): Int {
            val index = values.indexOf(current)
            return values[(if (index >= 0) index + 1 else 0) % values.size]
        }
    }
}

class AppSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        livePreviewEnabled = preferences.getBoolean(KEY_LIVE_PREVIEW_ENABLED, true),
        livePreviewDelayMs = preferences.getInt(
            KEY_LIVE_PREVIEW_DELAY_MS,
            AppSettings.DEFAULT_LIVE_PREVIEW_DELAY_MS,
        ).takeIf { it in AppSettings.LIVE_PREVIEW_DELAYS_MS }
            ?: AppSettings.DEFAULT_LIVE_PREVIEW_DELAY_MS,
        vodSeekStepSeconds = preferences.getInt(
            KEY_VOD_SEEK_STEP_SECONDS,
            AppSettings.DEFAULT_VOD_SEEK_STEP_SECONDS,
        ).takeIf { it in AppSettings.VOD_SEEK_STEPS_SECONDS }
            ?: AppSettings.DEFAULT_VOD_SEEK_STEP_SECONDS,
        videoAspect = runCatching {
            VideoAspectSetting.valueOf(
                preferences.getString(KEY_VIDEO_ASPECT, VideoAspectSetting.Fit.name)
                    ?: VideoAspectSetting.Fit.name,
            )
        }.getOrDefault(VideoAspectSetting.Fit),
        bufferMode = runCatching {
            BufferMode.valueOf(
                preferences.getString(KEY_BUFFER_MODE, BufferMode.Auto.name) ?: BufferMode.Auto.name,
            )
        }.getOrDefault(BufferMode.Auto),
        liveStreamFormat = runCatching {
            LiveStreamFormat.valueOf(
                preferences.getString(KEY_LIVE_STREAM_FORMAT, LiveStreamFormat.Auto.name)
                    ?: LiveStreamFormat.Auto.name,
            )
        }.getOrDefault(LiveStreamFormat.Auto),
    )

    fun save(settings: AppSettings) {
        preferences.edit()
            .putBoolean(KEY_LIVE_PREVIEW_ENABLED, settings.livePreviewEnabled)
            .putInt(KEY_LIVE_PREVIEW_DELAY_MS, settings.livePreviewDelayMs)
            .putInt(KEY_VOD_SEEK_STEP_SECONDS, settings.vodSeekStepSeconds)
            .putString(KEY_VIDEO_ASPECT, settings.videoAspect.name)
            .putString(KEY_BUFFER_MODE, settings.bufferMode.name)
            .putString(KEY_LIVE_STREAM_FORMAT, settings.liveStreamFormat.name)
            .apply()
    }

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val updated = transform(load())
        save(updated)
        return updated
    }

    private companion object {
        const val PREFERENCES_NAME = "streamia-app-settings-v1"
        const val KEY_LIVE_PREVIEW_ENABLED = "live_preview_enabled"
        const val KEY_LIVE_PREVIEW_DELAY_MS = "live_preview_delay_ms"
        const val KEY_VOD_SEEK_STEP_SECONDS = "vod_seek_step_seconds"
        const val KEY_VIDEO_ASPECT = "video_aspect"
        const val KEY_BUFFER_MODE = "buffer_mode"
        const val KEY_LIVE_STREAM_FORMAT = "live_stream_format"
    }
}

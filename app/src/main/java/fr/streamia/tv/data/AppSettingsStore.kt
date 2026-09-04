package fr.streamia.tv.data

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

enum class VideoAspectSetting { Fit, Fill, Zoom }
enum class BufferMode { LowLatency, Auto, Stable }
enum class LiveStreamFormat { Auto, Ts, Hls }
enum class LiveChannelSortOrder { Provider, Number, Alphabetical }
enum class VodSortOrder { Provider, Alphabetical, RecentlyAdded, Rating }

data class AppSettings(
    val livePreviewEnabled: Boolean = true,
    val livePreviewDelayMs: Int = DEFAULT_LIVE_PREVIEW_DELAY_MS,
    val vodSeekStepSeconds: Int = DEFAULT_VOD_SEEK_STEP_SECONDS,
    val videoAspect: VideoAspectSetting = VideoAspectSetting.Fit,
    val bufferMode: BufferMode = BufferMode.Auto,
    val liveStreamFormat: LiveStreamFormat = LiveStreamFormat.Auto,
    val liveChannelSortOrder: LiveChannelSortOrder = LiveChannelSortOrder.Provider,
    val vodSortOrder: VodSortOrder = VodSortOrder.Provider,
    val epgTimeOffsetHours: Int = 0,
    val autoPlayNextEpisode: Boolean = true,
    val subtitleSizeScale: Float = 1.0f,
    val subtitleBackgroundEnabled: Boolean = true,
    /** Un code est enregistré (voir [AppSettingsStore.setParentalPin]) et le verrouillage est actif. */
    val parentalControlEnabled: Boolean = false,
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

    fun nextLiveChannelSortOrder(): LiveChannelSortOrder =
        LiveChannelSortOrder.entries[(liveChannelSortOrder.ordinal + 1) % LiveChannelSortOrder.entries.size]

    fun nextVodSortOrder(): VodSortOrder =
        VodSortOrder.entries[(vodSortOrder.ordinal + 1) % VodSortOrder.entries.size]

    fun nextEpgTimeOffsetHours(): Int =
        nextValue(EPG_TIME_OFFSETS_HOURS, epgTimeOffsetHours)

    fun nextSubtitleSizeScale(): Float {
        val index = SUBTITLE_SIZE_SCALES.indexOf(subtitleSizeScale)
        return SUBTITLE_SIZE_SCALES[(if (index >= 0) index + 1 else 0) % SUBTITLE_SIZE_SCALES.size]
    }

    companion object {
        val LIVE_PREVIEW_DELAYS_MS = listOf(0, 250, 500, 1_000, 2_000)
        val VOD_SEEK_STEPS_SECONDS = listOf(10, 30, 60)
        val EPG_TIME_OFFSETS_HOURS = listOf(-3, -2, -1, 0, 1, 2, 3)
        val SUBTITLE_SIZE_SCALES = listOf(0.75f, 1.0f, 1.25f, 1.5f)
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
        liveChannelSortOrder = runCatching {
            LiveChannelSortOrder.valueOf(
                preferences.getString(KEY_LIVE_CHANNEL_SORT_ORDER, LiveChannelSortOrder.Provider.name)
                    ?: LiveChannelSortOrder.Provider.name,
            )
        }.getOrDefault(LiveChannelSortOrder.Provider),
        vodSortOrder = runCatching {
            VodSortOrder.valueOf(
                preferences.getString(KEY_VOD_SORT_ORDER, VodSortOrder.Provider.name) ?: VodSortOrder.Provider.name,
            )
        }.getOrDefault(VodSortOrder.Provider),
        epgTimeOffsetHours = preferences.getInt(KEY_EPG_TIME_OFFSET_HOURS, 0)
            .takeIf { it in AppSettings.EPG_TIME_OFFSETS_HOURS } ?: 0,
        autoPlayNextEpisode = preferences.getBoolean(KEY_AUTO_PLAY_NEXT_EPISODE, true),
        subtitleSizeScale = preferences.getFloat(KEY_SUBTITLE_SIZE_SCALE, 1.0f)
            .takeIf { it in AppSettings.SUBTITLE_SIZE_SCALES } ?: 1.0f,
        subtitleBackgroundEnabled = preferences.getBoolean(KEY_SUBTITLE_BACKGROUND_ENABLED, true),
        parentalControlEnabled = preferences.getBoolean(KEY_PARENTAL_ENABLED, false) &&
            preferences.getString(KEY_PARENTAL_PIN_HASH, null) != null,
    )

    fun save(settings: AppSettings) {
        preferences.edit()
            .putBoolean(KEY_LIVE_PREVIEW_ENABLED, settings.livePreviewEnabled)
            .putInt(KEY_LIVE_PREVIEW_DELAY_MS, settings.livePreviewDelayMs)
            .putInt(KEY_VOD_SEEK_STEP_SECONDS, settings.vodSeekStepSeconds)
            .putString(KEY_VIDEO_ASPECT, settings.videoAspect.name)
            .putString(KEY_BUFFER_MODE, settings.bufferMode.name)
            .putString(KEY_LIVE_STREAM_FORMAT, settings.liveStreamFormat.name)
            .putString(KEY_LIVE_CHANNEL_SORT_ORDER, settings.liveChannelSortOrder.name)
            .putString(KEY_VOD_SORT_ORDER, settings.vodSortOrder.name)
            .putInt(KEY_EPG_TIME_OFFSET_HOURS, settings.epgTimeOffsetHours)
            .putBoolean(KEY_AUTO_PLAY_NEXT_EPISODE, settings.autoPlayNextEpisode)
            .putFloat(KEY_SUBTITLE_SIZE_SCALE, settings.subtitleSizeScale)
            .putBoolean(KEY_SUBTITLE_BACKGROUND_ENABLED, settings.subtitleBackgroundEnabled)
            .putBoolean(KEY_PARENTAL_ENABLED, settings.parentalControlEnabled)
            .apply()
    }

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val updated = transform(load())
        save(updated)
        return updated
    }

    /**
     * Enregistre un nouveau code parental et active le verrouillage. Le code lui-même n'est
     * jamais stocké : seuls un sel aléatoire et le hachage salé sont conservés, en dehors de
     * [AppSettings] (qui, lui, transite par l'état d'interface) pour qu'aucune empreinte du code
     * ne circule au-delà de ce store.
     */
    fun setParentalPin(pin: String): AppSettings {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes).toHex()
        preferences.edit()
            .putString(KEY_PARENTAL_PIN_SALT, salt)
            .putString(KEY_PARENTAL_PIN_HASH, hashPin(pin, salt))
            .putBoolean(KEY_PARENTAL_ENABLED, true)
            .apply()
        return load()
    }

    /** Désactive le verrouillage parental et oublie le code enregistré. */
    fun clearParentalPin(): AppSettings {
        preferences.edit()
            .remove(KEY_PARENTAL_PIN_SALT)
            .remove(KEY_PARENTAL_PIN_HASH)
            .putBoolean(KEY_PARENTAL_ENABLED, false)
            .apply()
        return load()
    }

    fun verifyParentalPin(pin: String): Boolean {
        val salt = preferences.getString(KEY_PARENTAL_PIN_SALT, null) ?: return false
        val storedHash = preferences.getString(KEY_PARENTAL_PIN_HASH, null) ?: return false
        return hashPin(pin, salt) == storedHash
    }

    private companion object {
        const val PREFERENCES_NAME = "streamia-app-settings-v1"
        const val KEY_LIVE_PREVIEW_ENABLED = "live_preview_enabled"
        const val KEY_LIVE_PREVIEW_DELAY_MS = "live_preview_delay_ms"
        const val KEY_VOD_SEEK_STEP_SECONDS = "vod_seek_step_seconds"
        const val KEY_VIDEO_ASPECT = "video_aspect"
        const val KEY_BUFFER_MODE = "buffer_mode"
        const val KEY_LIVE_STREAM_FORMAT = "live_stream_format"
        const val KEY_LIVE_CHANNEL_SORT_ORDER = "live_channel_sort_order"
        const val KEY_VOD_SORT_ORDER = "vod_sort_order"
        const val KEY_EPG_TIME_OFFSET_HOURS = "epg_time_offset_hours"
        const val KEY_AUTO_PLAY_NEXT_EPISODE = "auto_play_next_episode"
        const val KEY_SUBTITLE_SIZE_SCALE = "subtitle_size_scale"
        const val KEY_SUBTITLE_BACKGROUND_ENABLED = "subtitle_background_enabled"
        const val KEY_PARENTAL_ENABLED = "parental_control_enabled"
        const val KEY_PARENTAL_PIN_SALT = "parental_pin_salt"
        const val KEY_PARENTAL_PIN_HASH = "parental_pin_hash"
    }
}

/**
 * Hachage salé d'un code parental, extrait en fonction pure (plutôt que méthode privée de
 * [AppSettingsStore]) pour rester testable sans `Context` Android — ce module n'a pas Robolectric.
 */
internal fun hashPin(pin: String, salt: String): String =
    MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray(Charsets.UTF_8)).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Pour une sauvegarde/restauration (voir [BackupManager]) : ne couvre volontairement pas
 * [AppSettings.parentalControlEnabled], dérivé d'un code jamais exporté — le réimporter à `true`
 * sans code fonctionnel verrouillerait l'utilisateur hors de son propre contenu.
 */
fun AppSettings.toBackupJson(): JSONObject = JSONObject().apply {
    put("livePreviewEnabled", livePreviewEnabled)
    put("livePreviewDelayMs", livePreviewDelayMs)
    put("vodSeekStepSeconds", vodSeekStepSeconds)
    put("videoAspect", videoAspect.name)
    put("bufferMode", bufferMode.name)
    put("liveStreamFormat", liveStreamFormat.name)
    put("liveChannelSortOrder", liveChannelSortOrder.name)
    put("vodSortOrder", vodSortOrder.name)
    put("epgTimeOffsetHours", epgTimeOffsetHours)
    put("autoPlayNextEpisode", autoPlayNextEpisode)
    put("subtitleSizeScale", subtitleSizeScale.toDouble())
    put("subtitleBackgroundEnabled", subtitleBackgroundEnabled)
}

fun appSettingsFromBackupJson(json: JSONObject, fallback: AppSettings): AppSettings = AppSettings(
    livePreviewEnabled = json.optBoolean("livePreviewEnabled", fallback.livePreviewEnabled),
    livePreviewDelayMs = json.optInt("livePreviewDelayMs", fallback.livePreviewDelayMs)
        .takeIf { it in AppSettings.LIVE_PREVIEW_DELAYS_MS } ?: fallback.livePreviewDelayMs,
    vodSeekStepSeconds = json.optInt("vodSeekStepSeconds", fallback.vodSeekStepSeconds)
        .takeIf { it in AppSettings.VOD_SEEK_STEPS_SECONDS } ?: fallback.vodSeekStepSeconds,
    videoAspect = runCatching { VideoAspectSetting.valueOf(json.getString("videoAspect")) }.getOrDefault(fallback.videoAspect),
    bufferMode = runCatching { BufferMode.valueOf(json.getString("bufferMode")) }.getOrDefault(fallback.bufferMode),
    liveStreamFormat = runCatching { LiveStreamFormat.valueOf(json.getString("liveStreamFormat")) }.getOrDefault(fallback.liveStreamFormat),
    liveChannelSortOrder = runCatching { LiveChannelSortOrder.valueOf(json.getString("liveChannelSortOrder")) }.getOrDefault(fallback.liveChannelSortOrder),
    vodSortOrder = runCatching { VodSortOrder.valueOf(json.getString("vodSortOrder")) }.getOrDefault(fallback.vodSortOrder),
    epgTimeOffsetHours = json.optInt("epgTimeOffsetHours", fallback.epgTimeOffsetHours)
        .takeIf { it in AppSettings.EPG_TIME_OFFSETS_HOURS } ?: fallback.epgTimeOffsetHours,
    autoPlayNextEpisode = json.optBoolean("autoPlayNextEpisode", fallback.autoPlayNextEpisode),
    subtitleSizeScale = json.optDouble("subtitleSizeScale", fallback.subtitleSizeScale.toDouble()).toFloat()
        .takeIf { it in AppSettings.SUBTITLE_SIZE_SCALES } ?: fallback.subtitleSizeScale,
    subtitleBackgroundEnabled = json.optBoolean("subtitleBackgroundEnabled", fallback.subtitleBackgroundEnabled),
    parentalControlEnabled = fallback.parentalControlEnabled,
)

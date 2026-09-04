package fr.streamia.tv.data

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

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

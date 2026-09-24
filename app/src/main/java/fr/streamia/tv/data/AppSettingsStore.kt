package fr.streamia.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

enum class VideoAspectSetting { Fit, Fill, Zoom }
enum class BufferMode { LowLatency, Auto, Stable }
enum class LiveStreamFormat { Auto, Ts, Hls }
enum class LiveChannelSortOrder { Provider, Number, Alphabetical }
enum class VodSortOrder { Provider, Alphabetical, RecentlyAdded, Rating }
enum class PrayerMethod { MuslimWorldLeague, France, Tunisia, Egypt, UmmAlQura, Karachi, NorthAmerica }

/** Un bloc de l'accueil que l'utilisateur peut activer/désactiver depuis Paramètres. */
enum class HomeBlock {
    Resume,
    Favorites,
    RecentChannels,
    FootballScores,
    LiveMatches,
    TvProgrammeNow,
    TvProgrammeTonight,
    BeinSportsNow,
    BeinSportsNext,
    UkGuideNow,
    UkGuideNext,
    /** Regroupe les deux rangées IA (principale + secondaire) : elles varient ensemble. */
    Recommendations,
    JustWatchTopMoviesWeek,
    JustWatchTopSeriesWeek,
    JustWatchPopularMovies,
    JustWatchPopularSeries,
    JustWatchNewMovies,
    JustWatchNewSeries,
    ;

    companion object {
        /** Désactivés tant que l'utilisateur ne les coche pas (guide UK : ~60 requêtes par chargement). */
        val DEFAULT_DISABLED: Set<HomeBlock> = setOf(UkGuideNow, UkGuideNext)
    }
}

val JustWatchSection.homeBlock: HomeBlock
    get() = when (this) {
        JustWatchSection.TopMoviesWeek -> HomeBlock.JustWatchTopMoviesWeek
        JustWatchSection.TopSeriesWeek -> HomeBlock.JustWatchTopSeriesWeek
        JustWatchSection.PopularMovies -> HomeBlock.JustWatchPopularMovies
        JustWatchSection.PopularSeries -> HomeBlock.JustWatchPopularSeries
        JustWatchSection.NewMovies -> HomeBlock.JustWatchNewMovies
        JustWatchSection.NewSeries -> HomeBlock.JustWatchNewSeries
    }

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
    /** Blocs de l'accueil désactivés par l'utilisateur. Vide = tous les blocs actifs (défaut). */
    val disabledHomeBlocks: Set<HomeBlock> = HomeBlock.DEFAULT_DISABLED,
    /** Ville de la météo et des prières de l'accueil ; null = détectée d'après la connexion. */
    val homePlace: HomePlace? = null,
    val prayerMethod: PrayerMethod = PrayerMethod.MuslimWorldLeague,
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
        disabledHomeBlocks = preferences.getStringSet(KEY_DISABLED_HOME_BLOCKS, null)
            ?.mapNotNullTo(mutableSetOf()) { name -> runCatching { HomeBlock.valueOf(name) }.getOrNull() }
            // Réglages enregistrés avant l'arrivée des blocs désactivés par défaut : appliqués une fois.
            ?.let { stored -> if (preferences.getBoolean(KEY_HOME_BLOCK_DEFAULTS_APPLIED, false)) stored else stored + HomeBlock.DEFAULT_DISABLED }
            ?: HomeBlock.DEFAULT_DISABLED,
        homePlace = runCatching {
            HomePlace(
                preferences.getString(KEY_HOME_PLACE_NAME, null)!!,
                preferences.getString(KEY_HOME_PLACE_LATITUDE, null)!!.toDouble(),
                preferences.getString(KEY_HOME_PLACE_LONGITUDE, null)!!.toDouble(),
            )
        }.getOrNull(),
        prayerMethod = runCatching {
            PrayerMethod.valueOf(preferences.getString(KEY_PRAYER_METHOD, null)!!)
        }.getOrDefault(PrayerMethod.MuslimWorldLeague),
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
            .putStringSet(KEY_DISABLED_HOME_BLOCKS, settings.disabledHomeBlocks.mapTo(mutableSetOf()) { it.name })
            .putBoolean(KEY_HOME_BLOCK_DEFAULTS_APPLIED, true)
            .putString(KEY_HOME_PLACE_NAME, settings.homePlace?.name)
            .putString(KEY_HOME_PLACE_LATITUDE, settings.homePlace?.latitude?.toString())
            .putString(KEY_HOME_PLACE_LONGITUDE, settings.homePlace?.longitude?.toString())
            .putString(KEY_PRAYER_METHOD, settings.prayerMethod.name)
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
            .putString(KEY_PARENTAL_PIN_HASH, slowHashPin(pin, salt))
            .putString(KEY_PARENTAL_PIN_ALGORITHM, PIN_ALGORITHM_PBKDF2)
            .putInt(KEY_PARENTAL_PIN_FAILURES, 0)
            .putLong(KEY_PARENTAL_PIN_LOCKED_UNTIL, 0L)
            .putBoolean(KEY_PARENTAL_ENABLED, true)
            .apply()
        return load()
    }

    /** Désactive le verrouillage parental et oublie le code enregistré. */
    fun clearParentalPin(): AppSettings {
        preferences.edit()
            .remove(KEY_PARENTAL_PIN_SALT)
            .remove(KEY_PARENTAL_PIN_HASH)
            .remove(KEY_PARENTAL_PIN_ALGORITHM)
            .remove(KEY_PARENTAL_PIN_FAILURES)
            .remove(KEY_PARENTAL_PIN_LOCKED_UNTIL)
            .putBoolean(KEY_PARENTAL_ENABLED, false)
            .apply()
        return load()
    }

    /**
     * Vérifie le code en respectant le blocage progressif après plusieurs échecs (persisté : relancer
     * l'app ne le remet pas à zéro). Un code à 4 chiffres n'a que 10 000 valeurs : sans ce blocage,
     * il se trouve à la télécommande.
     */
    fun verifyParentalPin(pin: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val salt = preferences.getString(KEY_PARENTAL_PIN_SALT, null) ?: return false
        val storedHash = preferences.getString(KEY_PARENTAL_PIN_HASH, null) ?: return false
        if (nowMillis < preferences.getLong(KEY_PARENTAL_PIN_LOCKED_UNTIL, 0L)) return false
        val legacy = preferences.getString(KEY_PARENTAL_PIN_ALGORITHM, null) != PIN_ALGORITHM_PBKDF2
        val correct = (if (legacy) hashPin(pin, salt) else slowHashPin(pin, salt)) == storedHash
        if (correct) {
            val edit = preferences.edit()
                .putInt(KEY_PARENTAL_PIN_FAILURES, 0)
                .putLong(KEY_PARENTAL_PIN_LOCKED_UNTIL, 0L)
            // Migration silencieuse de l'ancien SHA-256 rapide vers PBKDF2 au premier succès.
            if (legacy) {
                edit.putString(KEY_PARENTAL_PIN_HASH, slowHashPin(pin, salt))
                    .putString(KEY_PARENTAL_PIN_ALGORITHM, PIN_ALGORITHM_PBKDF2)
            }
            edit.apply()
        } else {
            val failures = preferences.getInt(KEY_PARENTAL_PIN_FAILURES, 0) + 1
            preferences.edit()
                .putInt(KEY_PARENTAL_PIN_FAILURES, failures)
                .putLong(KEY_PARENTAL_PIN_LOCKED_UNTIL, nowMillis + pinLockoutMillis(failures))
                .apply()
        }
        return correct
    }

    /** Temps restant avant de pouvoir retenter un code, 0 si aucun blocage. */
    fun parentalPinLockRemainingMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        (preferences.getLong(KEY_PARENTAL_PIN_LOCKED_UNTIL, 0L) - nowMillis).coerceAtLeast(0L)

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
        const val KEY_DISABLED_HOME_BLOCKS = "disabled_home_blocks"
        const val KEY_HOME_BLOCK_DEFAULTS_APPLIED = "home_block_defaults_applied"
        const val KEY_HOME_PLACE_NAME = "home_place_name"
        const val KEY_HOME_PLACE_LATITUDE = "home_place_latitude"
        const val KEY_HOME_PLACE_LONGITUDE = "home_place_longitude"
        const val KEY_PRAYER_METHOD = "prayer_method"
        const val KEY_PARENTAL_PIN_SALT = "parental_pin_salt"
        const val KEY_PARENTAL_PIN_HASH = "parental_pin_hash"
        const val KEY_PARENTAL_PIN_ALGORITHM = "parental_pin_algorithm"
        const val KEY_PARENTAL_PIN_FAILURES = "parental_pin_failures"
        const val KEY_PARENTAL_PIN_LOCKED_UNTIL = "parental_pin_locked_until"
        const val PIN_ALGORITHM_PBKDF2 = "pbkdf2-sha256"
    }
}

/**
 * Hachage salé d'un code parental, extrait en fonction pure (plutôt que méthode privée de
 * [AppSettingsStore]) pour rester testable sans `Context` Android — ce module n'a pas Robolectric.
 */
internal fun hashPin(pin: String, salt: String): String =
    MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray(Charsets.UTF_8)).toHex()

/** PBKDF2 : coûteux volontairement, pour qu'une fuite des préférences ne livre pas le code en 10 000 SHA-256. */
internal fun slowHashPin(pin: String, salt: String): String =
    pbkdf2HmacSha256(pin.toByteArray(Charsets.UTF_8), salt.toByteArray(Charsets.UTF_8), PIN_PBKDF2_ITERATIONS).toHex()

/**
 * PBKDF2-HMAC-SHA256 (RFC 8018, un seul bloc de 32 octets) écrit sur `Mac("HmacSHA256")`, disponible
 * partout : `SecretKeyFactory("PBKDF2WithHmacSHA256")` n'existe qu'à partir d'Android 8 (API 26),
 * alors que l'app tourne dès l'API 23.
 */
internal fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
    var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
    val result = u.copyOf()
    repeat(iterations - 1) {
        u = mac.doFinal(u)
        for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
    }
    return result
}

/** Aucun délai sous 5 échecs, puis 30 s doublées à chaque échec supplémentaire, plafonné à 15 min. */
internal fun pinLockoutMillis(failures: Int): Long {
    if (failures < PIN_FREE_ATTEMPTS) return 0L
    val exponent = (failures - PIN_FREE_ATTEMPTS).coerceAtMost(5)
    return (30_000L shl exponent).coerceAtMost(15 * 60_000L)
}

private const val PIN_PBKDF2_ITERATIONS = 40_000
private const val PIN_FREE_ATTEMPTS = 5

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
    put("disabledHomeBlocks", JSONArray(disabledHomeBlocks.map { it.name }))
    put("prayerMethod", prayerMethod.name)
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
    disabledHomeBlocks = json.optJSONArray("disabledHomeBlocks")?.let { array ->
        (0 until array.length()).mapNotNullTo(mutableSetOf()) { index ->
            runCatching { HomeBlock.valueOf(array.getString(index)) }.getOrNull()
        }
    } ?: fallback.disabledHomeBlocks,
    // La ville reste propre à l'appareil : une sauvegarde peut venir d'une TV installée ailleurs.
    homePlace = fallback.homePlace,
    prayerMethod = runCatching { PrayerMethod.valueOf(json.getString("prayerMethod")) }.getOrDefault(fallback.prayerMethod),
)

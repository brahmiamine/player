package fr.streamia.tv.domain

import java.text.Normalizer
import kotlin.math.abs
import java.util.Locale

/**
 * Reconnaissance des versions d'une même chaîne (« TF1 », « FR| TF1 FHD », « TF1 UHD ᴴᴰ »…).
 *
 * Les noms des fournisseurs sont peu fiables : ils ne servent qu'à regrouper et à estimer une
 * qualité de départ. La qualité réellement affichée vient des mesures du lecteur
 * ([LiveVersionStats]).
 */
object LiveVersionNames {
    // Préfixe fournisseur : « |FR| », « [AR] », « FR: », « UK | », « FR-HD - »… (majuscules seulement,
    // sans chiffre : « TF1 : » ou « M6 | » ne sont pas des préfixes).
    private val PREFIX = Regex("""^\s*(?:\|\s*([^|]{1,15}?)\s*\||\[\s*([^\]]{1,15}?)\s*]|([A-Z]{2,4}(?:-[A-Z]{2,4})*)\s*[-:|])\s*""")
    private val TIMESHIFT = Regex("""(?<![\p{L}\p{N}])\+\s?(\d{1,2})(?![\p{N}])""")
    private val SUPERSCRIPT_DIGITS = Regex("[¹²³⁰⁴⁵⁶⁷⁸⁹]")
    private val TOKEN = Regex("""[\p{L}\p{N}]+""")
    private val MARKS = Regex("""\p{M}+""")

    private val QUALITY_HEIGHTS = mapOf(
        "8k" to 2160, "4k" to 2160, "uhd" to 2160, "2160p" to 2160, "2160" to 2160,
        "fhd" to 1080, "1080p" to 1080, "1080i" to 1080, "1080" to 1080,
        "hd" to 720, "720p" to 720, "720" to 720,
        "sd" to 576, "576p" to 576, "576" to 576, "480p" to 576,
    )

    /** Mots de qualité ou de flux ignorés pour reconnaître la chaîne. */
    private val NOISE = QUALITY_HEIGHTS.keys + setOf(
        "hq", "lq", "hdr", "hdr10", "sdr", "hevc", "h265", "h264", "x265", "x264", "avc",
        "25fps", "50fps", "60fps", "fps", "backup", "bkp", "alt", "raw", "vip", "multi", "multiaudio", "dual",
    )

    /** Clé commune aux versions d'une chaîne ; vide si le nom ne contient rien d'exploitable. */
    fun groupKey(name: String): String {
        val parsed = parse(name)
        if (parsed.core.isEmpty()) return ""
        return parsed.core + (parsed.timeshift?.let { "+$it" } ?: "")
    }

    /** Qualité annoncée par le nom (hauteur d'image), `null` si le nom n'en dit rien. */
    fun announcedHeight(name: String): Int? = parse(name).announcedHeight

    /** Langue/pays du préfixe fournisseur (« FR », « AR »…), `null` sans préfixe. */
    fun language(name: String): String? = parse(name).language

    internal fun numbers(name: String): Set<String> = parse(name).numbers

    internal fun timeshift(name: String): String? = parse(name).timeshift

    private data class Parsed(
        val core: String,
        val numbers: Set<String>,
        val timeshift: String?,
        val announcedHeight: Int?,
        val language: String?,
    )

    private fun parse(name: String): Parsed {
        var rest = name.replace("⁴ᴷ", " 4K ").replace("⁸ᴷ", " 8K ")
        var language: String? = null
        var announced: Int? = null
        repeat(3) {
            val match = PREFIX.find(rest) ?: return@repeat
            val raw = match.groupValues.drop(1).firstOrNull(String::isNotBlank).orEmpty().trim()
            val value = raw.lowercase(Locale.ROOT)
            if (value in QUALITY_HEIGHTS || value in NOISE) {
                announced = maxOf(announced ?: 0, QUALITY_HEIGHTS[value] ?: 0).takeIf { it > 0 }
            } else if (language == null && raw.isNotBlank()) {
                language = raw.uppercase(Locale.ROOT)
            }
            val remainder = rest.substring(match.range.last + 1)
            // Ne jamais vider le nom : « UK | » seul reste un nom (rare, mais pas une chaîne vide).
            if (TOKEN.containsMatchIn(remainder)) rest = remainder else return@repeat
        }
        rest = rest.replace(SUPERSCRIPT_DIGITS, " ")
        val normalized = Normalizer.normalize(rest, Normalizer.Form.NFKD).replace(MARKS, "").lowercase(Locale.ROOT)
        val timeshift = TIMESHIFT.find(normalized)?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { null }
        val tokens = TOKEN.findAll(normalized.replace(TIMESHIFT, " ")).map { it.value }.toList()
        tokens.forEach { token -> QUALITY_HEIGHTS[token]?.let { announced = maxOf(announced ?: 0, it) } }
        val kept = tokens.filter { it !in NOISE }
        return Parsed(
            core = kept.joinToString(""),
            numbers = kept.filter { token -> token.all(Char::isDigit) }.toSet(),
            timeshift = timeshift,
            announcedHeight = announced,
            language = language,
        )
    }
}

/**
 * Index des chaînes du Direct par version : même nom nettoyé, ou même identifiant de guide TV
 * (`epg_channel_id` / `tvg-id`) sans numéro ni décalage (+1) contradictoire.
 */
class LiveVersionIndex(channels: List<MediaEntry>) {
    private val byGroupKey: Map<String, List<MediaEntry>>
    private val byGuideId: Map<String, List<MediaEntry>>

    init {
        val live = channels.filter { it.type == MediaType.Live }
        byGroupKey = live.groupBy { LiveVersionNames.groupKey(it.displayName) }
            .filterKeys(String::isNotEmpty)
        byGuideId = live.groupBy { guideId(it) }
            .filterKeys { it != null }
            .mapKeys { it.key!! }
            // Un même identifiant posé sur des dizaines de chaînes est un défaut du fournisseur, pas une version.
            .filterValues { it.size <= MAX_GUIDE_ID_SHARE }
    }

    /** Versions de [entry], elle comprise, dans l'ordre du fournisseur. */
    fun versionsOf(entry: MediaEntry): List<MediaEntry> {
        val key = LiveVersionNames.groupKey(entry.displayName)
        val sameName = if (key.isEmpty()) emptyList() else byGroupKey[key].orEmpty()
        val sameGuide = guideId(entry)?.let { id ->
            val numbers = LiveVersionNames.numbers(entry.displayName)
            val timeshift = LiveVersionNames.timeshift(entry.displayName)
            byGuideId[id].orEmpty().filter {
                LiveVersionNames.numbers(it.displayName) == numbers &&
                    LiveVersionNames.timeshift(it.displayName) == timeshift
            }
        }.orEmpty()
        return (listOf(entry) + sameName + sameGuide).distinctBy { it.key }.take(MAX_VERSIONS)
    }

    private fun guideId(entry: MediaEntry): String? =
        entry.tvgId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.length >= 3 && it !in INVALID_GUIDE_IDS }

    private companion object {
        const val MAX_GUIDE_ID_SHARE = 12
        const val MAX_VERSIONS = 40
        val INVALID_GUIDE_IDS = setOf("none", "null", "n/a", "undefined")
    }
}

/** Ce que le lecteur a réellement mesuré sur une version (mémorisé entre les sessions). */
data class LiveVersionStats(
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val codec: String? = null,
    val bitrate: Int? = null,
    val hdr: String? = null,
    val startupMs: Long? = null,
    val watchedMs: Long = 0L,
    val rebufferCount: Int = 0,
    val lastSuccessAtMs: Long = 0L,
    val lastFailureAtMs: Long = 0L,
    val consecutiveFailures: Int = 0,
    val updatedAtMs: Long = 0L,
    /** Images par seconde réellement affichées (compteurs du décodeur), pas celles annoncées par le flux. */
    val realFrameRate: Float? = null,
    /** Part des images perdues (0–1) lors du dernier contrôle. */
    val droppedRatio: Float? = null,
    /** Dernier contrôle : le son manquait, ou l'image ne s'affichait pas / restait figée. */
    val noSound: Boolean = false,
    val noPicture: Boolean = false,
) {
    val measured: Boolean get() = height != null && width != null
}

enum class LiveVersionHealth(val symbol: String, val label: String, val rank: Int) {
    Stable("●", "Stable", 0),
    Untested("○", "Jamais testée", 1),
    Choppy("◐", "Quelques coupures", 2),
    NoPicture("✕", "Pas d'image", 3),
    NoSound("✕", "Pas de son", 3),
    Unavailable("✕", "Indisponible", 3),
}

/** Contrôle réel d'une version, relevé par le lecteur pendant sa lecture. */
data class LiveVersionCheck(
    val width: Int?,
    val height: Int?,
    val declaredFrameRate: Float?,
    val realFrameRate: Float?,
    val codec: String?,
    val bitrate: Int?,
    val hdr: String?,
    val droppedRatio: Float?,
    val noPicture: Boolean,
    val noSound: Boolean,
)

data class LiveVersionOption(
    val entry: MediaEntry,
    val health: LiveVersionHealth,
    /** Qualité mesurée (« 1080p · 50 fps · H.265 · 8.0 Mb/s ») ou annoncée (« annoncée UHD »). */
    val qualityText: String,
    val measured: Boolean,
    val warnings: List<String>,
    val sameLanguage: Boolean,
    val recommended: Boolean,
    val current: Boolean,
    /** Pour une version indisponible : depuis quand (« il y a 2 h »). */
    val failureAgo: String? = null,
)

/** Hauteur « de classe » (2160, 1440, 1080, 720, 576) à partir de la taille réelle de l'image. */
fun qualityClassHeight(width: Int, height: Int): Int = when {
    width >= 3840 || height >= 2160 -> 2160
    width >= 2560 || height >= 1440 -> 1440
    width >= 1920 || height >= 1080 -> 1080
    width >= 1280 || height >= 720 -> 720
    else -> 576
}

fun qualityClassLabel(height: Int): String = when {
    height >= 2160 -> "4K"
    height >= 1440 -> "QHD"
    height >= 1080 -> "1080p"
    height >= 720 -> "720p"
    else -> "SD"
}

private fun announcedLabel(height: Int): String = when {
    height >= 2160 -> "UHD"
    height >= 1080 -> "FHD"
    height >= 720 -> "HD"
    else -> "SD"
}

/**
 * Classe les versions : même langue que la chaîne en cours d'abord, puis stables, jamais testées,
 * avec coupures, indisponibles ; à l'intérieur, la qualité **mesurée** (ou annoncée faute de
 * mesure) décide, plafonnée à ce que l'écran affiche.
 */
fun rankLiveVersions(
    current: MediaEntry,
    versions: List<MediaEntry>,
    stats: Map<String, LiveVersionStats>,
    nowMs: Long,
    maxDisplayHeight: Int? = null,
): List<LiveVersionOption> {
    val currentLanguage = LiveVersionNames.language(current.displayName)
    data class Scored(val option: LiveVersionOption, val rankHeight: Int, val overDisplay: Boolean, val fps: Float, val bitrate: Int)

    val scored = versions.distinctBy { it.key }.map { entry ->
        val stat = stats[entry.key]
        val announced = LiveVersionNames.announcedHeight(entry.displayName)
        val health = health(stat, nowMs)
        val warnings = mutableListOf<String>()
        var effectiveHeight: Int
        val qualityText: String
        if (stat != null && stat.measured) {
            val measuredClass = qualityClassHeight(stat.width!!, stat.height!!)
            effectiveHeight = measuredClass
            val efficientCodec = stat.codec?.let { codec -> listOf("265", "HEVC", "AV1", "VP9").any { codec.contains(it, ignoreCase = true) } } == true
            val minimum4kBitrate = if (efficientCodec) 8_000_000 else 12_000_000
            if (measuredClass >= 2160 && stat.bitrate != null && stat.bitrate in 1 until minimum4kBitrate) {
                warnings += "4K douteuse (débit faible)"
                effectiveHeight = 1080
            }
            if (announced != null && announced > measuredClass) {
                warnings += "annoncée ${announcedLabel(announced)}, réellement ${qualityClassLabel(measuredClass)}"
            }
            val real = stat.realFrameRate?.takeIf { it > 0f }
            val declared = stat.frameRate?.takeIf { it > 0f }
            if (real != null && declared != null && abs(real - declared) / declared > FPS_MISMATCH_RATIO) {
                warnings += "annoncée ${Math.round(declared)} fps, réellement ${Math.round(real)}"
            }
            stat.droppedRatio?.takeIf { it >= DROPPED_WARNING_RATIO }?.let {
                warnings += "images perdues (${Math.round(it * 100)} %)"
            }
            qualityText = listOfNotNull(
                qualityClassLabel(measuredClass),
                (real ?: declared)?.let { "${Math.round(it)} fps" },
                stat.codec,
                stat.bitrate?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f Mb/s", it / 1_000_000.0) },
                stat.hdr?.takeIf { it != "SDR" },
            ).joinToString(" · ")
        } else {
            effectiveHeight = announced ?: 0
            qualityText = announced?.let { "annoncée ${announcedLabel(it)}" } ?: "qualité inconnue"
        }
        val rankHeight = maxDisplayHeight?.let { minOf(effectiveHeight, it) } ?: effectiveHeight
        val failureAgo = if (health == LiveVersionHealth.Unavailable && stat != null) ago(nowMs - stat.lastFailureAtMs) else null
        Scored(
            LiveVersionOption(
                entry = entry,
                health = health,
                qualityText = qualityText,
                measured = stat?.measured == true,
                warnings = warnings,
                sameLanguage = LiveVersionNames.language(entry.displayName).let { it == null || currentLanguage == null || it == currentLanguage },
                recommended = false,
                current = entry.key == current.key,
                failureAgo = failureAgo,
            ),
            rankHeight = rankHeight,
            overDisplay = maxDisplayHeight != null && effectiveHeight > maxDisplayHeight,
            fps = stat?.realFrameRate ?: stat?.frameRate ?: 0f,
            bitrate = stat?.bitrate ?: 0,
        )
    }
    val sorted = scored.sortedWith(
        compareBy<Scored> { if (it.option.sameLanguage) 0 else 1 }
            .thenBy { it.option.health.rank }
            .thenByDescending { it.rankHeight }
            // À qualité affichée égale, inutile de charger une 4K sur une TV 1080p.
            .thenBy { if (it.overDisplay) 1 else 0 }
            .thenByDescending { if (it.fps >= 48f) 1 else 0 }
            .thenByDescending { it.bitrate }
            .thenBy { it.option.entry.displayName.lowercase(Locale.ROOT) },
    ).map { it.option }
    val best = sorted.firstOrNull()
    val recommendable = sorted.size > 1 && best != null && best.sameLanguage &&
        (best.health == LiveVersionHealth.Stable || best.health == LiveVersionHealth.Untested)
    return if (recommendable) listOf(best.copy(recommended = true)) + sorted.drop(1) else sorted
}

internal fun health(stat: LiveVersionStats?, nowMs: Long): LiveVersionHealth {
    if (stat == null) return LiveVersionHealth.Untested
    val recentFailure = stat.consecutiveFailures > 0 &&
        stat.lastFailureAtMs > stat.lastSuccessAtMs &&
        nowMs - stat.lastFailureAtMs < UNAVAILABLE_WINDOW_MS
    if (recentFailure) return LiveVersionHealth.Unavailable
    if (stat.noPicture) return LiveVersionHealth.NoPicture
    if (stat.noSound) return LiveVersionHealth.NoSound
    if (stat.lastSuccessAtMs == 0L && !stat.measured) return LiveVersionHealth.Untested
    if ((stat.droppedRatio ?: 0f) >= CHOPPY_DROPPED_RATIO) return LiveVersionHealth.Choppy
    val watchedHours = maxOf(stat.watchedMs, MIN_RATE_WINDOW_MS) / 3_600_000.0
    if (stat.rebufferCount >= 2 && stat.rebufferCount / watchedHours >= CHOPPY_REBUFFERS_PER_HOUR) return LiveVersionHealth.Choppy
    return LiveVersionHealth.Stable
}

private fun ago(elapsedMs: Long): String {
    val minutes = (elapsedMs / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        else -> "il y a ${minutes / 60} h"
    }
}

private const val UNAVAILABLE_WINDOW_MS = 6 * 3_600_000L
private const val MIN_RATE_WINDOW_MS = 10 * 60_000L
private const val CHOPPY_REBUFFERS_PER_HOUR = 6.0
private const val CHOPPY_DROPPED_RATIO = 0.10f
private const val DROPPED_WARNING_RATIO = 0.05f
private const val FPS_MISMATCH_RATIO = 0.2f

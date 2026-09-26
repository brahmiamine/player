package fr.streamia.tv.data

import fr.streamia.tv.net.HttpClients
import fr.streamia.tv.domain.EpgChannel
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaEntry
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** Charge un guide XMLTV en flux, en ne conservant que les chaînes présentes dans le catalogue. */
class XmlTvRepository {
    /**
     * Variante destinée au cache SQLite : aucun guide complet n'est matérialisé en mémoire. Les
     * programmes sont émis par petits lots pendant le parsing et la transaction est pilotée par
     * l'appelant sur le même thread IO.
     */
    internal fun syncOnIo(
        url: String,
        entries: List<MediaEntry>,
        sink: EpgWriteSink,
        validators: HttpValidators? = null,
    ): XmlTvSyncOutcome {
        val accepted = acceptedIds(entries)
        return withRemoteStream(url, validators) { stream -> parseToSink(stream, accepted, sink) }
    }

    private fun acceptedIds(entries: List<MediaEntry>): Set<String> = buildSet {
        entries.forEach { entry ->
            entry.tvgId?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            entry.name.trim().takeIf(String::isNotBlank)?.let(::add)
            entry.displayName.trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    /**
     * Guide téléchargé par le client HTTP partagé (décompression gzip transparente). Avec
     * [validators] (ETag / Last-Modified de la dernière synchronisation), un guide inchangé répond
     * 304 : ni téléchargement ni analyse de dizaines de Mo.
     */
    private fun withRemoteStream(url: String, validators: HttpValidators?, block: (InputStream) -> Unit): XmlTvSyncOutcome {
        val headers = buildMap {
            validators?.etag?.let { put("If-None-Match", it) }
            validators?.lastModified?.let { put("If-Modified-Since", it) }
        }
        return HttpClients.execute(url, headers, connectTimeoutMs = 15_000, readTimeoutMs = 60_000).use { response ->
            if (response.code == 304 && validators != null) return@use XmlTvSyncOutcome.NotModified
            if (!response.isSuccessful) throw XtreamException("Le serveur EPG a répondu avec le code ${response.code}.")
            val body = response.body ?: throw XtreamException("Le serveur EPG a renvoyé une réponse vide.")
            val raw = BufferedInputStream(body.byteStream(), BUFFER_SIZE)
            // Fichier .gz servi tel quel (le gzip de transport, lui, est déjà retiré par OkHttp).
            val stream: InputStream = if (url.substringBefore('?').endsWith(".gz", ignoreCase = true)) GZIPInputStream(raw, BUFFER_SIZE) else raw
            stream.use(block)
            XmlTvSyncOutcome.Written(HttpValidators(response.header("ETag"), response.header("Last-Modified")))
        }
    }

    private fun parseToSink(
        stream: InputStream,
        acceptedIds: Set<String>,
        sink: EpgWriteSink,
    ) {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(stream, null)
        }
        val channelNames = HashMap<String, String?>()
        val acceptedChannelIds = HashSet<String>()
        val programBatch = ArrayList<EpgProgram>(PROGRAM_WRITE_BATCH_SIZE)
        val nowSeconds = System.currentTimeMillis() / 1000
        val windowStart = nowSeconds - EPG_KEEP_PAST_SECONDS
        val windowEnd = nowSeconds + EPG_KEEP_FUTURE_SECONDS
        var event = parser.eventType
        var currentChannelId: String? = null
        var currentChannelName: String? = null
        var currentChannelIcon: String? = null
        var currentProgramChannel: String? = null
        var currentProgramTitle: String? = null
        var currentProgramDescription: String? = null
        var currentProgramCategory: String? = null
        var currentStart: Long? = null
        var currentEnd: Long? = null

        fun flushPrograms() {
            if (programBatch.isEmpty()) return
            sink.writePrograms(programBatch)
            programBatch.clear()
        }

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        currentChannelId = parser.getAttributeValue(null, "id")
                        currentChannelName = null
                        currentChannelIcon = null
                    }
                    "display-name" -> if (currentChannelId != null) {
                        currentChannelName = parser.nextText().trim().takeIf(String::isNotBlank)
                    }
                    "icon" -> if (currentChannelId != null) {
                        currentChannelIcon = parser.getAttributeValue(null, "src")?.takeIf(String::isNotBlank)
                    }
                    "programme" -> {
                        currentProgramChannel = parser.getAttributeValue(null, "channel")
                        currentStart = parseDate(parser.getAttributeValue(null, "start"))
                        currentEnd = parseDate(parser.getAttributeValue(null, "stop"))
                        currentProgramTitle = null
                        currentProgramDescription = null
                        currentProgramCategory = null
                    }
                    "title" -> if (currentProgramChannel != null) {
                        currentProgramTitle = parser.nextText().trim().takeIf(String::isNotBlank)
                    }
                    "desc" -> if (currentProgramChannel != null) {
                        currentProgramDescription = parser.nextText().trim().takeIf(String::isNotBlank)
                    }
                    "category" -> if (currentProgramChannel != null) {
                        currentProgramCategory = parser.nextText().trim().takeIf(String::isNotBlank)
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> {
                        val id = currentChannelId
                        if (id != null) {
                            channelNames[id] = currentChannelName
                            if (id in acceptedIds || currentChannelName in acceptedIds) {
                                acceptedChannelIds += id
                                sink.writeChannel(
                                    EpgChannel(
                                        channelId = id,
                                        displayName = currentChannelName,
                                        iconUrl = currentChannelIcon,
                                    ),
                                )
                            }
                        }
                        currentChannelId = null
                        currentChannelName = null
                        currentChannelIcon = null
                    }
                    "programme" -> {
                        val channel = currentProgramChannel
                        val title = currentProgramTitle
                        val accepted = channel != null && (
                            channel in acceptedIds ||
                                channel in acceptedChannelIds ||
                                channelNames[channel] in acceptedIds
                        )
                        // Hors de la fenêtre utile (programmes finis depuis plus d'un jour, ou
                        // au-delà d'une semaine) : ni écrits ni indexés.
                        val inWindow = (currentEnd ?: Long.MAX_VALUE) >= windowStart && (currentStart ?: Long.MIN_VALUE) <= windowEnd
                        if (accepted && inWindow && title != null && channel != null) {
                            if (channel !in acceptedChannelIds) {
                                acceptedChannelIds += channel
                                sink.writeChannel(
                                    EpgChannel(
                                        channelId = channel,
                                        displayName = channelNames[channel],
                                    ),
                                )
                            }
                            programBatch += EpgProgram(
                                title = title,
                                description = currentProgramDescription,
                                startEpochSeconds = currentStart,
                                endEpochSeconds = currentEnd,
                                channelId = channel,
                                category = currentProgramCategory,
                            )
                            if (programBatch.size >= PROGRAM_WRITE_BATCH_SIZE) flushPrograms()
                        }
                        currentProgramChannel = null
                    }
                }
            }
            event = parser.next()
        }
        flushPrograms()
    }

    private fun parseDate(value: String?): Long? = parseXmlTvDate(value)

    private companion object {
        const val BUFFER_SIZE = 128 * 1024
        const val PROGRAM_WRITE_BATCH_SIZE = 500
        const val EPG_KEEP_PAST_SECONDS = 24 * 3_600L
        const val EPG_KEEP_FUTURE_SECONDS = 8 * 24 * 3_600L
    }
}

/**
 * Date XMLTV (`20260926203000 +0200`, `202609262030 +0200`, `20260926203000`, en UTC sans décalage)
 * convertie en secondes epoch **sans allocation** : appelée deux fois par programme, soit des
 * millions de fois par synchronisation. L'ancienne version compilait une expression régulière et
 * créait jusqu'à trois `SimpleDateFormat` par date (des minutes de processeur sur un boîtier TV).
 * Format inattendu : repli sur l'analyse tolérante d'origine.
 */
internal fun parseXmlTvDate(value: String?): Long? {
    if (value == null) return null
    var i = 0
    val n = value.length
    while (i < n && value[i].isWhitespace()) i++
    val digitsStart = i
    while (i < n && value[i] in '0'..'9') i++
    val digitCount = i - digitsStart
    if (digitCount != 12 && digitCount != 14) return parseXmlTvDateLenient(value)
    fun num(offset: Int, length: Int): Int {
        var result = 0
        for (k in digitsStart + offset until digitsStart + offset + length) result = result * 10 + (value[k] - '0')
        return result
    }
    val year = num(0, 4)
    val month = num(4, 2)
    val day = num(6, 2)
    val hour = num(8, 2)
    val minute = num(10, 2)
    val second = if (digitCount == 14) num(12, 2) else 0
    if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return parseXmlTvDateLenient(value)
    while (i < n && value[i].isWhitespace()) i++
    var offsetSeconds = 0
    if (i < n) {
        val sign = when (value[i]) {
            '+' -> 1
            '-' -> -1
            else -> return parseXmlTvDateLenient(value)
        }
        i++
        var hh = 0
        var mm = 0
        var count = 0
        while (i < n && count < 4) {
            val c = value[i]
            if (c == ':') { i++; continue }
            if (c !in '0'..'9') break
            if (count < 2) hh = hh * 10 + (c - '0') else mm = mm * 10 + (c - '0')
            count++
            i++
        }
        if (count != 4 || hh > 23 || mm > 59) return parseXmlTvDateLenient(value)
        offsetSeconds = sign * (hh * 3_600 + mm * 60)
    }
    return epochDay(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second - offsetSeconds
}

/** Jours depuis le 01/01/1970 (algorithme « days from civil » de H. Hinnant, calendrier grégorien). */
private fun epochDay(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

private val XMLTV_WHITESPACE = Regex("\\s+")

private fun parseXmlTvDateLenient(value: String): Long? {
    val raw = value.trim().takeIf(String::isNotBlank) ?: return null
    val normalized = raw.replace(XMLTV_WHITESPACE, " ")
    for (pattern in XMLTV_PATTERNS) {
        val parsed = runCatching {
            java.text.SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = true
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(normalized)?.time
        }.getOrNull()
        if (parsed != null) return parsed / 1000
    }
    return null
}

private val XMLTV_PATTERNS = listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmm Z", "yyyyMMddHHmmss")

/** Validateurs HTTP d'un guide déjà synchronisé. */
internal data class HttpValidators(val etag: String?, val lastModified: String?) {
    val isEmpty: Boolean get() = etag.isNullOrBlank() && lastModified.isNullOrBlank()
}

internal sealed interface XmlTvSyncOutcome {
    /** Guide inchangé depuis la dernière synchronisation (304). */
    data object NotModified : XmlTvSyncOutcome
    data class Written(val validators: HttpValidators) : XmlTvSyncOutcome
}

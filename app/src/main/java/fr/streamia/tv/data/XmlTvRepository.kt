package fr.streamia.tv.data

import fr.streamia.tv.domain.EpgChannel
import fr.streamia.tv.domain.EpgGuide
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** Charge un guide XMLTV en flux, en ne conservant que les chaînes présentes dans le catalogue. */
class XmlTvRepository {
    suspend fun load(url: String, entries: List<MediaEntry>): EpgGuide = withContext(Dispatchers.IO) {
        val accepted = acceptedIds(entries)
        withRemoteStream(url) { stream -> parse(stream, accepted) }
    }

    /**
     * Variante destinée au cache SQLite : aucun guide complet n'est matérialisé en mémoire. Les
     * programmes sont émis par petits lots pendant le parsing et la transaction est pilotée par
     * l'appelant sur le même thread IO.
     */
    internal fun syncOnIo(
        url: String,
        entries: List<MediaEntry>,
        sink: EpgWriteSink,
    ) {
        val accepted = acceptedIds(entries)
        withRemoteStream(url) { stream -> parseToSink(stream, accepted, sink) }
    }

    private fun acceptedIds(entries: List<MediaEntry>): Set<String> = buildSet {
        entries.forEach { entry ->
            entry.tvgId?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            entry.name.trim().takeIf(String::isNotBlank)?.let(::add)
            entry.displayName.trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun <T> withRemoteStream(url: String, block: (InputStream) -> T): T {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            useCaches = true
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", "Streamia-TV/1.5")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw XtreamException("Le serveur EPG a répondu avec le code $code.")
            val raw = BufferedInputStream(connection.inputStream, BUFFER_SIZE)
            val stream: InputStream = if (
                connection.contentEncoding.equals("gzip", ignoreCase = true) || url.endsWith(".gz", ignoreCase = true)
            ) GZIPInputStream(raw, BUFFER_SIZE) else raw
            return stream.use(block)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(stream: InputStream, acceptedIds: Set<String>): EpgGuide {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(stream, null)
        }
        val channelNames = linkedMapOf<String, String?>()
        val channelIcons = linkedMapOf<String, String?>()
        val programs = linkedMapOf<String, MutableList<EpgProgram>>()
        var event = parser.eventType
        var currentChannelId: String? = null
        var currentChannelName: String? = null
        var currentProgramChannel: String? = null
        var currentProgramTitle: String? = null
        var currentProgramDescription: String? = null
        var currentProgramCategory: String? = null
        var currentStart: Long? = null
        var currentEnd: Long? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        currentChannelId = parser.getAttributeValue(null, "id")
                        currentChannelName = null
                    }
                    "display-name" -> if (currentChannelId != null) {
                        currentChannelName = parser.nextText().trim().takeIf(String::isNotBlank)
                    }
                    "icon" -> if (currentChannelId != null) {
                        parser.getAttributeValue(null, "src")?.takeIf(String::isNotBlank)?.let {
                            channelIcons[currentChannelId!!] = it
                        }
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
                        if (id != null) channelNames[id] = currentChannelName
                        currentChannelId = null
                        currentChannelName = null
                    }
                    "programme" -> {
                        val channel = currentProgramChannel
                        val title = currentProgramTitle
                        val accepted = channel != null && (
                            channel in acceptedIds || channelNames[channel] in acceptedIds
                        )
                        if (accepted && title != null) {
                            programs.getOrPut(channel!!) { ArrayList() }.add(
                                EpgProgram(
                                    title = title,
                                    description = currentProgramDescription,
                                    startEpochSeconds = currentStart,
                                    endEpochSeconds = currentEnd,
                                    channelId = channel,
                                    category = currentProgramCategory,
                                ),
                            )
                        }
                        currentProgramChannel = null
                    }
                }
            }
            event = parser.next()
        }

        val channels = linkedMapOf<String, EpgChannel>()
        val keys = (channelNames.keys + programs.keys).distinct()
        for (id in keys) {
            if (id !in acceptedIds && channelNames[id] !in acceptedIds) continue
            channels[id] = EpgChannel(
                channelId = id,
                displayName = channelNames[id],
                iconUrl = channelIcons[id],
                programs = programs[id].orEmpty().sortedBy { it.startEpochSeconds ?: Long.MAX_VALUE },
            )
        }
        return EpgGuide(channels)
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
                        if (accepted && title != null && channel != null) {
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

    private fun parseDate(value: String?): Long? {
        val raw = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        val normalized = raw.replace(Regex("\\s+"), " ")
        val patterns = listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmm Z", "yyyyMMddHHmmss")
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = true
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(normalized)?.time
            }.getOrNull()
            if (parsed != null) return parsed / 1000
        }
        return null
    }

    private companion object {
        const val BUFFER_SIZE = 128 * 1024
        const val PROGRAM_WRITE_BATCH_SIZE = 500
    }
}


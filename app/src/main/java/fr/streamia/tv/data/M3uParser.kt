package fr.streamia.tv.data

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.ServerCredentials
import java.io.BufferedReader
import java.io.Reader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Parse une playlist M3U ligne par ligne afin de supporter les très gros fichiers sur Android TV.
 *
 * Les informations EXTINF utilisées sont notamment : tvg-id, tvg-name, tvg-logo et group-title.
 * Le type de média est déterminé par le chemin Xtream /live/, /movie/ ou /series/.
 *
 * Certains fournisseurs produisent des URL sans schéma (host:port/live/...). Dans ce cas HTTP est
 * utilisé comme transport initial, puis la couche réseau/lecteur peut basculer automatiquement vers
 * HTTPS si le serveur l'exige. Les URL qui déclarent explicitement http:// ou https:// sont conservées.
 */
class M3uParser {
    fun parse(reader: Reader): M3uImport {
        val categories = linkedMapOf<String, MediaCategory>()
        val entries = ArrayList<MediaEntry>()
        val seenEntries = HashSet<String>()
        val typeNumbers = mutableMapOf<MediaType, Int>()
        val detectedAttributes = linkedSetOf<String>()
        var credentials: ServerCredentials? = null
        var pending: ExtInf? = null
        var skipped = 0

        BufferedReader(reader, BUFFER_SIZE).useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.removePrefix("\uFEFF").trim()
                when {
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        pending = parseExtInf(line)
                        pending?.attributes?.keys?.let(detectedAttributes::addAll)
                    }

                    line.isBlank() || line.startsWith("#") -> Unit
                    pending != null -> {
                        val metadata = pending
                        pending = null
                        val stream = parseStream(line)
                        if (metadata == null || stream == null) {
                            skipped += 1
                            continue
                        }
                        if (credentials == null) credentials = stream.credentials
                        if (!sameAccount(credentials!!, stream.credentials)) {
                            skipped += 1
                            continue
                        }

                        val name = metadata.attributes["tvg-name"]
                            ?.takeIf(String::isNotBlank)
                            ?: metadata.displayName.takeIf(String::isNotBlank)
                            ?: "Média ${stream.id}"
                        val rawDisplayName = metadata.displayName.ifBlank { name }
                        val displayName = if (rawDisplayName == name) name else rawDisplayName
                        val group = metadata.attributes["group-title"]
                            ?.takeIf(String::isNotBlank)
                            ?: "Sans catégorie"
                        val groupKey = "${stream.type.name}:$group"
                        val category = categories.getOrPut(groupKey) {
                            MediaCategory(categoryId(stream.type, group), group, stream.type)
                        }
                        val entryKey = "${stream.type.name}:${stream.id}"
                        if (!seenEntries.add(entryKey)) continue

                        val automaticNumber = (typeNumbers[stream.type] ?: 0) + 1
                        typeNumbers[stream.type] = automaticNumber
                        val suppliedNumber = metadata.attributes["tvg-chno"]?.toIntOrNull()
                            ?: metadata.attributes["channel-number"]?.toIntOrNull()

                        entries += MediaEntry(
                            id = stream.id,
                            name = name,
                            displayName = displayName,
                            type = stream.type,
                            categoryId = category.id,
                            iconUrl = metadata.attributes["tvg-logo"]?.takeIf(String::isNotBlank),
                            number = suppliedNumber ?: automaticNumber,
                            extension = stream.extension,
                            tvgId = metadata.attributes["tvg-id"]?.takeIf(String::isNotBlank),
                            playable = stream.type != MediaType.Series,
                        )
                    }
                }
            }
        }

        val account = credentials ?: throw XtreamException(
            "Aucune URL Xtream compatible n'a été trouvée dans le fichier M3U.",
        )
        if (entries.isEmpty()) throw XtreamException("La playlist M3U ne contient aucun média exploitable.")
        return M3uImport(
            catalog = Catalog(categories.values.toList(), entries),
            credentials = account,
            skippedEntries = skipped,
            parsedEntries = entries.size,
            detectedAttributes = detectedAttributes,
        )
    }

    private fun parseExtInf(line: String): ExtInf? {
        val separator = firstCommaOutsideQuotes(line)
        if (separator < 0) return null
        val attributesPart = line.substring(0, separator)
        val displayName = line.substring(separator + 1).trim()
        val attributes = ATTRIBUTE.findAll(attributesPart).associate { result ->
            result.groupValues[1].lowercase() to result.groupValues[2]
        }
        return ExtInf(attributes, displayName)
    }

    private fun firstCommaOutsideQuotes(value: String): Int {
        var quoted = false
        for (index in value.indices) {
            when (value[index]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return index
            }
        }
        return -1
    }

    private fun parseStream(rawUrl: String): ParsedStream? {
        val raw = rawUrl.trim()
        if (raw.isBlank()) return null
        val hasExplicitScheme = SCHEME_PREFIX.containsMatchIn(raw)
        val normalized = if (hasExplicitScheme) raw else "$DEFAULT_SCHEME://$raw"
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null

        val rawSegments = uri.rawPath.orEmpty().split('/').filter(String::isNotBlank)
        val typeIndex = rawSegments.indexOfFirst { segment ->
            MediaType.entries.any { it.pathSegment.equals(segment, ignoreCase = true) }
        }
        if (typeIndex < 0 || rawSegments.size < typeIndex + 4) return null
        val type = MediaType.entries.first { it.pathSegment.equals(rawSegments[typeIndex], ignoreCase = true) }
        val username = decode(rawSegments[typeIndex + 1])
        val password = decode(rawSegments[typeIndex + 2])
        if (username.isBlank() || password.isBlank()) return null

        val fileName = decode(rawSegments[typeIndex + 3])
        val id = fileName.substringBeforeLast('.', fileName).toIntOrNull()
            ?: fileName.toIntOrNull()
            ?: return null
        val extension = fileName.substringAfterLast('.', type.defaultExtension)
            .takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
            ?: type.defaultExtension
        val prefix = rawSegments.take(typeIndex).joinToString("/")
        val authority = buildString {
            append(scheme)
            append("://")
            append(uri.host)
            if (uri.port >= 0) append(":${uri.port}")
            if (prefix.isNotBlank()) append("/$prefix")
        }
        return ParsedStream(
            credentials = ServerCredentials(authority, username, password),
            type = type,
            id = id,
            extension = extension,
        )
    }

    private fun sameAccount(first: ServerCredentials, second: ServerCredentials): Boolean =
        first.serverUrl.equals(second.serverUrl, ignoreCase = true) &&
            first.username == second.username && first.password == second.password

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private fun categoryId(type: MediaType, group: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${type.name}:$group".toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class ExtInf(val attributes: Map<String, String>, val displayName: String)

    private data class ParsedStream(
        val credentials: ServerCredentials,
        val type: MediaType,
        val id: Int,
        val extension: String,
    )

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_SCHEME = "http"
        private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
        private val ATTRIBUTE = Regex("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"")
    }
}

data class M3uImport(
    val catalog: Catalog,
    val credentials: ServerCredentials,
    val parsedEntries: Int,
    val skippedEntries: Int,
    val detectedAttributes: Set<String> = emptySet(),
)

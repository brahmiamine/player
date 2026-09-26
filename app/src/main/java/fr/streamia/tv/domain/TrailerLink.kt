package fr.streamia.tv.domain

/**
 * Adresses à essayer, dans l'ordre, pour ouvrir la bande-annonce fournie par Xtream
 * (`youtube_trailer`). Les fournisseurs y mettent tantôt l'identifiant YouTube seul
 * (`dQw4w9WgXcQ`), tantôt une URL complète (watch, youtu.be, embed, shorts), tantôt un lien
 * direct vers une vidéo. Une valeur vide, tronquée (`watch?v=`) ou fantaisiste (`0`) ne donne
 * rien : la fiche n'affiche alors pas de bouton plutôt qu'un bouton qui échoue.
 */
object TrailerLink {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val YOUTUBE_HOST = Regex("""^(?:[a-z0-9-]+\.)*(?:youtube\.com|youtube-nocookie\.com|youtu\.be)$""")

    /**
     * URI à tenter : d'abord `vnd.youtube:` (ouvre directement l'appli YouTube, y compris sur
     * Android TV, sans passer par un sélecteur de navigateur), puis l'URL web en secours.
     */
    fun candidates(raw: String?): List<String> {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return emptyList()
        youtubeVideoId(value)?.let { id ->
            return listOf("vnd.youtube:$id", "https://www.youtube.com/watch?v=$id")
        }
        val lower = value.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return emptyList()
        val host = hostOf(value) ?: return emptyList()
        // Lien YouTube sans identifiant exploitable (« watch?v= » vide…) : on ne l'ouvre pas.
        return if (YOUTUBE_HOST.matches(host)) emptyList() else listOf(value)
    }

    fun youtubeVideoId(raw: String): String? {
        val value = raw.trim()
        if (VIDEO_ID.matches(value)) return value
        val host = hostOf(value) ?: return null
        if (!YOUTUBE_HOST.matches(host)) return null
        val afterHost = value.substringAfter("://").substringAfter('/', "")
        val path = afterHost.substringBefore('?').substringBefore('#')
        val query = afterHost.substringAfter('?', "").substringBefore('#')
        val candidate = when {
            host.endsWith("youtu.be") -> path.substringBefore('/')
            path == "watch" || path.startsWith("watch/") -> query.split('&')
                .firstOrNull { it.startsWith("v=") }
                ?.removePrefix("v=")
            else -> {
                val segments = path.split('/')
                val marker = segments.indexOfFirst { it == "embed" || it == "shorts" || it == "v" || it == "live" }
                segments.getOrNull(marker + 1).takeIf { marker >= 0 }
            }
        }
        return candidate?.takeIf(VIDEO_ID::matches)
    }

    private fun hostOf(value: String): String? {
        val lower = value.lowercase()
        val rest = when {
            lower.startsWith("https://") -> value.substring(8)
            lower.startsWith("http://") -> value.substring(7)
            // « youtube.com/watch?v=… » ou « youtu.be/… » sans schéma.
            lower.startsWith("www.") || lower.startsWith("youtube.") || lower.startsWith("youtu.be") ||
                lower.startsWith("m.youtube.") -> value
            else -> return null
        }
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase()
        return host.takeIf { it.isNotEmpty() && '.' in it }
    }
}

package fr.streamia.tv.domain

/**
 * Chaînes Direct d'une zone de la playlist (AR, FR, UK…) : une chaîne est retenue si le nom de sa
 * catégorie OU son propre nom commence par le préfixe, sans tenir compte de la casse ni de
 * l'habillage courant des playlists ("|FR| …", "[UK] …").
 */
class LiveChannelPrefix(private val prefix: String) {
    private val stripPattern = Regex(
        """^[^\p{L}\p{N}]*${Regex.escape(prefix)}(?![\p{L}\p{N}])[\s|:\-•»\]\)/_.]*""",
        RegexOption.IGNORE_CASE,
    )

    fun matches(name: String): Boolean =
        name.trimStart { !it.isLetterOrDigit() }.startsWith(prefix, ignoreCase = true)

    fun channels(catalog: Catalog): List<MediaEntry> {
        val categoryIds = catalog.categories.asSequence()
            .filter { it.type == MediaType.Live && matches(it.name) }
            .mapTo(mutableSetOf()) { it.id }
        return catalog.entriesFor(MediaType.Live).filter { entry ->
            entry.categoryId in categoryIds || matches(entry.name) || matches(entry.displayName)
        }
    }

    /** "FR | TF1 HD" → "TF1 HD" ; "France 2" reste intact (préfixe non isolé). */
    fun strip(name: String): String = name.replace(stripPattern, "")

    companion object {
        val AR = LiveChannelPrefix("ar")
        val FR = LiveChannelPrefix("fr")
        val UK = LiveChannelPrefix("uk")
    }
}

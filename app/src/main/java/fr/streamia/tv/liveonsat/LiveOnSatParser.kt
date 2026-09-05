package fr.streamia.tv.liveonsat

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Parseur pur (aucun accès réseau ici, voir [fr.streamia.tv.data.LiveOnSatClient]) de la page
 * "aujourd'hui" de liveonsat.com. Le balisage de ce site est de facture ancienne (tables et polices
 * imbriquées, attributs non quotés) : Jsoup le tolère sans qu'on ait à le nettoyer nous-mêmes.
 *
 * Chaque match porte un horodatage Unix déjà résolu (`data-timestamp`) : aucun parsing de date/heure
 * ni de fuseau horaire n'est nécessaire ici.
 */
object LiveOnSatParser {
    fun parse(html: String): List<LiveOnSatMatch> {
        val document = Jsoup.parse(html)
        var currentCompetition = ""
        val matches = mutableListOf<LiveOnSatMatch>()

        document.select("span.comp_head, div.blockfix").forEach { element ->
            if (element.tagName() == "span") {
                currentCompetition = element.text().trim()
            } else {
                parseBlock(element, currentCompetition)?.let(matches::add)
            }
        }
        return matches
    }

    private fun parseBlock(block: Element, competition: String): LiveOnSatMatch? {
        val startEpochSeconds = block.selectFirst("div.dynamic-time")
            ?.attr("data-timestamp")
            ?.toLongOrNull()
            ?: return null

        val teams = block.selectFirst("div.fix_text div.fLeft")
            ?.text()
            ?.split(TEAM_SEPARATOR, limit = 2)
            ?.map(String::trim)
            ?.takeIf { it.size == 2 && it.all(String::isNotEmpty) }
            ?: return null

        val teamImages = block.select("div.fix_text img")
        val participantALogoUrl = teamImages.getOrNull(0)
            ?.attr("src")
            ?.let { normalizeTeamLogoUrl(it, teams[0]) }
        val participantBLogoUrl = teamImages.getOrNull(1)
            ?.attr("src")
            ?.let { normalizeTeamLogoUrl(it, teams[1]) }

        val channels = block
            .select("a.chan_live_free, a.chan_live_not_free, a.chan_live_iptvcable")
            .mapNotNull { anchor ->
                val name = anchor.text().trim()
                if (name.isEmpty()) null else LiveOnSatChannel(name = name, free = anchor.hasClass("chan_live_free"))
            }

        return LiveOnSatMatch(
            competition = competition,
            participantA = teams[0],
            participantB = teams[1],
            participantALogoUrl = participantALogoUrl,
            participantBLogoUrl = participantBLogoUrl,
            startEpochSeconds = startEpochSeconds,
            channels = channels,
        )
    }

    private fun normalizeTeamLogoUrl(raw: String, participant: String): String? {
        val value = raw.trim()
        if (value.isBlank() || !value.contains("img/team/", ignoreCase = true)) return null

        // LiveOnSat place aussi des drapeaux de pays dans img/team/ (ex. england.gif,
        // spain.gif). On ne doit pas les présenter comme logos de clubs. Une image n'est
        // acceptée que si son nom de fichier partage un token significatif avec le participant.
        val imageStem = value.substringAfterLast('/').substringBeforeLast('.')
            .lowercase()
            .replace(TEAM_LOGO_NON_ALNUM, " ")
        val participantTokens = participant
            .lowercase()
            .replace(TEAM_LOGO_NON_ALNUM, " ")
            .split(' ')
            .filter { it.length >= TEAM_LOGO_MIN_TOKEN_LENGTH }
        if (participantTokens.none { token -> imageStem.contains(token) }) return null

        return when {
            value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$LIVE_ONSAT_ORIGIN$value"
            else -> "$LIVE_ONSAT_ORIGIN/$value"
        }
    }

    private const val LIVE_ONSAT_ORIGIN = "https://liveonsat.com"
    private const val TEAM_LOGO_MIN_TOKEN_LENGTH = 4
    private val TEAM_LOGO_NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val TEAM_SEPARATOR = Regex("\\sv\\s")
}

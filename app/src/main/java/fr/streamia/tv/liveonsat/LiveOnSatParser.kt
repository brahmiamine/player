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
            startEpochSeconds = startEpochSeconds,
            channels = channels,
        )
    }

    private val TEAM_SEPARATOR = Regex("\\sv\\s")
}

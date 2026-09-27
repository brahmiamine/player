package fr.streamia.tv.ukguide

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.LiveChannelPrefix
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.PrefixedChannelIndex
import fr.streamia.tv.domain.PrefixedChannelIndexCache

/**
 * Associe les chaînes de tvguideuk.com uniquement aux chaînes Direct "UK" de la playlist
 * (catégorie ou nom de chaîne commençant par "UK", voir [LiveChannelPrefix]), puis choisit la
 * meilleure variante qualité disponible, comme
 * [fr.streamia.tv.tvprogramme.TvProgrammeChannelMatcher] le fait pour les chaînes françaises.
 */
class UkGuideChannelMatcher {
    private val indexCache = PrefixedChannelIndexCache(LiveChannelPrefix.UK)

    fun ukLiveChannels(catalog: Catalog): List<MediaEntry> = LiveChannelPrefix.UK.channels(catalog)

    fun resolve(
        programmes: List<UkProgrammeItem>,
        catalog: Catalog,
    ): List<ResolvedUkProgrammeItem> {
        if (programmes.isEmpty()) return emptyList()
        return resolve(programmes, indexCache.indexFor(catalog))
    }

    fun resolve(
        programmes: List<UkProgrammeItem>,
        channels: List<MediaEntry>,
    ): List<ResolvedUkProgrammeItem> {
        if (programmes.isEmpty() || channels.isEmpty()) return emptyList()
        return resolve(programmes, PrefixedChannelIndex(LiveChannelPrefix.UK, channels))
    }

    private fun resolve(programmes: List<UkProgrammeItem>, index: PrefixedChannelIndex): List<ResolvedUkProgrammeItem> {
        val seenChannels = mutableSetOf<String>()
        return programmes.mapNotNull { programme ->
            val best = index.best(programme.channelName) ?: return@mapNotNull null
            if (!seenChannels.add(best.key)) return@mapNotNull null
            ResolvedUkProgrammeItem(programme = programme, channel = best)
        }
    }
}

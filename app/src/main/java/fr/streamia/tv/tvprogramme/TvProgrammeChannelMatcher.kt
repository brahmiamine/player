package fr.streamia.tv.tvprogramme

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.LiveChannelPrefix
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.PrefixedChannelIndex
import fr.streamia.tv.domain.PrefixedChannelIndexCache

/**
 * Associe les chaînes de tv-programme.com uniquement aux chaînes Direct "FR" de la playlist
 * (catégorie ou nom de chaîne commençant par "FR", voir [LiveChannelPrefix]), puis choisit la
 * meilleure variante qualité disponible (voir [PrefixedChannelIndex]).
 */
class TvProgrammeChannelMatcher {
    /** Index partagé par les blocs « en direct » et « ce soir » de l'accueil. */
    private val indexCache = PrefixedChannelIndexCache(LiveChannelPrefix.FR)

    fun frenchLiveChannels(catalog: Catalog): List<MediaEntry> = LiveChannelPrefix.FR.channels(catalog)

    fun resolve(
        programmes: List<TvProgrammeItem>,
        catalog: Catalog,
    ): List<ResolvedTvProgrammeItem> = if (programmes.isEmpty()) emptyList() else resolve(programmes, indexCache.indexFor(catalog))

    fun resolveNow(
        programmes: List<TvProgrammeNowItem>,
        catalog: Catalog,
    ): List<ResolvedTvProgrammeNowItem> = if (programmes.isEmpty()) emptyList() else resolveNow(programmes, indexCache.indexFor(catalog))

    fun resolveNow(
        programmes: List<TvProgrammeNowItem>,
        channels: List<MediaEntry>,
    ): List<ResolvedTvProgrammeNowItem> {
        if (programmes.isEmpty() || channels.isEmpty()) return emptyList()
        return resolveNow(programmes, PrefixedChannelIndex(LiveChannelPrefix.FR, channels))
    }

    private fun resolveNow(programmes: List<TvProgrammeNowItem>, index: PrefixedChannelIndex): List<ResolvedTvProgrammeNowItem> {
        val seenChannels = mutableSetOf<String>()
        return programmes.mapNotNull { programme ->
            val best = index.best(programme.channelName) ?: return@mapNotNull null
            if (!seenChannels.add(best.key)) return@mapNotNull null
            ResolvedTvProgrammeNowItem(programme = programme, channel = best)
        }
    }

    fun resolve(
        programmes: List<TvProgrammeItem>,
        channels: List<MediaEntry>,
    ): List<ResolvedTvProgrammeItem> {
        if (programmes.isEmpty() || channels.isEmpty()) return emptyList()
        return resolve(programmes, PrefixedChannelIndex(LiveChannelPrefix.FR, channels))
    }

    private fun resolve(programmes: List<TvProgrammeItem>, index: PrefixedChannelIndex): List<ResolvedTvProgrammeItem> {
        val seenChannels = mutableSetOf<String>()
        return programmes.mapNotNull { programme ->
            val best = index.best(programme.channelName) ?: return@mapNotNull null
            if (!seenChannels.add(best.key)) return@mapNotNull null
            ResolvedTvProgrammeItem(programme = programme, channel = best)
        }
    }
}

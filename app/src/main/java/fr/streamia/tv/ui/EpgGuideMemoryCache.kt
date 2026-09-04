package fr.streamia.tv.ui

import fr.streamia.tv.domain.EpgGuide
import java.time.LocalDate
import java.util.LinkedHashMap

internal data class EpgGuideCacheKey(
    val profileId: String,
    val date: LocalDate,
    val offsetHours: Int,
)

/**
 * Petit cache LRU en mémoire pour les journées EPG déjà matérialisées depuis SQLite.
 *
 * SQLite reste la source de vérité persistante : ce cache ne sert qu'à rendre les allers-retours
 * entre aujourd'hui / hier / demain instantanés pendant la session courante. Sa taille reste
 * volontairement très petite pour ne pas gonfler la mémoire sur les gros catalogues TV.
 */
internal class EpgGuideMemoryCache(private val maxEntries: Int = 3) {
    init {
        require(maxEntries > 0) { "maxEntries must be > 0" }
    }

    private val entries = object : LinkedHashMap<EpgGuideCacheKey, EpgGuide>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EpgGuideCacheKey, EpgGuide>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(profileId: String, date: LocalDate, offsetHours: Int): EpgGuide? =
        entries[EpgGuideCacheKey(profileId, date, offsetHours)]

    @Synchronized
    fun put(profileId: String, date: LocalDate, offsetHours: Int, guide: EpgGuide) {
        entries[EpgGuideCacheKey(profileId, date, offsetHours)] = guide
    }

    @Synchronized
    fun clearProfile(profileId: String) {
        entries.keys.removeAll { it.profileId == profileId }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}

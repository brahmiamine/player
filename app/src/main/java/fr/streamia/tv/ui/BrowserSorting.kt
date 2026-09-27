package fr.streamia.tv.ui

import androidx.compose.runtime.key
import fr.streamia.tv.data.LiveChannelSortOrder
import fr.streamia.tv.data.VodSortOrder
import fr.streamia.tv.domain.MediaEntry
import kotlinx.coroutines.Dispatchers

// Tris d'affichage du navigateur, mémorisés entre recompositions.

/**
 * Ordre d'affichage des chaînes Direct au sein d'une catégorie, choisi dans Paramètres. `Provider`
 * conserve l'ordre déjà renvoyé par le fournisseur/SQLite (aucun tri, coût nul) ; `Alphabetical`
 * ignore les accents français plutôt que trier par point de code Unicode (voir [sortedAlphabetically]).
 */
internal fun sortedForLiveDisplay(entries: List<MediaEntry>, order: LiveChannelSortOrder): List<MediaEntry> = when (order) {
    LiveChannelSortOrder.Provider -> entries
    LiveChannelSortOrder.Number -> entries.sortedBy(MediaEntry::number)
    LiveChannelSortOrder.Alphabetical -> sortedAlphabetically(entries)
}

/**
 * Tri alphabétique à la française (accents ignorés, puis casse) : une clé calculée **une fois par
 * titre**, puis de simples comparaisons de chaînes. `Collator.compare` à chaque comparaison
 * coûtait des centaines de milliers d'appels lents sur le thread principal pour « Tout » (des
 * dizaines de milliers de chaînes) : plusieurs secondes de gel à l'ouverture de la liste.
 */
internal fun sortedAlphabetically(entries: List<MediaEntry>): List<MediaEntry> {
    if (entries.size < 2) return entries
    val keys = HashMap<String, String>(entries.size * 2)
    fun key(name: String) = keys.getOrPut(name) { fr.streamia.tv.domain.catalogSortKey(name) }
    return entries.sortedWith(compareBy<MediaEntry> { key(it.displayName) }.thenBy { it.displayName })
}

/**
 * Dernières listes triées du navigateur, pour la même source (même instance), les mêmes masquages
 * et le même tri : rouvrir la liste (retour du plein écran, aller-retour de catégorie) ne refait
 * pas le tri. Utilisé depuis le thread principal et depuis Dispatchers.Default.
 */
internal object BrowserSortMemo {
    private class Entry(
        val source: List<MediaEntry>,
        val hiddenEntries: Set<String>,
        val excludedCategoryIds: Set<String>,
        val order: Any,
        val result: List<MediaEntry>,
    )

    private val recent = ArrayDeque<Entry>()

    @Synchronized
    fun get(source: List<MediaEntry>, hiddenEntries: Set<String>, excludedCategoryIds: Set<String>, order: Any): List<MediaEntry>? =
        recent.firstOrNull {
            it.source === source && it.hiddenEntries === hiddenEntries && it.order == order && it.excludedCategoryIds == excludedCategoryIds
        }?.result

    @Synchronized
    fun put(source: List<MediaEntry>, hiddenEntries: Set<String>, excludedCategoryIds: Set<String>, order: Any, result: List<MediaEntry>) {
        recent.addFirst(Entry(source, hiddenEntries, excludedCategoryIds, order, result))
        while (recent.size > MAX_ENTRIES) recent.removeLast()
    }

    private const val MAX_ENTRIES = 6
}

/**
 * `RecentlyAdded`/`Rating` retombent en fin de liste pour une entrée sans date d'ajout / note (le
 * fournisseur ne les fournit pas toujours) plutôt que de les faire remonter en tête par accident :
 * `sortedByDescending` traite `null` comme la plus petite valeur, donc toujours en dernier ici.
 * Pas d'option « année » : cette donnée vient de [fr.streamia.tv.domain.MediaDetails], récupérée
 * à la demande pour un seul contenu, jamais en bloc pour toute une catégorie du catalogue léger.
 */
internal fun sortedForVodDisplay(entries: List<MediaEntry>, order: VodSortOrder): List<MediaEntry> = when (order) {
    VodSortOrder.Provider -> entries
    VodSortOrder.Alphabetical -> sortedAlphabetically(entries)
    VodSortOrder.RecentlyAdded -> entries.sortedByDescending(MediaEntry::addedAtEpochSeconds)
    VodSortOrder.Rating -> entries.sortedByDescending { entry -> entry.rating?.takeIf { it in 0.0..10.0 } }
}

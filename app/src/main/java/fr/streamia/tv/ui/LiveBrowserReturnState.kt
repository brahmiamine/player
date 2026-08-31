package fr.streamia.tv.ui

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType

/**
 * Mémoire très courte du dernier Live quitté en plein écran.
 * Elle permet au navigateur Live de rouvrir exactement sur la chaîne qui jouait,
 * sans introduire un second écran/overlay différent de l'interface principale.
 */
object LiveBrowserReturnState {
    @Volatile
    private var pendingEntryKey: String? = null

    fun remember(entryKey: String) {
        pendingEntryKey = entryKey
    }

    fun remember(entry: MediaEntry) {
        if (entry.type == MediaType.Live) pendingEntryKey = entry.key
    }

    fun consume(): String? = pendingEntryKey.also { pendingEntryKey = null }
}

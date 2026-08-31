package fr.streamia.tv.ui

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

    fun consume(): String? = pendingEntryKey.also { pendingEntryKey = null }
}

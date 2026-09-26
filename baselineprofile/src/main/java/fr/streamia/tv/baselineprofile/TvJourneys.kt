package fr.streamia.tv.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.UiDevice

const val TARGET_PACKAGE = "fr.streamia.tv"

/**
 * Parcours télécommande communs au profil et aux mesures. Prérequis : une liste (Xtream ou M3U)
 * déjà ouverte une fois dans l'app sur l'appareil, pour que le démarrage arrive sur l'accueil.
 */
fun MacrobenchmarkScope.openLiveAndZap(zaps: Int = 10) {
    startActivityAndWait()
    val remote = UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
    remote.waitForIdle()
    // Focus initial sur la tuile « TV en direct » en tête de l'accueil.
    remote.pressKey(KeyEvent.KEYCODE_DPAD_CENTER)
    Thread.sleep(2_000)
    // Défilement de la liste des chaînes, puis plein écran et zapping CH+.
    repeat(15) { remote.pressKey(KeyEvent.KEYCODE_DPAD_DOWN) }
    remote.pressKey(KeyEvent.KEYCODE_DPAD_CENTER)
    Thread.sleep(1_000)
    remote.pressKey(KeyEvent.KEYCODE_DPAD_CENTER)
    Thread.sleep(3_000)
    repeat(zaps) {
        remote.pressKey(KeyEvent.KEYCODE_CHANNEL_UP)
        Thread.sleep(1_500)
    }
    remote.pressBack()
    remote.pressBack()
    remote.waitForIdle()
}

/** Accueil → Films : défilement de la grille (pages SQLite, affiches), retour. */
fun MacrobenchmarkScope.browseMovies() {
    startActivityAndWait()
    val remote = UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
    remote.waitForIdle()
    // Tuile « TV en direct » focalisée ; à droite : « Films ».
    remote.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT)
    remote.pressKey(KeyEvent.KEYCODE_DPAD_CENTER)
    Thread.sleep(2_000)
    // Rail des catégories → grille, puis défilement de plusieurs rangées (chargement de pages).
    repeat(3) { remote.pressKey(KeyEvent.KEYCODE_DPAD_DOWN) }
    remote.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT)
    repeat(25) { remote.pressKey(KeyEvent.KEYCODE_DPAD_DOWN) }
    repeat(3) { remote.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
    remote.pressBack()
    remote.pressBack()
    remote.pressBack()
    remote.waitForIdle()
}

/** Accueil → Guide TV : défilement des chaînes et de la fenêtre horaire. */
fun MacrobenchmarkScope.openGuide() {
    startActivityAndWait()
    val remote = UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
    remote.waitForIdle()
    // TV en direct → Films → Recherche → Guide TV.
    remote.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT)
    remote.pressKey(KeyEvent.KEYCODE_DPAD_DOWN)
    remote.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT)
    remote.pressKey(KeyEvent.KEYCODE_DPAD_CENTER)
    Thread.sleep(2_500)
    repeat(20) { remote.pressKey(KeyEvent.KEYCODE_DPAD_DOWN) }
    repeat(6) { remote.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
    remote.pressBack()
    remote.pressBack()
    remote.waitForIdle()
}

/** Accueil : descente dans toutes les rangées (guides, matchs, recommandations) puis retour en haut. */
fun MacrobenchmarkScope.scrollHome() {
    startActivityAndWait()
    val remote = UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
    remote.waitForIdle()
    Thread.sleep(2_000)
    repeat(14) { remote.pressKey(KeyEvent.KEYCODE_DPAD_DOWN) }
    repeat(4) { remote.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
    repeat(14) { remote.pressKey(KeyEvent.KEYCODE_DPAD_UP) }
    remote.waitForIdle()
}

private fun UiDevice.pressKey(code: Int) {
    pressKeyCode(code)
    waitForIdle(300)
}

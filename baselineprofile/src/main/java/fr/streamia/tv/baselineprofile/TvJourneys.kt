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

private fun UiDevice.pressKey(code: Int) {
    pressKeyCode(code)
    waitForIdle(300)
}

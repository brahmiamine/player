package fr.streamia.tv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `./gradlew :app:generateBaselineProfile` → remplace app/src/main/generated/baselineProfiles. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * Profil de démarrage : seulement ce qui s'exécute jusqu'au premier écran. Il guide la
     * disposition du DEX ; y mettre tous les parcours (comme avant) annulait cette optimisation.
     */
    @Test
    fun startup() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
    }

    /** Baseline profile : parcours Direct, Films, Guide TV et accueil (compilés à l'avance). */
    @Test
    fun navigation() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = false) {
        openLiveAndZap(zaps = 5)
        browseMovies()
        openGuide()
        scrollHome()
    }
}

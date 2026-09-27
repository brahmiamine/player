package fr.streamia.tv.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Remplace [Dispatchers.Main] (utilisé par `viewModelScope`) par un [UnconfinedTestDispatcher] :
 * les coroutines de l'interface s'exécutent alors immédiatement sur le thread appelant, ce qui rend
 * les transitions d'écran déterministes sans attente ni avance manuelle du temps virtuel. Les
 * `delay(...)` des chargements différés restent suspendus (jamais avancés) et n'interfèrent donc pas.
 *
 * Les accès à `Dispatchers.IO` du ViewModel (lecture du disque au démarrage) restent sur le vrai
 * pool IO : ils sont attendus via [StreamiaViewModel.awaitStartupData] plutôt que par le temps virtuel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

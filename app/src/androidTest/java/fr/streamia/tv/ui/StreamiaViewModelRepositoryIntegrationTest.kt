package fr.streamia.tv.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.streamia.tv.data.CatalogCache
import fr.streamia.tv.data.CredentialsStore
import fr.streamia.tv.data.PlaylistKind
import fr.streamia.tv.data.PlaylistProfile
import fr.streamia.tv.data.PlaylistStore
import fr.streamia.tv.data.XtreamRepository
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Intégration bout-en-bout du ViewModel sur le vrai dépôt, sans aucun serveur Xtream : la liste et
 * son catalogue sont écrits dans la vraie base SQLite et le vrai PlaylistStore chiffré (Keystore),
 * puis le ViewModel ouvre la liste et navigue comme en conditions réelles. C'est la couche
 * intermédiaire entre l'interface et la base qui est vérifiée ici — la même que [CatalogCache] et
 * [XtreamRepository] traversent en production.
 *
 * Les enrichissements de l'accueil (JustWatch, guides tiers) partent en tâche de fond et sont
 * ignorés s'ils échouent ; ils n'interviennent pas dans les assertions de navigation.
 */
@RunWith(AndroidJUnit4::class)
class StreamiaViewModelRepositoryIntegrationTest {

    private lateinit var context: Context
    private lateinit var repository: XtreamRepository
    private val profileId = "vm-itest-${System.nanoTime()}"

    private val movieCategory = MediaCategory("films", "Films", MediaType.Movie)
    private val movie1 = MediaEntry(id = 1, name = "Film Un", categoryId = "films", iconUrl = null, number = 1, type = MediaType.Movie)
    private val movie2 = MediaEntry(id = 2, name = "Film Deux", categoryId = "films", iconUrl = null, number = 2, type = MediaType.Movie)
    private val movie3 = MediaEntry(id = 3, name = "Film Trois", categoryId = "films", iconUrl = null, number = 3, type = MediaType.Movie)

    @Before
    fun seed() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        repository = XtreamRepository.get(context)

        // Liste Xtream marquée fraîche : ouverte depuis le cache, sans actualisation réseau.
        PlaylistStore(context).upsert(
            PlaylistProfile(
                id = profileId,
                name = "Liste de test",
                kind = PlaylistKind.Xtream,
                serverUrl = "http://serveur.test",
                username = "user",
                password = "pass",
                lastRefreshAt = System.currentTimeMillis(),
            ),
        )

        val catalog = Catalog(
            categories = listOf(movieCategory),
            entries = listOf(movie1, movie2, movie3),
            totalCounts = mapOf(MediaType.Movie to 3),
            categoryCounts = mapOf(Catalog.categoryKey(MediaType.Movie, "films") to 3),
        )
        CatalogCache(context).save(profileId, catalog)
    }

    @After
    fun clean() = runBlocking {
        PlaylistStore(context).delete(profileId)
        CredentialsStore(context).clear()
        CatalogCache(context).clear(profileId)
    }

    /** Attend que l'état de l'interface atteigne un écran répondant au prédicat. */
    private fun awaitState(
        viewModel: StreamiaViewModel,
        predicate: (StreamiaUiState) -> Boolean,
    ): StreamiaUiState = runBlocking {
        withTimeout(15_000) { viewModel.uiState.first(predicate) }
    }

    @Test
    fun openingASeededProfileReachesHomeWithCatalogFromSqlite() {
        val viewModel = StreamiaViewModel(repository)
        viewModel.openProfile(profileId)

        val state = awaitState(viewModel) { it.screen is StreamiaScreen.Home && it.catalog != null }

        assertTrue(state.screen is StreamiaScreen.Home)
        assertEquals(profileId, state.activeProfileId)
        assertNotNull(state.catalog)
        assertEquals(3, state.catalog!!.count(MediaType.Movie))
        assertEquals(listOf(movieCategory), state.catalog!!.categoriesFor(MediaType.Movie))
        assertEquals(
            setOf("Film Un", "Film Deux", "Film Trois"),
            state.catalog!!.entriesFor(MediaType.Movie).map(MediaEntry::name).toSet(),
        )
    }

    @Test
    fun searchCatalogReadsFromTheSeededSqliteIndex() {
        val viewModel = StreamiaViewModel(repository)
        viewModel.openProfile(profileId)
        awaitState(viewModel) { it.screen is StreamiaScreen.Home && it.catalog != null }

        val results = runBlocking { viewModel.searchCatalog("Deux", MediaType.Movie) }

        assertEquals(listOf("Film Deux"), results.map(MediaEntry::name))
    }

    @Test
    fun openingASectionAndMenuNavigationWorkOnTheRealRepository() {
        val viewModel = StreamiaViewModel(repository)
        viewModel.openProfile(profileId)
        awaitState(viewModel) { it.screen is StreamiaScreen.Home && it.catalog != null }

        viewModel.openSection(MediaType.Movie)
        assertEquals(MediaType.Movie, viewModel.uiState.value.browserType)
        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.Browser)

        viewModel.showSettings()
        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.Settings)
        viewModel.backFromMenu()
        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.Browser)
    }

    @Test
    fun logoutReturnsToLoginAndKeepsThePreviousProfile() {
        val viewModel = StreamiaViewModel(repository)
        viewModel.openProfile(profileId)
        awaitState(viewModel) { it.screen is StreamiaScreen.Home && it.catalog != null }

        viewModel.logout()

        val state = awaitState(viewModel) { it.screen is StreamiaScreen.Login }
        assertTrue(state.screen is StreamiaScreen.Login)
        assertEquals(profileId, state.returnProfileId)
    }
}

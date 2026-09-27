package fr.streamia.tv.ui

import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.CatalogPresentation
import fr.streamia.tv.data.CatalogSource
import fr.streamia.tv.data.LoadedCatalog
import fr.streamia.tv.data.PlaylistKind
import fr.streamia.tv.data.PlaylistProfile
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.data.XtreamRepository
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.ServerCredentials
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Régression de navigation : le ViewModel relie les contrôleurs de domaine et porte l'état d'écran.
 * Ces tests pilotent les transitions (ouverture de liste, menu empilé, ouverture de contenu, retour,
 * déconnexion, recherche filtrée) avec un dépôt simulé, sans réseau ni Android — c'est exactement là
 * qu'une régression de navigation se manifesterait avant même de toucher à l'interface.
 */
class StreamiaViewModelNavigationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profileId = "p1"
    private val credentials = ServerCredentials("http://serveur.test", "user", "pass")
    private val profile = PlaylistProfile(
        id = profileId,
        name = "Ma liste",
        kind = PlaylistKind.Xtream,
        serverUrl = "http://serveur.test",
        username = "user",
        password = "pass",
    )

    private val liveCategory = MediaCategory("live_cat", "Direct", MediaType.Live)
    private val movieCategory = MediaCategory("movie_cat", "Films", MediaType.Movie)
    private val hiddenCategory = MediaCategory("hidden_cat", "Cachée", MediaType.Movie)
    private val lockedCategory = MediaCategory("locked_cat", "Verrouillée", MediaType.Movie)

    private val liveEntry = MediaEntry(
        id = 1, name = "TF1", categoryId = "live_cat", iconUrl = null, number = 1, type = MediaType.Live,
    )
    private val movieEntry = MediaEntry(
        id = 100, name = "Film A", categoryId = "movie_cat", iconUrl = null, number = 1, type = MediaType.Movie,
    )
    private val otherMovie = MediaEntry(
        id = 101, name = "Film B", categoryId = "movie_cat", iconUrl = null, number = 2, type = MediaType.Movie,
    )
    private val seriesEntry = MediaEntry(
        id = 200, name = "Série A", categoryId = "series_cat", iconUrl = null, number = 1, type = MediaType.Series, playable = false,
    )

    private val catalog = Catalog(
        categories = listOf(liveCategory, movieCategory, hiddenCategory, lockedCategory),
        entries = listOf(liveEntry, movieEntry, otherMovie, seriesEntry),
    )

    private val repository: XtreamRepository = mockk(relaxed = true)

    /** Prépare le dépôt pour qu'[openProfile] aboutisse à l'accueil avec un catalogue local. */
    private fun stubLoadedProfile(library: UserLibrarySnapshot = UserLibrarySnapshot(), appSettings: AppSettings = AppSettings()) {
        every { repository.profiles() } returns listOf(profile)
        every { repository.appSettings() } returns appSettings
        every { repository.library(profileId) } returns library
        every { repository.profile(profileId) } returns profile
        coEvery { repository.cachedCatalog(profileId) } returns catalog
        coEvery { repository.openProfile(profileId, any()) } returns
            LoadedCatalog(catalog, credentials, CatalogSource.Local, profileId)
        coEvery { repository.prepareCatalogPresentation(any(), any(), any()) } returns
            CatalogPresentation(catalog, library, listOf(profile))
    }

    /** Construit le ViewModel et le laisse ouvrir la liste jusqu'à un accueil stable. */
    private fun loadedViewModel(library: UserLibrarySnapshot = UserLibrarySnapshot(), appSettings: AppSettings = AppSettings()): StreamiaViewModel {
        stubLoadedProfile(library, appSettings)
        val viewModel = StreamiaViewModel(repository)
        runBlocking { withTimeout(5_000) { viewModel.awaitStartupData() } }
        viewModel.openProfile(profileId)
        return viewModel
    }

    @Test
    fun `initial state is the login screen`() {
        every { repository.profiles() } returns emptyList()
        every { repository.appSettings() } returns AppSettings()

        val viewModel = StreamiaViewModel(repository)
        runBlocking { withTimeout(5_000) { viewModel.awaitStartupData() } }

        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.Login)
    }

    @Test
    fun `opening a profile settles on home with catalog and credentials`() {
        val viewModel = loadedViewModel()
        val state = viewModel.uiState.value

        assertTrue(state.screen is StreamiaScreen.Home)
        assertEquals(profileId, state.activeProfileId)
        assertNotNull(state.catalog)
        assertEquals(credentials, state.credentials)
    }

    @Test
    fun `menu screens push the current screen and back returns to it`() {
        val viewModel = loadedViewModel()

        viewModel.showSettings()
        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.Settings)
        assertEquals(StreamiaScreen.Home, viewModel.uiState.value.menuBackStack.last())

        viewModel.backFromMenu()
        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.Home)
        assertTrue(viewModel.uiState.value.menuBackStack.isEmpty())
    }

    @Test
    fun `menu screens chain their back stack across screens`() {
        val viewModel = loadedViewModel()

        viewModel.showSettings()
        viewModel.showSearch()
        viewModel.showAbout()

        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.About)
        assertEquals(
            listOf(StreamiaScreen.Home, StreamiaScreen.Settings, StreamiaScreen.Search),
            viewModel.uiState.value.menuBackStack,
        )

        viewModel.backFromMenu()
        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.Search)
        viewModel.backFromMenu()
        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.Settings)
        viewModel.backFromMenu()
        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.Home)
    }

    @Test
    fun `back from a menu screen opened over the browser returns to the browser`() {
        val viewModel = loadedViewModel()
        viewModel.openSection(MediaType.Movie)
        viewModel.showAbout()

        // Depuis le navigateur, Retour revient au navigateur (écran empilé), pas à l'accueil.
        viewModel.backFromMenu()
        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.Browser)
    }

    @Test
    fun `epg screen does nothing without an active profile`() {
        every { repository.profiles() } returns emptyList()
        every { repository.appSettings() } returns AppSettings()
        val viewModel = StreamiaViewModel(repository)
        runBlocking { withTimeout(5_000) { viewModel.awaitStartupData() } }

        viewModel.showEpg()
        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.Login)
    }

    @Test
    fun `opening a section switches browser and resets the back stack`() {
        val viewModel = loadedViewModel()

        viewModel.openSection(MediaType.Movie)
        val state = viewModel.uiState.value

        assertTrue(state.screen is StreamiaScreen.Browser)
        assertEquals(MediaType.Movie, state.browserType)
        assertTrue(state.menuBackStack.isEmpty())
    }

    @Test
    fun `live entry opened from browser plays and close returns to browser`() {
        val viewModel = loadedViewModel()
        viewModel.openSection(MediaType.Live)

        viewModel.openEntry(liveEntry)
        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.Player)

        viewModel.closePlayer()
        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.Browser)
        assertEquals(MediaType.Live, viewModel.uiState.value.browserType)
    }

    @Test
    fun `movie opened from browser shows details and close returns to browser`() {
        val viewModel = loadedViewModel()
        viewModel.openSection(MediaType.Movie)

        viewModel.openEntry(movieEntry)
        val details = viewModel.uiState.value.screen
        assertTrue(details is StreamiaScreen.MovieDetails)
        assertEquals(movieEntry.key, (details as StreamiaScreen.MovieDetails).movie.key)

        viewModel.closeDetails()
        assertTrue(viewModel.uiState.value.screen is StreamiaScreen.Browser)
    }

    @Test
    fun `similar content from details reopens the previous details on back`() {
        val viewModel = loadedViewModel()
        viewModel.openSection(MediaType.Movie)

        viewModel.openEntry(movieEntry)
        // Film B ouvert depuis la fiche de Film A : A est empilé.
        viewModel.openEntry(otherMovie)
        val current = viewModel.uiState.value.screen as StreamiaScreen.MovieDetails
        assertEquals(otherMovie.key, current.movie.key)

        viewModel.closeDetails()
        val previous = viewModel.uiState.value.screen as StreamiaScreen.MovieDetails
        assertEquals(movieEntry.key, previous.movie.key)
    }

    @Test
    fun `non-playable series opens the series screen rather than the player`() {
        val viewModel = loadedViewModel()
        viewModel.openSection(MediaType.Series)

        viewModel.openEntry(seriesEntry)
        val screen = viewModel.uiState.value.screen
        assertTrue(screen is StreamiaScreen.Series)
        assertEquals(seriesEntry.key, (screen as StreamiaScreen.Series).series.key)
    }

    @Test
    fun `logout returns to login and remembers the previous profile`() {
        val viewModel = loadedViewModel()
        viewModel.logout()

        val state = viewModel.uiState.value
        assertTrue(state.screen is StreamiaScreen.Login)
        assertEquals(profileId, state.returnProfileId)
        assertNull(state.activeProfileId)
    }

    @Test
    fun `search excludes hidden entries and locked categories under parental control`() {
        val library = UserLibrarySnapshot(
            hiddenEntries = setOf(movieEntry.key),
            hiddenCategories = setOf(hiddenCategory.key),
            lockedCategories = setOf(lockedCategory.key),
        )
        val inHiddenCategory = MediaEntry(id = 301, name = "Cachée", categoryId = "hidden_cat", iconUrl = null, number = 3, type = MediaType.Movie)
        val inLockedCategory = MediaEntry(id = 401, name = "Verrouillée", categoryId = "locked_cat", iconUrl = null, number = 4, type = MediaType.Movie)
        val visible = MediaEntry(id = 302, name = "Visible", categoryId = "movie_cat", iconUrl = null, number = 5, type = MediaType.Movie)
        coEvery { repository.search(profileId, any(), any()) } returns
            listOf(movieEntry, inHiddenCategory, inLockedCategory, visible)

        val viewModel = loadedViewModel(library = library, appSettings = AppSettings(parentalControlEnabled = true))

        val results = runBlocking { viewModel.searchCatalog("film", MediaType.Movie) }

        assertEquals(listOf(visible), results)
        assertFalse(results.any { it.key == movieEntry.key })
        assertFalse(results.any { it.categoryId == "hidden_cat" })
        assertFalse(results.any { it.categoryId == "locked_cat" })
    }

    @Test
    fun `locked categories become searchable once the parental pin is entered`() {
        val library = UserLibrarySnapshot(
            hiddenCategories = setOf(hiddenCategory.key),
            lockedCategories = setOf(lockedCategory.key),
        )
        val inLockedCategory = MediaEntry(id = 401, name = "Verrouillée", categoryId = "locked_cat", iconUrl = null, number = 4, type = MediaType.Movie)
        coEvery { repository.search(profileId, any(), any()) } returns listOf(inLockedCategory)
        every { repository.verifyParentalPin("1234") } returns true

        val viewModel = loadedViewModel(library = library, appSettings = AppSettings(parentalControlEnabled = true))
        assertTrue(runBlocking { viewModel.verifyParentalPin("1234") })
        assertTrue(viewModel.uiState.value.parentalUnlocked)

        val results = runBlocking { viewModel.searchCatalog("verrouillée", MediaType.Movie) }
        assertEquals(listOf(inLockedCategory), results)
    }
}

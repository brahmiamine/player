package fr.streamia.tv.ui

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPresentationTest {
    @Test
    fun `home stays behind startup gate while local catalog is hydrating`() {
        assertTrue(
            shouldShowStartupGate(
                StreamiaUiState(
                    booting = false,
                    catalogHydrating = true,
                    screen = StreamiaScreen.Home,
                ),
            ),
        )
    }

    @Test
    fun `startup gate disappears as soon as local catalog is ready`() {
        assertFalse(
            shouldShowStartupGate(
                StreamiaUiState(
                    booting = false,
                    catalogHydrating = false,
                    screen = StreamiaScreen.Home,
                ),
            ),
        )
    }

    @Test
    fun `background hydration does not hide non home screens`() {
        assertFalse(
            shouldShowStartupGate(
                StreamiaUiState(
                    booting = false,
                    catalogHydrating = true,
                    screen = StreamiaScreen.Login,
                ),
            ),
        )
    }

    @Test
    fun `live browser return waits while restored catalog is hydrating`() {
        assertTrue(
            shouldDeferLiveBrowserReturn(
                StreamiaUiState(
                    booting = false,
                    catalogHydrating = true,
                    screen = StreamiaScreen.Player(liveEntry()),
                ),
            ),
        )
    }

    @Test
    fun `live browser return is immediate once local catalog is ready`() {
        assertFalse(
            shouldDeferLiveBrowserReturn(
                StreamiaUiState(
                    booting = false,
                    catalogHydrating = false,
                    screen = StreamiaScreen.Player(liveEntry()),
                ),
            ),
        )
    }

    private fun liveEntry() = MediaEntry(
        id = 1,
        name = "Live",
        displayName = "Live",
        type = MediaType.Live,
        categoryId = "1",
        iconUrl = null,
        number = 1,
        extension = "ts",
    )
}

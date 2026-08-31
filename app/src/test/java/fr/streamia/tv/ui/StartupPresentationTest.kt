package fr.streamia.tv.ui

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
    fun `background hydration does not hide an active player`() {
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
}

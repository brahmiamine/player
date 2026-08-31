package fr.streamia.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupPresentationTest {
    @Test
    fun `leaving restored player while local catalog hydrates keeps home behind startup gate`() {
        assertEquals(
            PlayerExitDestination.StartupGate,
            resolvePlayerExitDestination(
                catalogHydrating = true,
                returnToSeries = false,
                hasSeriesDetails = false,
            ),
        )
    }

    @Test
    fun `leaving player after catalog is ready returns to normal destinations`() {
        assertEquals(
            PlayerExitDestination.Series,
            resolvePlayerExitDestination(
                catalogHydrating = false,
                returnToSeries = true,
                hasSeriesDetails = true,
            ),
        )
        assertEquals(
            PlayerExitDestination.Browser,
            resolvePlayerExitDestination(
                catalogHydrating = false,
                returnToSeries = false,
                hasSeriesDetails = false,
            ),
        )
    }
}

package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogLoadProgressTest {
    @Test
    fun progressionEtSecoursNonNecessaire() {
        var p = CatalogLoadProgress()
        assertTrue(p.loading)
        assertEquals(5, p.totalCount)

        p = p.with(CatalogStep.Live, CatalogStepStatus.Running, 0)
        assertEquals(CatalogStep.Live, p.current)
        p = p.with(CatalogStep.Live, CatalogStepStatus.Done, 4200)
        assertEquals(4200, p.counts[CatalogStep.Live])
        assertEquals(1, p.doneCount)

        p = p.with(CatalogStep.M3uFallback, CatalogStepStatus.Skipped, 0)
        assertEquals(4, p.totalCount)
    }

    @Test
    fun echecOuFinArretentLeChargeur() {
        assertFalse(CatalogLoadProgress(failure = "Hors ligne").loading)
        assertFalse(CatalogLoadProgress(finished = true).loading)
    }
}

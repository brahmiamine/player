package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `equal versions compare to zero`() {
        assertEquals(0, compareSemVer("1.5.7", "1.5.7"))
    }

    @Test
    fun `numeric comparison beats lexicographic order`() {
        assertTrue(compareSemVer("1.10.0", "1.9.0") > 0)
        assertTrue(compareSemVer("1.9.0", "1.10.0") < 0)
    }

    @Test
    fun `patch and minor differences are detected`() {
        assertTrue(compareSemVer("1.5.8", "1.5.7") > 0)
        assertTrue(compareSemVer("1.6.0", "1.5.9") > 0)
        assertTrue(compareSemVer("2.0.0", "1.9.9") > 0)
    }

    @Test
    fun `missing components default to zero`() {
        assertEquals(0, compareSemVer("1.5", "1.5.0"))
        assertTrue(compareSemVer("1.5.1", "1.5") > 0)
    }

    @Test
    fun `build variant suffix on the running app version is ignored`() {
        // BuildConfig#VERSION_NAME porte le suffixe de variante à l'exécution ("1.5.7-optimized",
        // "1.5.7-debug") : seuls les chiffres en tête de chaque segment doivent compter.
        assertEquals(0, compareSemVer("1.5.7-optimized", "1.5.7"))
        assertEquals(0, compareSemVer("1.5.7-debug", "1.5.7"))
        assertTrue(compareSemVer("1.6.0", "1.5.7-optimized") > 0)
    }
}

package fr.streamia.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserLibrarySnapshotTest {
    @Test
    fun `favorites do not invalidate catalog layout projection`() {
        val captured = UserLibrarySnapshot(favoriteEntries = setOf("Live:1"))
        val current = captured.copy(favoriteEntries = setOf("Live:1", "Movie:2"))

        assertTrue(current.hasSameCatalogLayoutAs(captured))
    }

    @Test
    fun `entry moves invalidate catalog layout projection`() {
        val captured = UserLibrarySnapshot(movedEntries = mapOf("Movie:2" to "old"))
        val current = captured.copy(movedEntries = mapOf("Movie:2" to "new"))

        assertFalse(current.hasSameCatalogLayoutAs(captured))
    }

    @Test
    fun `category order invalidates catalog layout projection`() {
        val captured = UserLibrarySnapshot(categoryOrder = mapOf("Movie" to listOf("Movie:a", "Movie:b")))
        val current = captured.copy(categoryOrder = mapOf("Movie" to listOf("Movie:b", "Movie:a")))

        assertFalse(current.hasSameCatalogLayoutAs(captured))
    }
}

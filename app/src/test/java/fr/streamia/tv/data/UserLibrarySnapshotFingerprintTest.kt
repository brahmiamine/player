package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UserLibrarySnapshotFingerprintTest {

    @Test
    fun `fingerprint is stable regardless of map insertion order`() {
        val a = UserLibrarySnapshot(
            categoryOrder = linkedMapOf("LIVE" to listOf("news", "sport"), "MOVIE" to listOf("action")),
            movedEntries = linkedMapOf("tf1" to "2", "m6" to "3"),
        )
        val b = UserLibrarySnapshot(
            categoryOrder = linkedMapOf("MOVIE" to listOf("action"), "LIVE" to listOf("news", "sport")),
            movedEntries = linkedMapOf("m6" to "3", "tf1" to "2"),
        )

        assertEquals(a.catalogLayoutFingerprint(), b.catalogLayoutFingerprint())
    }

    @Test
    fun `favorites never affect the fingerprint`() {
        val withoutFavorites = UserLibrarySnapshot(movedEntries = mapOf("tf1" to "2"))
        val withFavorites = withoutFavorites.copy(
            favoriteEntries = setOf("tf1", "m6"),
            favoriteCategories = setOf("sport"),
        )

        assertEquals(withoutFavorites.catalogLayoutFingerprint(), withFavorites.catalogLayoutFingerprint())
    }

    @Test
    fun `history never affects the fingerprint`() {
        val withoutHistory = UserLibrarySnapshot(categoryOrder = mapOf("LIVE" to listOf("news")))
        val withHistory = withoutHistory.copy(
            history = listOf(
                PlaybackHistoryItem(
                    entry = fr.streamia.tv.domain.MediaEntry(
                        id = 1,
                        name = "TF1",
                        type = fr.streamia.tv.domain.MediaType.Live,
                        categoryId = "news",
                        iconUrl = null,
                        number = 1,
                    ),
                    positionMs = 1_000L,
                    durationMs = 10_000L,
                    updatedAt = 0L,
                ),
            ),
        )

        assertEquals(withoutHistory.catalogLayoutFingerprint(), withHistory.catalogLayoutFingerprint())
    }

    @Test
    fun `a different category order changes the fingerprint`() {
        val original = UserLibrarySnapshot(categoryOrder = mapOf("LIVE" to listOf("news", "sport")))
        val reordered = UserLibrarySnapshot(categoryOrder = mapOf("LIVE" to listOf("sport", "news")))

        assertNotEquals(original.catalogLayoutFingerprint(), reordered.catalogLayoutFingerprint())
    }

    @Test
    fun `a different moved entry destination changes the fingerprint`() {
        val original = UserLibrarySnapshot(movedEntries = mapOf("tf1" to "2"))
        val movedAgain = UserLibrarySnapshot(movedEntries = mapOf("tf1" to "3"))

        assertNotEquals(original.catalogLayoutFingerprint(), movedAgain.catalogLayoutFingerprint())
    }

    @Test
    fun `an empty snapshot yields a stable non-crashing fingerprint`() {
        assertEquals(UserLibrarySnapshot().catalogLayoutFingerprint(), UserLibrarySnapshot().catalogLayoutFingerprint())
    }
}

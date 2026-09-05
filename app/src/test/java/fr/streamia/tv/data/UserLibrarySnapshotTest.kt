package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.shouldRecordPlaybackInHistory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserLibrarySnapshotTest {
    private fun historyItem(positionMs: Long, durationMs: Long) = PlaybackHistoryItem(
        entry = MediaEntry(id = 1, name = "Film", categoryId = "0", iconUrl = null, number = 0, type = MediaType.Movie),
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = 0,
    )

    @Test
    fun `below 5 seconds is not resumable`() {
        assertFalse(historyItem(positionMs = 4_999, durationMs = 6_000_000).isResumable())
    }

    @Test
    fun `at 5 seconds with a duration left is resumable`() {
        assertTrue(historyItem(positionMs = 5_000, durationMs = 6_000_000).isResumable())
    }

    @Test
    fun `within the last 30 seconds of duration is not resumable`() {
        assertFalse(historyItem(positionMs = 5_975_000, durationMs = 6_000_000).isResumable())
    }

    @Test
    fun `unknown duration is resumable once past 5 seconds`() {
        assertTrue(historyItem(positionMs = 5_000, durationMs = 0).isResumable())
    }

    @Test
    fun `VOD history starts at five seconds for movies and series`() {
        assertFalse(shouldRecordPlaybackInHistory(MediaType.Movie, 4_999L))
        assertFalse(shouldRecordPlaybackInHistory(MediaType.Series, 4_999L))
        assertTrue(shouldRecordPlaybackInHistory(MediaType.Movie, 5_000L))
        assertTrue(shouldRecordPlaybackInHistory(MediaType.Series, 5_000L))
    }

    @Test
    fun `live history remains eligible without a resumable position`() {
        assertTrue(shouldRecordPlaybackInHistory(MediaType.Live, 0L))
    }

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

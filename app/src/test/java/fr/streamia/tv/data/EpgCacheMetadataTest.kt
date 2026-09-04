package fr.streamia.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgCacheMetadataTest {
    private fun metadata(
        syncedAtMillis: Long = 1_000L,
        programCount: Int = 10,
    ) = EpgCacheMetadata(
        syncedAtMillis = syncedAtMillis,
        sourceUrl = "https://example.test/epg.xml",
        minStartEpochSeconds = 100L,
        maxEndEpochSeconds = 500L,
        programCount = programCount,
    )

    @Test
    fun `cache is fresh strictly inside configured age`() {
        assertTrue(metadata().isFreshAt(nowMillis = 1_999L, maxAgeMillis = 1_000L))
        assertFalse(metadata().isFreshAt(nowMillis = 2_000L, maxAgeMillis = 1_000L))
    }

    @Test
    fun `empty cache is never fresh`() {
        assertFalse(metadata(programCount = 0).isFreshAt(nowMillis = 1_100L, maxAgeMillis = 1_000L))
    }

    @Test
    fun `future sync timestamp is not considered fresh`() {
        assertFalse(metadata(syncedAtMillis = 2_000L).isFreshAt(nowMillis = 1_000L, maxAgeMillis = 1_000L))
    }
}

package fr.streamia.tv.ui

import fr.streamia.tv.domain.EpgChannel
import fr.streamia.tv.domain.EpgGuide
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class EpgGuideMemoryCacheTest {
    private fun guide(id: String) = EpgGuide(
        channels = mapOf(id to EpgChannel(channelId = id, displayName = id)),
    )

    @Test
    fun `least recently used day is evicted when cache is full`() {
        val cache = EpgGuideMemoryCache(maxEntries = 2)
        val day1 = LocalDate.of(2026, 9, 3)
        val day2 = LocalDate.of(2026, 9, 4)
        val day3 = LocalDate.of(2026, 9, 5)

        cache.put("profile", day1, 0, guide("1"))
        cache.put("profile", day2, 0, guide("2"))

        // Relire J1 le rend plus récent que J2.
        assertNotNull(cache.get("profile", day1, 0))
        cache.put("profile", day3, 0, guide("3"))

        assertNotNull(cache.get("profile", day1, 0))
        assertNull(cache.get("profile", day2, 0))
        assertNotNull(cache.get("profile", day3, 0))
    }

    @Test
    fun `profile invalidation keeps other profiles intact`() {
        val cache = EpgGuideMemoryCache(maxEntries = 3)
        val date = LocalDate.of(2026, 9, 4)

        cache.put("profile-a", date, 0, guide("a"))
        cache.put("profile-b", date, 0, guide("b"))

        cache.clearProfile("profile-a")

        assertNull(cache.get("profile-a", date, 0))
        assertNotNull(cache.get("profile-b", date, 0))
    }

    @Test
    fun `time offset is part of the cache key`() {
        val cache = EpgGuideMemoryCache(maxEntries = 3)
        val date = LocalDate.of(2026, 9, 4)

        cache.put("profile", date, 0, guide("base"))

        assertNotNull(cache.get("profile", date, 0))
        assertNull(cache.get("profile", date, 1))
    }
}

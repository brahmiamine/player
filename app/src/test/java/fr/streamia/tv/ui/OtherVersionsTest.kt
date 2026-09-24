package fr.streamia.tv.ui

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class OtherVersionsTest {
    private fun movie(id: Int, name: String) =
        MediaEntry(id = id, name = name, categoryId = "c", iconUrl = null, number = id, type = MediaType.Movie)

    @Test
    fun `finds same title under other prefixes only`() {
        val source = movie(1, "4K-TOP - Michael (2026)")
        val catalog = listOf(
            source,
            movie(2, "FR - Michael (2026)"),
            movie(3, "|EN| Michael 2026"),
            movie(4, "AR: Michael"),
            movie(5, "FR - Michael (1990)"), // autre film, même titre
            movie(6, "FR - Michael Clayton (2007)"),
        )
        val versions = otherVersionsOf(source, catalog)
        assertEquals(listOf(2, 3, 4), versions.map { it.entry.id })
        assertEquals(listOf("FR", "EN", "AR"), versions.map { it.reason })
    }

    @Test
    fun `matches all provider prefix styles`() {
        val source = movie(1, "RO - 12 Years a Slave")
        val names = listOf(
            "EN-TOP -183.12.Years.A.Slave.2013", "EN - 12 Years a Slave (2013)", "DE-TOP - 49. 12 Years a Slave (2013)",
            "KU-B - 12 Years a Slave (2013)", "4K-AR - 12 Years a Slave (2013)", "AR-SUBS - 12 Years a Slave (2013)",
            "IR - 12 Years a Slave",
        )
        val catalog = listOf(source) + names.mapIndexed { i, n -> movie(i + 2, n) } + movie(99, "RO - 12 Round Gun")
        assertEquals(names.size, otherVersionsOf(source, catalog).size)
        assertEquals("12 years slave", versionSearchQuery(source))
    }
}

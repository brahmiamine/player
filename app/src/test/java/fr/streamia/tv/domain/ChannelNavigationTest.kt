package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelNavigationTest {
    private val channels = listOf(
        MediaEntry(id = 1, name = "One", categoryId = "news", iconUrl = null, number = 1),
        MediaEntry(id = 2, name = "Two", categoryId = "news", iconUrl = null, number = 2),
        MediaEntry(id = 3, name = "Three", categoryId = "news", iconUrl = null, number = 3),
    )

    @Test
    fun `next channel wraps to the first channel`() {
        assertEquals(1, channels.adjacentTo(currentKey = channels[2].key, delta = 1)?.id)
    }

    @Test
    fun `previous channel wraps to the last channel`() {
        assertEquals(3, channels.adjacentTo(currentKey = channels[0].key, delta = -1)?.id)
    }

    @Test
    fun `empty list has no adjacent channel`() {
        assertEquals(null, emptyList<MediaEntry>().adjacentTo(currentKey = "Live:1", delta = 1))
    }
}

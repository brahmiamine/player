package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelNavigationTest {
    private val channels = listOf(
        LiveChannel(1, "One", "news", null, 1),
        LiveChannel(2, "Two", "news", null, 2),
        LiveChannel(3, "Three", "news", null, 3),
    )

    @Test
    fun `next channel wraps to the first channel`() {
        assertEquals(1, channels.adjacentTo(currentId = 3, delta = 1)?.id)
    }

    @Test
    fun `previous channel wraps to the last channel`() {
        assertEquals(3, channels.adjacentTo(currentId = 1, delta = -1)?.id)
    }

    @Test
    fun `empty list has no adjacent channel`() {
        assertEquals(null, emptyList<LiveChannel>().adjacentTo(currentId = 1, delta = 1))
    }
}

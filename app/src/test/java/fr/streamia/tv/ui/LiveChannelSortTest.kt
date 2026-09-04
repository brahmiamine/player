package fr.streamia.tv.ui

import fr.streamia.tv.data.LiveChannelSortOrder
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveChannelSortTest {
    private fun channel(id: Int, name: String, number: Int) = MediaEntry(
        id = id,
        name = name,
        displayName = name,
        type = MediaType.Live,
        categoryId = "1",
        iconUrl = null,
        number = number,
    )

    private val channels = listOf(
        channel(1, "TF1", number = 30),
        channel(2, "Éducation +", number = 10),
        channel(3, "Arte", number = 20),
    )

    @Test
    fun `provider order is untouched`() {
        assertEquals(channels.map { it.id }, sortedForLiveDisplay(channels, LiveChannelSortOrder.Provider).map { it.id })
    }

    @Test
    fun `number order sorts by channel number`() {
        assertEquals(listOf(2, 3, 1), sortedForLiveDisplay(channels, LiveChannelSortOrder.Number).map { it.id })
    }

    @Test
    fun `alphabetical order is locale-aware`() {
        // Un tri par point de code Unicode placerait "Éducation +" après "TF1" ; le tri français
        // attendu (comme celui de l'Organizer) le classe avec les E.
        assertEquals(
            listOf("Arte", "Éducation +", "TF1"),
            sortedForLiveDisplay(channels, LiveChannelSortOrder.Alphabetical).map { it.displayName },
        )
    }
}

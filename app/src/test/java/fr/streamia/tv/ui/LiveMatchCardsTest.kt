package fr.streamia.tv.ui

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.liveonsat.LiveOnSatChannel
import fr.streamia.tv.liveonsat.LiveOnSatMatch
import fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveMatchCardsTest {
    private fun channel(id: Int) = MediaEntry(id = id, name = "CH$id", categoryId = "1", iconUrl = null, number = id)

    private fun match(start: Long, matched: Map<String, List<MediaEntry>>) = ResolvedLiveOnSatMatch(
        LiveOnSatMatch("Ligue 1", "A$start", "B$start", startEpochSeconds = start, channels = listOf(LiveOnSatChannel("beIN 1", false), LiveOnSatChannel("Canal+", false))),
        matched,
    )

    @Test
    fun onlyLiveMatchesWithRecognisedChannels() {
        val now = 10_000L
        val cards = liveMatchCards(
            listOf(
                match(now - 600, mapOf("beIN 1" to listOf(channel(1), channel(2)))), // en direct, reconnu → 1 carte (HD/FHD dédoublonnées)
                match(now - 600, emptyMap()), // en direct, aucune chaîne reconnue → exclu
                match(now + 3_600, mapOf("Canal+" to listOf(channel(3)))), // à venir → exclu
            ),
            now,
        )
        assertEquals(listOf(1), cards.map { it.channel?.id })
    }

    @Test
    fun unresolvedLiveMatchesShowPendingChannelWhileResolving() {
        val now = 10_000L
        val matches = listOf(
            match(now - 600, mapOf("beIN 1" to listOf(channel(1)))),
            match(now - 300, emptyMap()), // pas encore rapproché → carte avec chaîne en squelette
            match(now + 3_600, emptyMap()), // à venir → exclu
        )
        assertEquals(listOf(1, null), liveMatchCards(matches, now, resolvingChannels = true).map { it.channel?.id })
        assertEquals(listOf(1), liveMatchCards(matches, now, resolvingChannels = false).map { it.channel?.id })
    }
}

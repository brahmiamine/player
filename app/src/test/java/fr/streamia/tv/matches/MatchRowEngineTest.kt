package fr.streamia.tv.matches

import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchRowEngineTest {
    private val engine = MatchRowEngine()
    private val now = 1_757_000_000L // point de référence fixe pour des tests déterministes

    @Test
    fun `live match makes the row title switch to live`() {
        val channel = channel(1, "beIN Sports")
        val program = footballProgram(
            title = "PSG - Marseille",
            start = now - 600,
            end = now + 3_000,
        )

        val row = engine.buildRow(mapOf(channel to listOf(program)), now)

        assertEquals("🔴 Matchs en direct", row?.title)
        assertEquals(MatchTemporalState.Live, row?.items?.first()?.temporalState)
    }

    @Test
    fun `only upcoming matches today produce the today title`() {
        val channel = channel(1, "beIN Sports")
        val program = footballProgram(title = "Arsenal vs Chelsea", start = now + 1_800, end = now + 5_400)

        val row = engine.buildRow(mapOf(channel to listOf(program)), now)

        assertEquals("⚽ Matchs aujourd'hui", row?.title)
    }

    @Test
    fun `real slash fixture is detected from sports channel context`() {
        val channel = channel(14300, "VIP: BEIN SPORTS MAX 8 HD")
        val program = EpgProgram(
            title = "Paderborn / Fribourg",
            description = "beIN SPORTS, le plus grand des spectacles",
            startEpochSeconds = now + 1_800,
            endEpochSeconds = now + 7_200,
            category = null,
        )

        val row = engine.buildRow(mapOf(channel to listOf(program)), now)

        assertEquals(1, row?.items?.size)
        assertEquals("Paderborn", row?.items?.first()?.event?.participantA)
        assertEquals("Fribourg", row?.items?.first()?.event?.participantB)
    }

    @Test
    fun `no detected match means no row at all`() {
        val channel = channel(1, "Chaine Generaliste")
        val program = EpgProgram(
            title = "Journal du soir",
            description = "L'actualité du jour.",
            startEpochSeconds = now + 1_800,
            endEpochSeconds = now + 5_400,
            category = "Information",
        )

        val row = engine.buildRow(mapOf(channel to listOf(program)), now)

        assertNull(row)
    }

    @Test
    fun `already ended programmes never appear`() {
        val channel = channel(1, "beIN Sports")
        val program = footballProgram(title = "PSG - Marseille", start = now - 5_000, end = now - 100)

        val row = engine.buildRow(mapOf(channel to listOf(program)), now)

        assertNull(row)
    }

    @Test
    fun `matches beyond the seven day window are excluded`() {
        val channel = channel(1, "beIN Sports")
        val tooFar = now + 10L * 86_400L
        val program = footballProgram(title = "PSG - Marseille", start = tooFar, end = tooFar + 5_400)

        val row = engine.buildRow(mapOf(channel to listOf(program)), now)

        assertNull(row)
    }

    @Test
    fun `hidden channel is excluded even if it broadcasts a match`() {
        val hiddenChannel = channel(1, "beIN Sports")
        val visibleChannel = channel(2, "Canal Foot")
        val programs = mapOf(
            hiddenChannel to listOf(footballProgram("PSG - Marseille", now - 300, now + 3_000)),
            visibleChannel to listOf(footballProgram("Real Madrid vs Barcelona", now + 1_800, now + 5_400)),
        )

        val row = engine.buildRow(programs, now, hiddenEntryKeys = setOf(hiddenChannel.key))

        assertEquals(1, row?.items?.size)
        assertEquals("Real Madrid", row?.items?.first()?.event?.participantA)
    }

    @Test
    fun `hidden category is excluded even if not individually hidden`() {
        val lockedChannel = channel(1, "beIN Sports", categoryId = "locked")
        val programs = mapOf(lockedChannel to listOf(footballProgram("PSG - Marseille", now - 300, now + 3_000)))

        val row = engine.buildRow(programs, now, hiddenCategoryIds = setOf("locked"))

        assertNull(row)
    }

    @Test
    fun `same fixture on two channels is deduplicated into a single card`() {
        val channelA = channel(1, "beIN Sports FR")
        val channelB = channel(2, "beIN Sports EN")
        val programs = mapOf(
            channelA to listOf(footballProgram("PSG - Marseille", now + 1_800, now + 5_400)),
            channelB to listOf(footballProgram("PSG vs Marseille", now + 1_860, now + 5_460)),
        )

        val row = engine.buildRow(programs, now)

        assertEquals(1, row?.items?.size)
    }

    @Test
    fun `different fixtures at the same time are kept separate`() {
        val channelA = channel(1, "beIN Sports")
        val channelB = channel(2, "Canal Foot")
        val programs = mapOf(
            channelA to listOf(footballProgram("PSG - Marseille", now + 1_800, now + 5_400)),
            channelB to listOf(footballProgram("Lyon - Nice", now + 1_800, now + 5_400)),
        )

        val row = engine.buildRow(programs, now)

        assertEquals(2, row?.items?.size)
    }

    @Test
    fun `live events always sort before upcoming events regardless of start time`() {
        val channel = channel(1, "beIN Sports")
        val programs = mapOf(
            channel to listOf(
                footballProgram("Lyon - Nice", now + 900, now + 4_500),
                footballProgram("PSG - Marseille", now - 300, now + 3_000),
            ),
        )

        val row = engine.buildRow(programs, now)

        assertEquals(MatchTemporalState.Live, row?.items?.first()?.temporalState)
        assertEquals("PSG", row?.items?.first()?.event?.participantA)
    }

    @Test
    fun `rows from multiple EPG days merge in chronological order`() {
        val channel = channel(1, "beIN Sports")
        val today = engine.buildRow(
            mapOf(channel to listOf(footballProgram("PSG - Marseille", now + 1_800, now + 5_400))),
            now,
        )!!
        val tomorrow = engine.buildRow(
            mapOf(channel to listOf(footballProgram("Lyon - Nice", now + 86_400, now + 90_000))),
            now,
        )!!

        val merged = engine.mergeRows(listOf(tomorrow, today))

        assertEquals(2, merged?.items?.size)
        assertEquals("PSG", merged?.items?.first()?.event?.participantA)
    }

    @Test
    fun `building the row twice from the same input is fully deterministic`() {
        val channel = channel(1, "beIN Sports")
        val programs = mapOf(
            channel to listOf(
                footballProgram("Lyon - Nice", now + 1_800, now + 5_400),
                footballProgram("PSG - Marseille", now + 900, now + 4_500),
            ),
        )

        val first = engine.buildRow(programs, now)
        val second = engine.buildRow(programs, now)

        assertEquals(first?.items?.map { it.event.fingerprint }, second?.items?.map { it.event.fingerprint })
    }

    @Test
    fun `magazine and highlights programmes never reach the row`() {
        val channel = channel(1, "beIN Sports")
        val programs = mapOf(
            channel to listOf(
                EpgProgram(
                    title = "Magazine Ligue 1",
                    description = "Retour sur PSG - Marseille.",
                    startEpochSeconds = now + 1_800,
                    endEpochSeconds = now + 5_400,
                    category = "Football",
                ),
                EpgProgram(
                    title = "Highlights: Arsenal vs Chelsea",
                    description = null,
                    startEpochSeconds = now + 7_200,
                    endEpochSeconds = now + 9_000,
                    category = "Football",
                ),
            ),
        )

        val row = engine.buildRow(programs, now)

        assertTrue(row == null || row.items.isEmpty())
    }

    private fun channel(id: Int, name: String, categoryId: String = "sport") = MediaEntry(
        id = id,
        name = name,
        displayName = name,
        type = MediaType.Live,
        categoryId = categoryId,
        iconUrl = null,
        number = id,
    )

    private fun footballProgram(title: String, start: Long, end: Long) = EpgProgram(
        title = title,
        description = "Match de football en direct.",
        startEpochSeconds = start,
        endEpochSeconds = end,
        category = "Football",
    )
}

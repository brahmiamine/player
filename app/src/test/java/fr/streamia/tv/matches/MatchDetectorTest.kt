package fr.streamia.tv.matches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchDetectorTest {
    private val detector = StructuredMatchDetector()

    @Test
    fun `structured match with competition and category is accepted`() {
        val result = detector.detect(
            title = "PSG - Marseille",
            description = "Championnat de France de football.",
            category = "Football",
        )

        assertTrue(result.isMatch)
        assertEquals(MatchSport.Football, result.sport)
        assertEquals("PSG", result.participantA)
        assertEquals("Marseille", result.participantB)
        assertTrue(result.confidence >= 0.55)
    }

    @Test
    fun `sports magazine mentioning two teams is rejected`() {
        val result = detector.detect(
            title = "Magazine Ligue 1",
            description = "Retour sur PSG - Marseille et les résultats du week-end.",
            category = "Football",
        )

        assertFalse(result.isMatch)
        assertTrue("MAGAZINE_OR_NEWS" in result.negativeSignals)
    }

    @Test
    fun `highlights programme is rejected even with versus pattern`() {
        val result = detector.detect(
            title = "Highlights: Arsenal vs Chelsea",
            description = null,
            category = "Football",
        )

        assertFalse(result.isMatch)
        assertTrue("HIGHLIGHTS_OR_SUMMARY" in result.negativeSignals)
    }

    @Test
    fun `replay is rejected outright regardless of other signals`() {
        val result = detector.detect(
            title = "Replay: Real Madrid vs Barcelona",
            description = "Ligue des champions, match en différé.",
            category = "Football",
        )

        assertFalse(result.isMatch)
        assertEquals(listOf("REPLAY"), result.negativeSignals)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `unrelated movie title with a hyphen is not treated as a match`() {
        val result = detector.detect(
            title = "Voyage - Une histoire de famille",
            description = "Un drame familial sur fond de road trip.",
            category = "Films",
        )

        assertFalse(result.isMatch)
        assertNull(result.sport)
    }

    @Test
    fun `english premier league fixture with vs separator is accepted`() {
        val result = detector.detect(
            title = "Arsenal vs Chelsea",
            description = "Premier League matchday clash.",
            category = "Sport",
        )

        assertTrue(result.isMatch)
        assertEquals(MatchSport.Football, result.sport)
    }

    @Test
    fun `english highlights programme is rejected`() {
        val result = detector.detect(
            title = "Premier League Highlights",
            description = "Weekly recap of the best goals.",
            category = "Football",
        )

        assertFalse(result.isMatch)
    }

    @Test
    fun `arabic fixture with dedicated separator is accepted`() {
        val result = detector.detect(
            title = "الهلال ضد النصر",
            description = "مباراة مباشرة في الدوري",
            category = "كرة القدم",
        )

        assertTrue(result.isMatch)
        assertEquals(MatchSport.Football, result.sport)
        assertEquals("الهلال", result.participantA)
        assertEquals("النصر", result.participantB)
    }

    @Test
    fun `arabic summary programme is rejected`() {
        val result = detector.detect(
            title = "ملخص الدوري",
            description = "أبرز أهداف الجولة",
            category = "كرة القدم",
        )

        assertFalse(result.isMatch)
    }

    @Test
    fun `spanish fixture with vs separator is accepted`() {
        val result = detector.detect(
            title = "Madrid vs Barcelona",
            description = "Partido de La Liga en vivo.",
            category = "Futbol",
        )

        assertTrue(result.isMatch)
    }

    @Test
    fun `spanish summary programme is rejected`() {
        val result = detector.detect(
            title = "Resumen de La Liga",
            description = "Los mejores goles de la jornada.",
            category = "Futbol",
        )

        assertFalse(result.isMatch)
    }

    @Test
    fun `italian fixture with hyphen and confirmed sport is accepted`() {
        val result = detector.detect(
            title = "Milan - Juventus",
            description = "Serie A, partita in diretta.",
            category = "Calcio",
        )

        assertTrue(result.isMatch)
    }

    @Test
    fun `italian highlights programme is rejected`() {
        val result = detector.detect(
            title = "Highlights Serie A",
            description = "Sintesi della giornata.",
            category = "Calcio",
        )

        assertFalse(result.isMatch)
    }

    @Test
    fun `german fixture with gegen separator is accepted`() {
        val result = detector.detect(
            title = "Bayern gegen Dortmund",
            description = "Bundesliga live.",
            category = "Fussball",
        )

        assertTrue(result.isMatch)
    }

    @Test
    fun `german magazine programme is rejected`() {
        val result = detector.detect(
            title = "Bundesliga Magazin",
            description = "Aktuelle Nachrichten aus der Liga.",
            category = "Fussball",
        )

        assertFalse(result.isMatch)
    }

    @Test
    fun `combat sport fixture is accepted`() {
        val result = detector.detect(
            title = "Fighter A vs Fighter B",
            description = "UFC main card, live.",
            category = "UFC",
        )

        assertTrue(result.isMatch)
        assertEquals(MatchSport.Combat, result.sport)
    }

    @Test
    fun `versus pattern without any sport signal is not enough on its own`() {
        val result = detector.detect(
            title = "Team Blue vs Team Red",
            description = "Un jeu télévisé familial.",
            category = "Divertissement",
        )

        assertFalse(result.isMatch)
        assertNull(result.sport)
    }
}

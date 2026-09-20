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
    fun `slash fixture on generic sports channel is accepted from real XMLTV shape`() {
        val result = detector.detect(
            title = "Fenerbahçe / Beşiktaş",
            description = "beIN SPORTS, le plus grand des spectacles",
            category = null,
            channelName = "VIP: BEIN SPORTS MAX 7 HD",
        )

        assertTrue(result.isMatch)
        assertEquals(MatchSport.Other, result.sport)
        assertEquals("Fenerbahçe", result.participantA)
        assertEquals("Beşiktaş", result.participantB)
        assertTrue("GENERIC_SPORT_CHANNEL" in result.signals)
    }

    @Test
    fun `multi word slash fixture is accepted on sports channel`() {
        val result = detector.detect(
            title = "Preston North End / Blackburn Rovers",
            description = null,
            category = null,
            channelName = "beIN Sports MAX 10",
        )

        assertTrue(result.isMatch)
        assertEquals("Preston North End", result.participantA)
        assertEquals("Blackburn Rovers", result.participantB)
    }

    @Test
    fun `slash title on non sports channel is still rejected`() {
        val result = detector.detect(
            title = "Alice / Bob",
            description = "Portrait croisé de deux artistes.",
            category = "Culture",
            channelName = "Culture TV",
        )

        assertFalse(result.isMatch)
        assertNull(result.sport)
    }

    @Test
    fun `youth team tokens with digits are accepted`() {
        val result = detector.detect(
            title = "PSG U19 / Dortmund U19",
            description = "UEFA Youth League football",
            category = "Football",
            channelName = "Canal+ Foot",
        )

        assertTrue(result.isMatch)
        assertEquals("PSG U19", result.participantA)
        assertEquals("Dortmund U19", result.participantB)
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

    @Test
    fun `programme vocabulary alone never becomes a match on a sports channel`() {
        // Cas réellement produits : le seul motif d'affrontement (0.42) plus le contexte générique du
        // nom de chaîne (0.16) atteignaient le seuil de confiance sans aucune preuve sportive.
        val titles = listOf(
            "Film - Action",
            "Documentaire - Histoire",
            "Serie - Saison 3",
            "Musique - Concert",
            "Multiplex - Ligue 1",
            "Documentaire. - Histoire",
            "Cinéma - Action",
            "Série - Saison 2",
            "Météo - Trophée",
        )

        titles.forEach { title ->
            val result = detector.detect(
                title = title,
                description = null,
                category = null,
                channelName = "beIN SPORTS 1",
            )

            assertFalse("« $title » ne doit pas être détecté comme un match", result.isMatch)
        }
    }

    @Test
    fun `a real competition prefix is not mistaken for programme vocabulary`() {
        // « Serie » appartient au vocabulaire de programme, mais « Serie A » est une compétition : un
        // côté n'est rejeté que s'il ne contient aucun mot porteur de sens.
        val result = detector.detect(
            title = "Serie A - Juventus vs Milan",
            description = "Calcio, partita in diretta.",
            category = null,
            channelName = "beIN SPORTS 1",
        )

        assertTrue(result.isMatch)
    }

    @Test
    fun `club names alone detect a fixture on a channel with no sport keyword`() {
        // Cas réel de la grille du fournisseur : titre en « Club / Club », aucune catégorie EPG
        // exploitable, et une chaîne dont le nom ne dit rien de sport. Le nom de club est la seule
        // preuve disponible — sans lui, ce match en direct était invisible sur l'accueil.
        val result = detector.detect(
            title = "Fulham / Manchester United",
            description = null,
            category = null,
            channelName = "FR: CANAL+ Family 4K",
        )

        assertTrue(result.isMatch)
        assertEquals("Fulham", result.participantA)
        assertEquals("Manchester United", result.participantB)
        assertTrue("CLUB_NAME_MARKER" in result.signals)
    }

    @Test
    fun `ambiguous club initials need a second word to count`() {
        val result = detector.detect(
            title = "AC / DC",
            description = null,
            category = null,
            channelName = "FR: CANAL+ Family 4K",
        )

        assertFalse(result.isMatch)
    }

    @Test
    fun `mightBeMatch short circuits titles with no separator`() {
        assertFalse(detector.mightBeMatch("Journal de 20h"))
        assertFalse(detector.mightBeMatch("Un si grand soleil"))
        assertTrue(detector.mightBeMatch("PSG - Marseille"))
        assertTrue(detector.mightBeMatch("Arsenal vs Chelsea"))
        assertTrue(detector.mightBeMatch("Fenerbahçe / Beşiktaş"))
        assertTrue(detector.mightBeMatch("Bayern gegen Dortmund"))
    }
}

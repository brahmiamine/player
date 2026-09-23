package fr.streamia.tv.recommendation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentCandidateIndexTest {
    private fun movie(id: Int, title: String, plot: String? = null, genre: String? = null, cast: String? = null, director: String? = null) =
        IndexedContent("Movie:$id", title, plot, genre, cast, director)

    // Remplissage réaliste : sans lui, chaque mot serait « rare » et l'IDF ne départagerait rien.
    private val filler = (100 until 160).map { movie(it, "Film $it", "Une famille part en vacances au bord de la mer et découvre la ville.", "Comédie") }

    @Test
    fun rareStoryWordsAndGenreBeatSharedCommonWords() {
        val index = ContentCandidateIndex(
            filler + listOf(
                movie(1, "Nuit Rouge", "Des vampires assoiffés de sang attaquent une petite ville isolée.", "Horreur"),
                movie(2, "Crocs", "Un vampire solitaire chasse dans les rues la nuit.", "Horreur, Thriller"),
                movie(3, "Vacances", "Une famille part en vacances dans une petite ville.", "Comédie"),
            ),
        )
        val top = index.topMatches(movie(1, "Nuit Rouge", "Des vampires assoiffés de sang attaquent une petite ville isolée.", "Horreur"), 5)
        assertEquals("Movie:2", top.first())
        assertFalse("Movie:1" in top)
    }

    @Test
    fun sameDirectorAndSagaAreFound() {
        val index = ContentCandidateIndex(
            filler + listOf(
                movie(10, "Inception", director = "Christopher Nolan", genre = "Science-Fiction"),
                movie(11, "Interstellar", director = "Christopher Nolan", genre = "Drame"),
                movie(20, "Harry Potter à l'école des sorciers", genre = "Fantastique"),
                movie(21, "Harry Potter et la Chambre des secrets", genre = "Fantastique"),
            ),
        )
        assertTrue("Movie:11" in index.topMatches(movie(10, "Inception", director = "Christopher Nolan", genre = "Science-Fiction"), 5))
        assertEquals("Movie:21", index.topMatches(movie(20, "Harry Potter à l'école des sorciers", genre = "Fantastique"), 5).first())
    }

    @Test
    fun onlySameTypeIsReturned() {
        val index = ContentCandidateIndex(
            filler + listOf(
                movie(30, "Zombieland", "Des zombies envahissent le pays.", "Horreur"),
                IndexedContent("Series:31", "The Walking Dead", "Des zombies envahissent le pays.", "Horreur", null, null),
            ),
        )
        assertTrue(index.topMatches(movie(30, "Zombieland", "Des zombies envahissent le pays.", "Horreur"), 5).none { it.startsWith("Series:") })
    }

    @Test
    fun sagaAndMovieLensNeighboursAreBoosted() {
        val hp = listOf("Q216930" to "Harry Potter")
        val index = ContentCandidateIndex(
            filler + listOf(
                IndexedContent("Movie:40", "HP 1", null, null, null, null, tmdbId = "671", sagas = hp),
                IndexedContent("Movie:41", "HP 2", null, null, null, null, tmdbId = "672", sagas = hp),
                IndexedContent("Movie:42", "Le Seigneur des anneaux", null, null, null, null, tmdbId = "120"),
            ),
        )
        val boosts = index.related(IndexedContent("Movie:40", "HP 1", null, null, null, null, tmdbId = "671"), intArrayOf(672, 120, 999))
        assertEquals("Même saga : Harry Potter", boosts["Movie:41"]?.reason)
        assertEquals("Aimé par les mêmes spectateurs", boosts["Movie:42"]?.reason)
        assertFalse("Movie:40" in boosts)
        assertTrue(boosts.getValue("Movie:41").score > boosts.getValue("Movie:42").score)
    }
}

package fr.streamia.tv.recommendation

import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSimilarityTest {
    private val engine = MetadataSimilarityEngine()

    @Test
    fun `related plots score above unrelated plots`() {
        val source = features(
            1,
            "Mission Orion",
            "Un équipage d'astronautes part en mission dans l'espace pour sauver la Terre.",
            genre = "Science-fiction, Drame",
        )
        val related = features(
            2,
            "Dernière orbite",
            "Des astronautes en mission dans l'espace tentent de sauver leur équipage et la Terre.",
            genre = "Science-fiction",
        )
        val unrelated = features(
            3,
            "Cuisine d'été",
            "Une famille ouvre un restaurant de cuisine méditerranéenne au bord de la mer.",
            genre = "Comédie",
        )

        val relatedScore = engine.compare(source, related).score
        val unrelatedScore = engine.compare(source, unrelated).score

        assertTrue(relatedScore > unrelatedScore)
        assertTrue(relatedScore >= 0.35)
    }

    @Test
    fun `IPTV quality tags and year never create fake similarity`() {
        val source = features(
            1,
            "Valiant One (MULTI) FHD 2025",
            "Après un crash en Corée du Nord, des soldats américains doivent survivre en territoire ennemi.",
            genre = "Guerre, Thriller, Action",
        )
        val unrelated = features(
            2,
            "The Diary (MULTI) FHD 2025",
            "Une jeune femme retrouve le journal intime de sa mère et découvre une histoire d'amour familiale.",
            genre = "Drame, Romance",
        )

        val result = engine.compare(source, unrelated)

        assertEquals(0.0, result.score, 0.0001)
        assertFalse(result.substantive)
    }

    @Test
    fun `matching genres keep military action ahead of unrelated catalog items`() {
        val source = features(
            1,
            "Valiant One (MULTI) FHD 2025",
            "Des soldats américains survivent derrière les lignes ennemies après un crash.",
            genre = "Guerre, Thriller, Action",
        )
        val military = features(
            2,
            "Behind Enemy Lines",
            "Un pilote militaire doit survivre en territoire ennemi pendant une mission de sauvetage.",
            genre = "Action, War, Thriller",
        )
        val drama = features(
            3,
            "Family Diary",
            "Une famille se réunit pour préparer un mariage et affronter ses souvenirs.",
            genre = "Drama, Romance",
        )

        val militaryScore = engine.compare(source, military)
        val dramaScore = engine.compare(source, drama)

        assertTrue(militaryScore.substantive)
        assertTrue(militaryScore.score > dramaScore.score)
        assertTrue(militaryScore.score >= 0.30)
    }

    @Test
    fun `same director produces an explainable reason`() {
        val source = features(1, "Film A", "Mystère dans une ville.", director = "Denis Villeneuve")
        val candidate = features(2, "Film B", "Une enquête dans le désert.", director = "Denis Villeneuve")

        assertEquals("Même réalisateur", engine.compare(source, candidate).reason)
    }

    @Test
    fun `semantic provider is preferred when available but metadata still contributes`() {
        val provider = object : SemanticSimilarityProvider {
            override val id = "test"
            override fun similarity(sourceText: String, candidateText: String) = 0.9
        }
        val hybrid = MetadataSimilarityEngine(provider)
        val result = hybrid.compare(
            features(1, "A", "Voyage spatial", genre = "Science-fiction"),
            features(2, "B", "Space journey", genre = "Science-fiction"),
        )

        assertTrue(result.semanticUsed)
        assertTrue(result.score >= 0.75)
    }

    private fun features(
        id: Int,
        name: String,
        plot: String,
        genre: String? = null,
        director: String? = null,
    ) = ContentFeatures(
        entry = MediaEntry(
            id = id,
            name = name,
            displayName = name,
            type = MediaType.Movie,
            categoryId = "movies",
            iconUrl = null,
            number = id,
        ),
        plot = plot,
        genre = genre,
        director = director,
    )
}

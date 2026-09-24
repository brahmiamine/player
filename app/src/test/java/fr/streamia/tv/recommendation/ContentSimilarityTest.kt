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

    @Test
    fun `same broad genre without related plot is rejected`() {
        val source = features(
            10,
            "Front Line",
            "Des soldats isolés derrière les lignes ennemies organisent une mission de sauvetage.",
            genre = "Action, Thriller",
        )
        val unrelated = features(
            11,
            "Summer Kitchen",
            "Une cheffe ouvre un restaurant familial et tombe amoureuse au bord de la mer.",
            genre = "Action, Thriller",
        )

        val result = engine.compare(source, unrelated)

        assertFalse(result.substantive)
        assertEquals(0.0, result.score, 0.0001)
    }

    @Test
    fun `provider prefixes never make two titles look like a saga`() {
        val slave = features(20, "RO - 12 Years a Slave", "A free man is abducted and sold into slavery.", genre = "Drama, History")
        val gun = features(21, "RO - 12 Round Gun", "A boxer fights a final bout against his demons.", genre = "Action")
        val bride = features(22, "4K-TOP - The Bride (2026)", "A monster bride is brought back to life.", genre = "Horror")
        val warfare = features(23, "4K-TOP - Warfare (2025)", "Navy SEALs are trapped during an Iraq mission.", genre = "War")

        assertFalse(engine.compare(slave, gun).substantive)
        assertFalse(engine.compare(bride, warfare).substantive)
        assertEquals(setOf("12", "years", "slave"), engine.titleTokens("EN-TOP -183.12.Years.A.Slave.2013"))
        assertEquals(setOf("blame"), engine.titleTokens("AR-SUBS - The Blame (2026) (GB)"))
    }

    @Test
    fun `same title under another prefix is the same content, not a recommendation`() {
        val ro = features(30, "RO - 12 Years a Slave", "plot", genre = "Drama")
        val fr = features(31, "FR - 12 Years a Slave (2013)", "plot", genre = "Drama")
        val remake = features(32, "FR - Michael (1990)", "plot").copy(releaseDate = "1990")
        val michael = features(33, "4K-TOP - Michael (2026)", "plot")

        assertTrue(likelySameContent(ro, fr))
        assertFalse(likelySameContent(michael, remake))
    }

    @Test
    fun `shared TMDB keywords link stories even without common plot words`() {
        val chernobyl = features(40, "4K-AR - Chernobyl (2019) (US)", "A reactor explodes in 1986.", genre = "Drama")
            .copy(keywords = listOf("nuclear catastrophe", "based on true story", "miniseries", "disaster"))
        val theDays = features(41, "EN - The Days (2023)", "Workers fight to contain a meltdown at a power plant.", genre = "Drama")
            .copy(keywords = listOf("nuclear catastrophe", "disaster", "miniseries"))
        val family = features(42, "AR - Family Saga", "A family drama in old Damascus.", genre = "Drama")
            .copy(keywords = listOf("miniseries", "woman director"))

        val related = engine.compare(chernobyl, theDays)
        assertTrue(related.substantive)
        assertEquals("Thèmes : nuclear catastrophe, disaster", related.reason)
        // « miniseries » est une étiquette générique : ne rapproche rien à elle seule.
        assertFalse(engine.compare(chernobyl, family).substantive)
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

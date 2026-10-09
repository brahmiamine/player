package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAssistantTest {
    @Test
    fun rankingKeepsValidUniqueNumbersAndAppendsMissingOnes() {
        assertEquals(listOf(2, 0, 1, 3), parseRanking("[3, 1, 3, 2, 9]", 4))
        assertEquals(listOf(2, 0, 1), parseRanking("[3,1]", 3))
    }

    @Test
    fun rankingRejectsAnswerWithTooFewNumbers() {
        assertNull(parseRanking("je ne sais pas", 6))
        assertNull(parseRanking("[1]", 6))
    }

    @Test
    fun titleLinesMapBackToOriginalAndIgnoreUnplausibleAnswers() {
        val batch = listOf("FR| Inception (2010) HD", "Heat", "EN - The Matrix [MULTI]")
        val cleaned = parseTitleLines("1|Inception\n2|Heat\n3|The Matrix\n9|Inconnu\nbruit", batch)
        assertEquals(mapOf("FR| Inception (2010) HD" to "Inception", "EN - The Matrix [MULTI]" to "The Matrix"), cleaned)
    }

    @Test
    fun titleCleanupOnlyFlagsNoisyTitles() {
        assertTrue(TitleCleanup.needsCleaning("FR| Inception (2010)"))
        assertTrue(TitleCleanup.needsCleaning("Dune 4K MULTI"))
        assertFalse(TitleCleanup.needsCleaning("Heat"))
        assertFalse(TitleCleanup.needsCleaning("Le Parrain, partie II"))
    }

    @Test
    fun languageGuessSkipsTextAlreadyInTargetLanguage() {
        val english = "A retired detective is pulled back into the city that he left, and the case is as personal as it is dangerous for his family."
        val french = "Un détective à la retraite est ramené dans la ville qu'il a quittée, et l'affaire est aussi personnelle que dangereuse pour sa famille."
        assertTrue(LanguageGuess.isLikely(english, "en"))
        assertFalse(LanguageGuess.isLikely(english, "fr"))
        assertTrue(LanguageGuess.isLikely(french, "fr"))
        assertFalse(LanguageGuess.isLikely(french, "en"))
    }
}

class AiModelInfoTest {
    @Test
    fun openRouterPricesPerTokenBecomePricePerMillion() {
        val body = """{"data":[{"id":"a/b","context_length":128000,"pricing":{"prompt":"0.0000009","completion":"0.000002"},"top_provider":{"max_completion_tokens":16000}}]}"""
        val info = parseModelInfos(body)["a/b"]!!
        assertEquals(128_000L, info.contextTokens)
        assertEquals(16_000L, info.maxOutputTokens)
        assertEquals(0.9, info.inputPricePerMillion!!, 1e-9)
        assertEquals(2.0, info.outputPricePerMillion!!, 1e-9)
    }

    @Test
    fun modelsWithoutDetailsAreSkipped() {
        assertTrue(parseModelInfos("""{"data":[{"id":"x"}]}""").isEmpty())
        assertTrue(parseModelInfos("pas du json").isEmpty())
    }
}

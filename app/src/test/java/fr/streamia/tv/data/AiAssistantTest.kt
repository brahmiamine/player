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

class AiSubtitlesTest {
    private val srt = "1\n00:00:01,000 --> 00:00:03,000\nHello there.\nHow are you?\n\n2\n00:00:04,000 --> 00:00:05,500\n<i>Fine.</i>\n"

    @Test
    fun cuesKeepTimingAndDropNumbersAndHeaders() {
        val cues = parseCues("WEBVTT\n\n" + srt)
        assertEquals(2, cues.size)
        assertEquals("00:00:01,000 --> 00:00:03,000", cues[0].timing)
        assertEquals(listOf("Hello there.", "How are you?"), cues[0].lines)
    }

    @Test
    fun renderedSrtCanBeParsedAgain() {
        val cues = parseCues(srt)
        assertEquals(cues, parseCues(renderCues(cues, vtt = false)))
        assertTrue(renderCues(cues, vtt = true).startsWith("WEBVTT"))
    }

    @Test
    fun cuesArePackedInFewLargeBatchesWithGlobalNumbers() {
        val cues = List(10) { SubtitleCue("t", listOf("ligne $it")) }
        val batches = packCues(cues, maxChars = 10_000, maxCues = 4)
        assertEquals(listOf(4, 4, 2), batches.map { it.size })
        assertEquals(1, batches.first().first().first)
        assertEquals(10, batches.last().last().first)
    }

    @Test
    fun translatedLinesAreSplitBackIntoSubtitleLines() {
        val lines = parseTranslatedLines("1|Bonjour. // Comment ça va ?\n2|<i>Bien.</i>\nbruit")
        assertEquals(listOf("Bonjour.", "Comment ça va ?"), lines[1])
        assertEquals(listOf("<i>Bien.</i>"), lines[2])
        assertEquals(2, lines.size)
    }
}

class AiCompatTest {
    @Test
    fun openCodeRoutesGptGrokAndMuseModelsToResponsesFirst() {
        assertEquals(AiFormat.Responses, AiCompat.formatOrder(AiProvider.OpenCode, "muse-spark-1.2-contributor").first())
        assertEquals(AiFormat.Responses, AiCompat.formatOrder(AiProvider.OpenCodeZen, "gpt-5.5").first())
        assertEquals(AiFormat.Messages, AiCompat.formatOrder(AiProvider.OpenCodeZen, "claude-sonnet-5-5").first())
        assertEquals(AiFormat.Chat, AiCompat.formatOrder(AiProvider.OpenCode, "deepseek-v4.1-flash").first())
    }

    @Test
    fun directProvidersKeepASingleFormat() {
        assertEquals(listOf(AiFormat.Messages), AiCompat.formatOrder(AiProvider.Claude, "claude-haiku-5-5"))
        assertEquals(listOf(AiFormat.Chat), AiCompat.formatOrder(AiProvider.OpenRouter, "a/b"))
    }

    @Test
    fun openAiNeverTriesMaxTokens() {
        assertEquals(listOf(1, 2), AiCompat.chatVariants(AiProvider.ChatGpt, "gpt-5"))
        assertEquals(listOf(0, 1, 2), AiCompat.chatVariants(AiProvider.OpenCode, "deepseek-v4.1-flash"))
    }
}

class AiChatRetryTest {
    @Test
    fun sessionAndAuthErrorsAreNotRetriedOnOtherFormats() {
        assertFalse(AiChatClient.worthAnotherFormat(AiCallException("400 : Request is missing x-opencode-session and cannot be routed", 400)))
        assertFalse(AiChatClient.worthAnotherFormat(AiCallException("401 : invalid api key", 401)))
        assertFalse(AiChatClient.worthAnotherFormat(AiCallException("429 : rate limited", 429)))
    }

    @Test
    fun wrongEndpointOrParameterTriesTheNextFormat() {
        assertTrue(AiChatClient.worthAnotherFormat(AiCallException("404 : not found", 404)))
        assertTrue(AiChatClient.worthAnotherFormat(AiCallException("400 : Unsupported parameter: 'max_tokens'", 400)))
        assertTrue(AiChatClient.worthAnotherFormat(AiCallException("400 : model is not supported on chat/completions", 400)))
    }
}

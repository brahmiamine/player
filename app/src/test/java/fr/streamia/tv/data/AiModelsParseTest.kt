package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AiModelsParseTest {
    @Test
    fun `reads data array sorted and deduplicated`() {
        assertEquals(listOf("a", "b"), parseModelIds("""{"data":[{"id":"b"},{"id":"a"},{"id":"a"}]}"""))
    }

    @Test
    fun `reads a top-level array like Together`() {
        assertEquals(listOf("m1", "m2"), parseModelIds("""[{"id":"m2"},{"id":"m1"}]"""))
    }

    @Test
    fun `strips the Gemini models prefix`() {
        assertEquals(listOf("gemini-2.5-flash"), parseModelIds("""{"data":[{"id":"models/gemini-2.5-flash"}]}"""))
    }

    @Test
    fun `unknown shape gives an empty list`() {
        assertEquals(emptyList<String>(), parseModelIds("""{"error":"x"}"""))
    }
}

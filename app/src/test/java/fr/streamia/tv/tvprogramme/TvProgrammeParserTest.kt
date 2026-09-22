package fr.streamia.tv.tvprogramme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvProgrammeParserTest {
    @Test
    fun parsesSemanticProgrammeImagesWithoutDependingOnCssClasses() {
        val html = """
            <html><body>
              <div class="whatever">
                <img data-src="//img.tv-programme.com/example/koh-lanta.webp"
                     alt="Sur TF1 à 21h10 : Koh-Lanta">
                <img src="/images/programmes/cercles.webp"
                     alt="Sur France 2 à 21h05 : Les cercles du silence">
              </div>
            </body></html>
        """.trimIndent()

        val result = TvProgrammeParser.parse(html)

        assertEquals(2, result.size)
        assertEquals("TF1", result[0].channelName)
        assertEquals("21:10", result[0].time)
        assertEquals("Koh-Lanta", result[0].title)
        assertEquals("https://img.tv-programme.com/example/koh-lanta.webp", result[0].imageUrl)
        assertEquals("https://tv-programme.com/images/programmes/cercles.webp", result[1].imageUrl)
    }

    @Test
    fun ignoresUnrelatedImagesAndDeduplicatesIdenticalProgramme() {
        val html = """
            <img src="/logo.png" alt="Logo TF1">
            <img src="/p.webp" alt="Sur M6 à 21h10 : Capital">
            <img src="/p.webp" alt="Sur M6 à 21h10 : Capital">
        """.trimIndent()

        val result = TvProgrammeParser.parse(html)

        assertEquals(1, result.size)
        assertTrue(result.single().title == "Capital")
    }
}

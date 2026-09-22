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
    fun parsesHomeChannelLinesWithFullChannelNamesAndUnescapedTitles() {
        val html = """
            <html><body>
              <article class="tvp-home-channel-line"><div class="tvp-home-line-grid">
                <aside><div class="tvp-home-channel-sticky">
                  <div class="tvp-home-channel-card"><a href="/la-chaine-parlementaire">
                    <img class="logoChaine tvp-home-channel-logo" alt="Logo de la chaîne La Chaîne parlementaire" src="/lcp.svg">
                  </a></div>
                  <h2 class="tvp-home-channel-name"><a href="/lcp">La Chaîne…</a></h2>
                </div></aside>
                <div class="tvp-home-program-card"><div class="tvp-home-program-inner">
                  <div class="tvp-home-program-media">
                    <img class="tvp-home-program-image" alt="Sur La Chaîne parlementaire à 20h30 : Débat" src="https://img.tv-programme.com/photos/debat-250.webp">
                  </div>
                  <div class="tvp-home-program-body">
                    <div class="tvp-home-program-time"><time datetime="2026-09-22T20:30:00+02:00">20h30</time></div>
                    <h3 class="tvp-home-program-title"><a href="/debat">Débat</a></h3>
                  </div>
                </div></div>
              </div></article>
              <article class="tvp-home-channel-line"><div class="tvp-home-line-grid">
                <aside><div class="tvp-home-channel-sticky">
                  <img class="logoChaine tvp-home-channel-logo" alt="Logo de la chaîne L&amp;apos;Equipe" src="/equipe.svg">
                  <h2 class="tvp-home-channel-name"><a href="/lequipe">L&amp;apos;Equipe</a></h2>
                </div></aside>
                <div class="tvp-home-program-card"><div class="tvp-home-program-inner">
                  <div class="tvp-home-program-body">
                    <div class="tvp-home-program-time"><time datetime="2026-09-22T23:00:00+02:00">23h00</time></div>
                    <h3 class="tvp-home-program-title"><a href="/soir">L&amp;apos;Equipe du soir</a></h3>
                  </div>
                </div></div>
              </div></article>
            </body></html>
        """.trimIndent()

        val result = TvProgrammeParser.parse(html)

        assertEquals(2, result.size)
        assertEquals("La Chaîne parlementaire", result[0].channelName)
        assertEquals("20:30", result[0].time)
        assertEquals("Débat", result[0].title)
        assertEquals("https://img.tv-programme.com/photos/debat-250.webp", result[0].imageUrl)
        assertEquals("L'Equipe", result[1].channelName)
        assertEquals("23:00", result[1].time)
        assertEquals("L'Equipe du soir", result[1].title)
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

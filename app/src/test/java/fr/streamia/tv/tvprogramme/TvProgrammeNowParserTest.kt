package fr.streamia.tv.tvprogramme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TvProgrammeNowParserTest {
    @Test
    fun parsesCurrentProgrammeFromAbsoluteEpochBounds() {
        val html = """
            <html><body>
              <table><tbody>
                <tr class="tvp-grille-row" data-channel-direct-url="https://tv-programme.com/tf1/direct">
                  <th class="tvp-grille-channel-cell">
                    <a href="https://tv-programme.com/tf1" class="tvp-grille-channel-link" aria-label="Voir la chaîne TF1">
                      <span class="tvp-grille-channel-stack">
                        <img src="https://img.tv-programme.com/photos/tf1.svg" class="logoChaine tvp-grille-channel-logo" alt="Logo TF1">
                        <span class="tvp-grille-channel-number">N° 1</span>
                      </span>
                    </a>
                  </th>
                  <td class="tvp-grille-track-cell"><div class="tvp-grille-track">
                    <article class="tvp-grille-item" data-starttime="1700000000" data-endtime="1700002400" data-tooltip-title="TFou">
                      <h2 class="tvp-grille-sr-only">TFou</h2>
                      <div class="tvp-grille-item-inner tvp-grille-item-inner--with-image">
                        <img class="tvp-grille-item-image" src="https://img.tv-programme.com/photos/tfou.webp" alt="Visuel de TFou">
                      </div>
                    </article>
                    <article class="tvp-grille-item" data-starttime="1700002400" data-endtime="1700003300" data-tooltip-title="Le journal">
                      <h2 class="tvp-grille-sr-only">Le journal</h2>
                      <div class="tvp-grille-item-inner tvp-grille-item-inner--with-image">
                        <img class="tvp-grille-item-image" data-src="//img.tv-programme.com/jt.webp" alt="Visuel de JT">
                      </div>
                    </article>
                    <article class="tvp-grille-item" data-starttime="1700003300" data-endtime="1700005400" data-tooltip-title="Le magazine">
                      <h2 class="tvp-grille-sr-only">Le magazine</h2>
                      <div class="tvp-grille-item-inner tvp-grille-item-inner--text-only">
                        <a class="tvp-grille-item-link" href="/magazine">Le magazine</a>
                      </div>
                    </article>
                  </div></td>
                </tr>
              </tbody></table>
            </body></html>
        """.trimIndent()

        // now = 1700002400 + 420s (7 min into the 15 min "Le journal" slot).
        val nowEpochMillis = 1_700_002_820_000L
        val result = TvProgrammeNowParser.parse(html, nowEpochMillis)

        assertEquals(1, result.size)
        val programme = result.single()
        assertEquals("TF1", programme.channelName)
        assertEquals(1_700_002_400_000L, programme.startEpochMillis)
        assertEquals(1_700_003_300_000L, programme.endEpochMillis)
        assertEquals("Le journal", programme.title)
        assertEquals("https://img.tv-programme.com/jt.webp", programme.imageUrl)

        val progress = programme.progressAt(nowEpochMillis)
        assertNotNull(progress)
        assertEquals(420f / 900f, progress!!, 0.001f)
    }

    @Test
    fun fallsBackToLogoAltAndLinkTextAcrossMidnight() {
        val html = """
            <html><body>
              <table><tbody>
                <tr class="tvp-grille-row">
                  <th class="tvp-grille-channel-cell">
                    <a href="https://tv-programme.com/france-2" class="tvp-grille-channel-link">
                      <span class="tvp-grille-channel-stack">
                        <img src="https://img.tv-programme.com/photos/france2.svg" class="logoChaine tvp-grille-channel-logo" alt="Logo France 2">
                        <span class="tvp-grille-channel-number">N° 2</span>
                      </span>
                    </a>
                  </th>
                  <td class="tvp-grille-track-cell"><div class="tvp-grille-track">
                    <article class="tvp-grille-item" data-starttime="1700085000" data-endtime="1700086800" data-tooltip-title="Film de nuit">
                      <div class="tvp-grille-item-inner tvp-grille-item-inner--text-only">
                        <a class="tvp-grille-item-link" href="/film">Film de nuit</a>
                      </div>
                    </article>
                    <article class="tvp-grille-item" data-starttime="1700086800" data-endtime="1700089200" data-tooltip-title="Journal de nuit">
                      <div class="tvp-grille-item-inner tvp-grille-item-inner--text-only">
                        <a class="tvp-grille-item-link" href="/news">Journal de nuit</a>
                      </div>
                    </article>
                  </div></td>
                </tr>
              </tbody></table>
            </body></html>
        """.trimIndent()

        // Programme crosses midnight (23:50-00:20 local): 900s into the 1800s slot -> 50%.
        val nowEpochMillis = 1_700_085_900_000L
        val programme = TvProgrammeNowParser.parse(html, nowEpochMillis).single()

        assertEquals("France 2", programme.channelName)
        assertEquals("Film de nuit", programme.title)
        assertEquals(0.5f, programme.progressAt(nowEpochMillis)!!, 0.001f)
    }

    @Test
    fun skipsChannelsWithNoProgrammeOnAirRightNow() {
        val html = """
            <html><body>
              <table><tbody>
                <tr class="tvp-grille-row">
                  <th class="tvp-grille-channel-cell">
                    <a href="https://tv-programme.com/tf1" class="tvp-grille-channel-link" aria-label="Voir la chaîne TF1">
                      <span class="tvp-grille-channel-stack">
                        <img src="https://img.tv-programme.com/photos/tf1.svg" class="logoChaine tvp-grille-channel-logo" alt="Logo TF1">
                      </span>
                    </a>
                  </th>
                  <td class="tvp-grille-track-cell"><div class="tvp-grille-track">
                    <article class="tvp-grille-item" data-starttime="1700000000" data-endtime="1700002400" data-tooltip-title="TFou">
                      <h2 class="tvp-grille-sr-only">TFou</h2>
                    </article>
                  </div></td>
                </tr>
              </tbody></table>
            </body></html>
        """.trimIndent()

        val result = TvProgrammeNowParser.parse(html, nowEpochMillis = 1_700_009_999_000L)
        assertEquals(0, result.size)
    }
}

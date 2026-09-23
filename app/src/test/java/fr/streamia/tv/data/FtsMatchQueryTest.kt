package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsMatchQueryTest {
    @Test
    fun `words become prefix terms and FTS syntax is stripped`() {
        assertEquals("tf1* hd*", ftsMatchQuery("TF1 HD"))
        assertEquals("beinsports* 1*", ftsMatchQuery("  beinsports-1 "))
        // Mis en minuscules, "OR" n'est plus un opérateur FTS : juste un terme de plus.
        assertEquals("télé* 2* or*", ftsMatchQuery("Télé \"2\" OR*"))
        assertNull(ftsMatchQuery("  *\"- "))
    }
}

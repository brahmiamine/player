package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XtreamUrlBuilderTest {
    @Test
    fun `normalizes trailing slashes and encodes query credentials`() {
        val builder = XtreamUrlBuilder(
            ServerCredentials("https://demo.example.com///", "amine tv", "p@ss&word"),
        )

        assertEquals(
            "https://demo.example.com/player_api.php?username=amine%20tv&password=p%40ss%26word&action=get_live_categories",
            builder.api("get_live_categories"),
        )
    }

    @Test
    fun `builds a transport stream url with safe path segments`() {
        val builder = XtreamUrlBuilder(
            ServerCredentials("http://provider.test:8080", "a/b", "secret value"),
        )

        assertEquals(
            "http://provider.test:8080/live/a%2Fb/secret%20value/42.ts",
            builder.liveStream(42),
        )
    }

    @Test
    fun `rejects unsupported or incomplete server urls`() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamUrlBuilder(ServerCredentials("ftp://provider.test", "user", "pass"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            XtreamUrlBuilder(ServerCredentials("provider.test", "user", "pass"))
        }
    }
}

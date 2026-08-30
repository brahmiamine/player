package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `adds https when the provider omits the scheme in login`() {
        val builder = XtreamUrlBuilder(ServerCredentials("provider.test:443", "user", "pass"))

        assertEquals(
            "https://provider.test:443/live/user/pass/7.ts",
            builder.liveStream(7),
        )
    }

    @Test
    fun `switches between HTTP and HTTPS while preserving the complete url`() {
        assertEquals(
            "https://provider.test:443/live/u/p/7.ts?token=a%20b",
            XtreamUrlBuilder.alternateTransportUrl("http://provider.test:443/live/u/p/7.ts?token=a%20b"),
        )
        assertEquals(
            "http://provider.test:8443/player_api.php?username=u&password=p",
            XtreamUrlBuilder.alternateTransportUrl("https://provider.test:8443/player_api.php?username=u&password=p"),
        )
        assertNull(XtreamUrlBuilder.alternateTransportUrl("ftp://provider.test/file"))
    }

    @Test
    fun `builds movie and series episode urls with their extensions`() {
        val builder = XtreamUrlBuilder(ServerCredentials("https://provider.test", "user", "pass"))

        assertEquals(
            "https://provider.test/movie/user/pass/10.mkv",
            builder.stream(MediaType.Movie, 10, "mkv"),
        )
        assertEquals(
            "https://provider.test/series/user/pass/11.mp4",
            builder.stream(MediaType.Series, 11, "mp4"),
        )
    }

    @Test
    fun `rejects unsupported or incomplete server urls`() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamUrlBuilder(ServerCredentials("ftp://provider.test", "user", "pass"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            XtreamUrlBuilder(ServerCredentials("https://", "user", "pass"))
        }
    }
}

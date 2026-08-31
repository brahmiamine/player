package fr.streamia.tv.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterSanitizerTest {
    @Test
    fun `stream URL hides host username password and query`() {
        val safe = sanitizeStreamUrl(
            "http://provider.example:443/live/demo-user/demo-password/53863.ts?token=secret",
        )

        assertEquals("http://provider:443/live/***/***/53863.ts", safe)
        assertFalse(safe.contains("provider.example"))
        assertFalse(safe.contains("demo-user"))
        assertFalse(safe.contains("demo-password"))
        assertFalse(safe.contains("secret"))
    }

    @Test
    fun `free text redacts Xtream credentials and auth parameters`() {
        val safe = sanitizeFreeText(
            "GET /series/alice/password123/900.ts?username=alice&password=password123&token=abc",
        )

        assertTrue(safe.contains("/series/***/***/900.ts"))
        assertTrue(safe.contains("username=***"))
        assertTrue(safe.contains("password=***"))
        assertTrue(safe.contains("token=***"))
        assertFalse(safe.contains("alice"))
        assertFalse(safe.contains("password123"))
        assertFalse(safe.contains("abc"))
    }

    @Test
    fun `invalid URLs are never echoed back`() {
        val safe = sanitizeStreamUrl("not a valid url with user/password")
        assertEquals("stream://redacted", safe)
    }
}

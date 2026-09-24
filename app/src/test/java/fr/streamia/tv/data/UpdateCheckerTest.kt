package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.json.JSONObject
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `reads build number from release notes`() {
        assertEquals(1234, parseBuildNumber("APK généré depuis main (abc1234, build 1234). Téléchargez streamia-tv.apk."))
    }

    @Test
    fun `old notes without build number give null`() {
        assertNull(parseBuildNumber("APK Android TV généré automatiquement depuis main (abc1234)."))
    }

    @Test
    fun `picks the apk asset download url`() {
        val release = JSONObject(
            """{"assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt"},""" +
                """{"name":"streamia-tv.apk","browser_download_url":"https://x/streamia-tv.apk"}]}""",
        )
        assertEquals("https://x/streamia-tv.apk", apkUrl(release))
        assertNull(apkUrl(JSONObject("{}")))
    }
}

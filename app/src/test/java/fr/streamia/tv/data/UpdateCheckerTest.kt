package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}

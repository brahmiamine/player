package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsBackupTest {
    @Test
    fun `round trip preserves every field except parental control`() {
        val original = AppSettings(
            livePreviewEnabled = false,
            livePreviewDelayMs = 1_000,
            vodSeekStepSeconds = 30,
            videoAspect = VideoAspectSetting.Zoom,
            bufferMode = BufferMode.Stable,
            liveStreamFormat = LiveStreamFormat.Hls,
            liveChannelSortOrder = LiveChannelSortOrder.Alphabetical,
            vodSortOrder = VodSortOrder.Rating,
            epgTimeOffsetHours = 2,
            autoPlayNextEpisode = false,
            subtitleSizeScale = 1.25f,
            subtitleBackgroundEnabled = false,
            parentalControlEnabled = true,
        )

        val restored = appSettingsFromBackupJson(original.toBackupJson(), fallback = AppSettings())

        assertEquals(original.copy(parentalControlEnabled = false), restored)
    }

    @Test
    fun `unknown or malformed fields fall back without throwing`() {
        val fallback = AppSettings(subtitleSizeScale = 1.25f)
        val restored = appSettingsFromBackupJson(org.json.JSONObject("{}"), fallback)
        assertEquals(fallback, restored)
    }
}

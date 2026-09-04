package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun `live preview delay cycles through supported values`() {
        var settings = AppSettings(livePreviewDelayMs = 0)

        val observed = buildList {
            repeat(AppSettings.LIVE_PREVIEW_DELAYS_MS.size) {
                add(settings.livePreviewDelayMs)
                settings = settings.copy(livePreviewDelayMs = settings.nextLivePreviewDelayMs())
            }
        }

        assertEquals(AppSettings.LIVE_PREVIEW_DELAYS_MS, observed)
        assertEquals(0, settings.livePreviewDelayMs)
    }

    @Test
    fun `vod seek step cycles and exposes milliseconds`() {
        var settings = AppSettings(vodSeekStepSeconds = 10)
        val observed = buildList {
            repeat(AppSettings.VOD_SEEK_STEPS_SECONDS.size) {
                add(settings.vodSeekStepSeconds)
                settings = settings.copy(vodSeekStepSeconds = settings.nextVodSeekStepSeconds())
            }
        }

        assertEquals(listOf(10, 30, 60), observed)
        assertEquals(10, settings.vodSeekStepSeconds)
        assertEquals(10_000L, settings.vodSeekStepMs)
    }
}

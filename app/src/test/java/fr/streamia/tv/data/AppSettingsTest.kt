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
    fun `video aspect cycles and wraps`() {
        var settings = AppSettings(videoAspect = VideoAspectSetting.Fit)
        val observed = buildList {
            repeat(VideoAspectSetting.entries.size) {
                add(settings.videoAspect)
                settings = settings.copy(videoAspect = settings.nextVideoAspect())
            }
        }

        assertEquals(
            listOf(VideoAspectSetting.Fit, VideoAspectSetting.Fill, VideoAspectSetting.Zoom),
            observed,
        )
        assertEquals(VideoAspectSetting.Fit, settings.videoAspect)
    }

    @Test
    fun `buffer mode cycles and wraps`() {
        var settings = AppSettings(bufferMode = BufferMode.LowLatency)
        val observed = buildList {
            repeat(BufferMode.entries.size) {
                add(settings.bufferMode)
                settings = settings.copy(bufferMode = settings.nextBufferMode())
            }
        }

        assertEquals(listOf(BufferMode.LowLatency, BufferMode.Auto, BufferMode.Stable), observed)
        assertEquals(BufferMode.LowLatency, settings.bufferMode)
    }

    @Test
    fun `live stream format cycles and wraps`() {
        var settings = AppSettings(liveStreamFormat = LiveStreamFormat.Auto)
        val observed = buildList {
            repeat(LiveStreamFormat.entries.size) {
                add(settings.liveStreamFormat)
                settings = settings.copy(liveStreamFormat = settings.nextLiveStreamFormat())
            }
        }

        assertEquals(listOf(LiveStreamFormat.Auto, LiveStreamFormat.Ts, LiveStreamFormat.Hls), observed)
        assertEquals(LiveStreamFormat.Auto, settings.liveStreamFormat)
    }

    @Test
    fun `live channel sort order cycles and wraps`() {
        var settings = AppSettings(liveChannelSortOrder = LiveChannelSortOrder.Provider)
        val observed = buildList {
            repeat(LiveChannelSortOrder.entries.size) {
                add(settings.liveChannelSortOrder)
                settings = settings.copy(liveChannelSortOrder = settings.nextLiveChannelSortOrder())
            }
        }

        assertEquals(
            listOf(LiveChannelSortOrder.Provider, LiveChannelSortOrder.Number, LiveChannelSortOrder.Alphabetical),
            observed,
        )
        assertEquals(LiveChannelSortOrder.Provider, settings.liveChannelSortOrder)
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

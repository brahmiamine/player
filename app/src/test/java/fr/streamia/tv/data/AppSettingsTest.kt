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
    fun `vod sort order cycles and wraps`() {
        var settings = AppSettings(vodSortOrder = VodSortOrder.Provider)
        val observed = buildList {
            repeat(VodSortOrder.entries.size) {
                add(settings.vodSortOrder)
                settings = settings.copy(vodSortOrder = settings.nextVodSortOrder())
            }
        }

        assertEquals(
            listOf(VodSortOrder.Provider, VodSortOrder.Alphabetical, VodSortOrder.RecentlyAdded, VodSortOrder.Rating),
            observed,
        )
        assertEquals(VodSortOrder.Provider, settings.vodSortOrder)
    }

    @Test
    fun `epg time offset cycles through negative and positive hours`() {
        var settings = AppSettings(epgTimeOffsetHours = -3)
        val observed = buildList {
            repeat(AppSettings.EPG_TIME_OFFSETS_HOURS.size) {
                add(settings.epgTimeOffsetHours)
                settings = settings.copy(epgTimeOffsetHours = settings.nextEpgTimeOffsetHours())
            }
        }

        assertEquals(AppSettings.EPG_TIME_OFFSETS_HOURS, observed)
        assertEquals(-3, settings.epgTimeOffsetHours)
    }

    @Test
    fun `subtitle size scale cycles and wraps`() {
        var settings = AppSettings(subtitleSizeScale = 0.75f)
        val observed = buildList {
            repeat(AppSettings.SUBTITLE_SIZE_SCALES.size) {
                add(settings.subtitleSizeScale)
                settings = settings.copy(subtitleSizeScale = settings.nextSubtitleSizeScale())
            }
        }

        assertEquals(AppSettings.SUBTITLE_SIZE_SCALES, observed)
        assertEquals(0.75f, settings.subtitleSizeScale)
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

package fr.streamia.tv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerLinkTest {
    @Test
    fun acceptsBareVideoIdAndCommonYoutubeUrls() {
        val id = "dQw4w9WgXcQ"
        listOf(
            id,
            " $id ",
            "https://www.youtube.com/watch?v=$id",
            "http://youtube.com/watch?feature=share&v=$id&t=10",
            "https://m.youtube.com/watch?v=$id#top",
            "https://youtu.be/$id?si=abc",
            "youtu.be/$id",
            "www.youtube.com/watch?v=$id",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube-nocookie.com/embed/$id?autoplay=1",
            "https://www.youtube.com/shorts/$id",
        ).forEach { raw -> assertEquals(raw, id, TrailerLink.youtubeVideoId(raw)) }
    }

    @Test
    fun opensYoutubeAppFirstThenWebFallback() {
        assertEquals(
            listOf("vnd.youtube:dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
            TrailerLink.candidates("https://youtu.be/dQw4w9WgXcQ"),
        )
    }

    @Test
    fun keepsDirectNonYoutubeLinks() {
        assertEquals(listOf("https://cdn.example.org/trailer.mp4"), TrailerLink.candidates("https://cdn.example.org/trailer.mp4"))
    }

    @Test
    fun ignoresEmptyTruncatedOrInvalidValues() {
        listOf(null, "", "  ", "0", "null", "https://www.youtube.com/watch?v=", "https://youtu.be/", "abc", "ftp://example.org/x", "https://example")
            .forEach { raw -> assertTrue("$raw", TrailerLink.candidates(raw).isEmpty()) }
        assertNull(TrailerLink.youtubeVideoId("https://example.org/watch?v=dQw4w9WgXcQ"))
        assertNull(TrailerLink.youtubeVideoId("https://notyoutube.com/watch?v=dQw4w9WgXcQ"))
    }
}

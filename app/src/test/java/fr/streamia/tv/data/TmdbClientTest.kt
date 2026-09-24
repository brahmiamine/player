package fr.streamia.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbClientTest {
    @Test
    fun `parses series keywords and recommendations`() {
        val info = parseTmdbInfo(
            JSONObject(
                """
                {"overview":"The true story of a nuclear disaster.","genres":[{"id":18,"name":"Drama"}],
                 "keywords":{"results":[{"name":"nuclear catastrophe"},{"name":"based on true story"}]},
                 "recommendations":{"results":[{"id":1,"name":"The Days","original_name":"The Days","first_air_date":"2023-06-01"},
                                               {"id":0,"name":"broken"}]}}
                """.trimIndent(),
            ),
        )
        assertEquals("The true story of a nuclear disaster.", info.overview)
        assertEquals(listOf("Drama"), info.genres)
        assertEquals(listOf("nuclear catastrophe", "based on true story"), info.keywords)
        assertEquals(listOf(TmdbTitle(1, "The Days", null, 2023)), info.recommendations)
    }

    @Test
    fun `parses movie keywords format`() {
        val info = parseTmdbInfo(
            JSONObject(
                """{"overview":"","keywords":{"keywords":[{"name":"memory loss"}]},
                   "recommendations":{"results":[{"id":77,"title":"Memento","original_title":"Memento","release_date":"2000-10-11"}]}}""",
            ),
        )
        assertEquals(null, info.overview)
        assertEquals(listOf("memory loss"), info.keywords)
        assertEquals(listOf(TmdbTitle(77, "Memento", null, 2000)), info.recommendations)
    }
}

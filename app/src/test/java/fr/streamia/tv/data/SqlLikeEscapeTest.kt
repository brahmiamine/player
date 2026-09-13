package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SqlLikeEscapeTest {
    @Test
    fun `literals stay unchanged`() {
        assertEquals("france info", escapeSqlLike("france info"))
    }

    @Test
    fun `wildcards and backslashes are escaped`() {
        assertEquals("100\\%", escapeSqlLike("100%"))
        assertEquals("a\\_b", escapeSqlLike("a_b"))
        assertEquals("c:\\\\temp", escapeSqlLike("c:\\temp"))
        assertEquals("\\%\\_\\\\", escapeSqlLike("%_\\"))
    }
}

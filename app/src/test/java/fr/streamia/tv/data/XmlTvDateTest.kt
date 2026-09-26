package fr.streamia.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class XmlTvDateTest {
    private fun reference(pattern: String, value: String): Long =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)!!.time / 1000

    @Test
    fun `seconds with positive offset match SimpleDateFormat`() {
        assertEquals(reference("yyyyMMddHHmmss Z", "20260926203000 +0200"), parseXmlTvDate("20260926203000 +0200"))
    }

    @Test
    fun `negative offset, minutes precision and extra spaces`() {
        assertEquals(reference("yyyyMMddHHmmss Z", "20240229235959 -0530"), parseXmlTvDate("20240229235959   -0530"))
        assertEquals(reference("yyyyMMddHHmm Z", "202601010005 +0000"), parseXmlTvDate(" 202601010005 +0000 "))
    }

    @Test
    fun `no offset is read as UTC`() {
        assertEquals(reference("yyyyMMddHHmmss", "19991231230000"), parseXmlTvDate("19991231230000"))
    }

    @Test
    fun `offset with colon is accepted`() {
        assertEquals(reference("yyyyMMddHHmmss Z", "20260926203000 +0100"), parseXmlTvDate("20260926203000 +01:00"))
    }

    @Test
    fun `blank or garbage gives null`() {
        assertNull(parseXmlTvDate(null))
        assertNull(parseXmlTvDate("   "))
        assertNull(parseXmlTvDate("not a date"))
    }
}

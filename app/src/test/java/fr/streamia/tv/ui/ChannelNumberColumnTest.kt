package fr.streamia.tv.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNumberColumnTest {
    @Test
    fun shortNumbersKeepTheOriginalColumn() {
        assertEquals(38.dp, channelNumberColumnWidth(999))
    }

    @Test
    fun fiveDigitNumbersGetAWiderColumn() {
        assertTrue(channelNumberColumnWidth(17055) > channelNumberColumnWidth(999))
        assertTrue(channelNumberColumnWidth(170550) > channelNumberColumnWidth(17055))
    }
}

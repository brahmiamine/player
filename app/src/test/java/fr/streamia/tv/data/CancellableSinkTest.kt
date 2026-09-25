package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CancellableSinkTest {
    private class RecordingSink : CatalogWriteSink {
        val written = mutableListOf<MediaEntry>()
        override fun writeCategories(categories: List<MediaCategory>) = Unit
        override fun writeEntries(entries: List<MediaEntry>) { written += entries }
    }

    private val entry = MediaEntry(id = 1, name = "TF1", type = MediaType.Live, categoryId = "fr", iconUrl = null, number = 1)

    @Test
    fun writesPassThroughWhileActive() {
        val sink = RecordingSink()
        sink.cancellableBy(Job()).writeEntries(listOf(entry))
        assertEquals(listOf(entry), sink.written)
    }

    @Test
    fun cancelledLoadStopsBeforeWriting() {
        val sink = RecordingSink()
        val job = Job().apply { cancel() }
        assertThrows(CancellationException::class.java) { sink.cancellableBy(job).writeEntries(listOf(entry)) }
        assertEquals(emptyList<MediaEntry>(), sink.written)
    }
}

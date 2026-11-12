package app.winters.octo.device

import app.winters.octo.catalog.longOf
import app.winters.octo.testing.FakeMediaStore
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A scan holds a page of files at a time: counted at the database, no
// statement yields more than a page of rows or binds more than 900 values,
// on a first scan, a scan after changes and through the merge after it,
// with 2,500 of the 3,000 files in one album.
@RunWith(RobolectricTestRunner::class)
class PhoneResidencyTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aScanReadsAPageAtATime() = runBlocking {
        val files = PhoneFiles.albums(files = 500, perAlbum = 10, unknownAlbum = 2_500)
        val phone = db.phoneLibrary(files)
        db.counted.reset()
        phone.rescan()
        assertEquals(3_000L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = 'device'"))
        assertBounded("the first scan")

        // Every file changed since: the next scan reads every file's tags
        // again and writes every row.
        FakeMediaStore.current!!.files = PhoneFiles(files.count) { files.file(it).let { f -> f.copy(modifiedSeconds = f.modifiedSeconds + 60) } }
        db.counted.reset()
        phone.rescan()
        assertEquals(3_000L, db.longOf("SELECT COUNT(*) FROM file_tags"))
        assertBounded("a scan after every file changed")
    }

    private fun assertBounded(what: String) {
        val read = db.counted.maxRead
        assertTrue("$what read ${read?.rowsRead} rows in one statement: $read", (read?.rowsRead ?: 0) <= 500)
        val bound = db.counted.maxBound
        assertTrue("$what bound ${bound?.boundArgs} values in one statement: $bound", (bound?.boundArgs ?: 0) <= 900)
    }
}

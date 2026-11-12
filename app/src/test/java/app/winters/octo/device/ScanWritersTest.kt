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

// Every write a scan makes is short: no statement of the scan's changes more
// than 2,000 rows, even when it empties a copy of 6,000 songs that turned
// out the same as the library's and the working rows it built them from.
@RunWith(RobolectricTestRunner::class)
class ScanWritersTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun noWriteOfAScanTakesMoreThanAChunk() = runBlocking {
        val files = PhoneFiles.albums(files = 5_000, perAlbum = 20, unknownAlbum = 1_000)
        val phone = db.phoneLibrary(files)
        phone.rescan()
        // Every file touched and no song changed: the scan builds, finds its
        // copy the same as the library's, and drains it.
        FakeMediaStore.current!!.files = PhoneFiles(files.count) { files.file(it).let { f -> f.copy(modifiedSeconds = f.modifiedSeconds + 1) } }
        val (_, ran) = db.counted.record { runBlocking { phone.rescan() } }
        val scans = ran.filter { it.label == "phone scan" && !it.bookkeeping }
        val drained = scans.filter { it.sql.startsWith("DELETE FROM stage_track") }.sumOf { it.rowsChanged }
        assertEquals("the copy drained", 6_000, drained)
        val biggest = scans.maxByOrNull { it.rowsChanged }
        assertTrue("$biggest", biggest!!.rowsChanged <= 2_000)
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM stage_track"))
    }
}

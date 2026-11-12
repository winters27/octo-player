package app.winters.octo.device

import app.winters.octo.catalog.MERGE_WORK_CLEAR
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A launch with nothing changed on the phone reads no saved tags and no
// phone rows and writes nothing, counted at the database. The merge it
// asks for still notes which server it merged for (merge_state), as every
// merge does.
@RunWith(RobolectricTestRunner::class)
class QuietLaunchTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aSecondScanWithNothingChangedReadsAndWritesNothingBig() = runBlocking {
        val merge = db.testMerge(null)
        val phone = db.phoneLibrary(PhoneFiles.albums(files = 1_500, perAlbum = 12, unknownAlbum = 300), merge)
        phone.rescan()
        // The first scan's copy leaves the stage, and its merge's work rows
        // go, in the background, a page at a time: both done before the
        // second scan is counted.
        merge.awaitStageClear(DEVICE)
        merge.awaitClear(MERGE_WORK_CLEAR)
        val (_, ran) = db.counted.record { runBlocking { phone.rescan() } }
        val own = ran.filterNot { it.bookkeeping }
        val big = own.filter { s -> listOf("file_tags", "source_track", "stage_").any { it in s.sql } && s.rowsRead > 1 }
        assertEquals("reads of tags or phone rows", emptyList<String>(), big.map { it.toString() })
        val writes = own.filter { it.rowsChanged > 0 && "merge_state" !in it.sql }
        assertEquals("writes", emptyList<String>(), writes.map { it.toString() })
    }
}

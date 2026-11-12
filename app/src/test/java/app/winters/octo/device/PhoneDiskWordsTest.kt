package app.winters.octo.device

import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.library
import app.winters.octo.catalog.longOf
import app.winters.octo.catalog.put
import app.winters.octo.catalog.stage
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A scan the phone has no room for says so as a copy of the library does,
// with how much to free when that is known, and the words go once a scan
// is saved.
@RunWith(RobolectricTestRunner::class)
class PhoneDiskWordsTest {
    private val db = openTestCatalog()
    private val files = PhoneFiles.albums(files = 300, perAlbum = 10)

    @After
    fun close() = db.close()

    @Test
    fun tooLittleRoomSaysHowMuchToFree() = runBlocking {
        val phone = db.phoneLibrary(files, space = 10)
        phone.rescan()
        val words = phone.problem.value
        assertTrue("$words", words!!.startsWith("Not enough space to copy the library. Free up "))
        assertTrue("$words", words.endsWith(" MB, then try again."))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM scan_file"))
    }

    @Test
    fun aFullDiskSaysToFreeSomeSpaceAndGoesWhenAScanIsSaved() = runBlocking {
        val phone = db.phoneLibrary(files)
        db.counted.fullDisk = { "file_tags" in it && it.trimStart().startsWith("INSERT") }
        phone.rescan()
        assertEquals("Not enough space to copy the library. Free up some space, then try again.", phone.problem.value)
        db.counted.fullDisk = null
        phone.rescan()
        assertNull(phone.problem.value)
        assertEquals(300L, db.longOf("SELECT COUNT(*) FROM track"))
    }

    // The merge has no room to swap the phone's copy and a server's in: both
    // go, and the phone says so with the copy's words.
    @Test
    fun aSpaceAbortOfTheMergeShowsTheCopysWords() = runBlocking {
        val server = "server:home.test"
        db.put(library(server, 4, msShift = 100))
        var room = Long.MAX_VALUE
        val merge = CatalogMerge(db, { room }, { server }, db.workDao())
        val phone = db.phoneLibrary(files, merge)
        phone.scan.reached = {
            if (it == ScanPoint.Ready) {
                db.stage(library(server, 5, msShift = 100), generation = 99)
                room = 10
            }
        }
        phone.rescan()
        val words = phone.problem.value
        assertTrue("$words", words!!.startsWith("Not enough space to copy the library. Free up "))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM track WHERE sourceId = 'device'"))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM stage_track"))
        assertEquals(4L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ?", server))
    }
}

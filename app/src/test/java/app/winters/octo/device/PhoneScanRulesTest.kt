package app.winters.octo.device

import app.winters.octo.catalog.longOf
import app.winters.octo.catalog.stringOf
import app.winters.octo.server.StagedCopy
import app.winters.octo.testing.PhoneFile
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.TestFolderRules
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The scan's own rules: a file the media library lists twice in one
// listing is kept as first listed (a pin of a declared rule: today two
// songs of one id went in); the folders switched off are read once as a
// scan starts, and a change meanwhile takes effect at the next scan; the
// phone's copy and a server's never share a generation; and the cost of an
// album stays the same with ten times as many albums.
@RunWith(RobolectricTestRunner::class)
class PhoneScanRulesTest {
    private val db = openTestCatalog()
    private val rules = TestFolderRules()

    @After
    fun close() = db.close()

    @Test
    fun aFileListedTwiceKeepsItsFirstListing() = runBlocking {
        val files = listOf(
            PhoneFile(1, "a.flac", "Music/A/", title = "First", album = "A"),
            PhoneFile(2, "b.flac", "Music/A/", title = "Other", album = "A"),
            PhoneFile(1, "a.flac", "Music/A/", title = "Second", album = "A"),
        )
        db.phoneLibrary(PhoneFiles(files.size) { files[it] }).rescan()
        assertEquals(2L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = 'device'"))
        assertEquals("First", db.stringOf("SELECT title FROM source_track WHERE id = 'device:1'"))
    }

    @Test
    fun foldersSwitchedOffDuringAScanCountFromTheNext() = runBlocking {
        val phone = db.phoneLibrary(PhoneFiles.albums(files = 40, perAlbum = 10, unknownAlbum = 10), rules = rules)
        phone.scan.reached = { if (it == ScanPoint.Started) rules.switchedOff.value = setOf("Download") }
        phone.rescan()
        assertEquals(50L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = 'device'"))
        phone.scan.reached = {}
        phone.rescan()
        assertEquals(40L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = 'device'"))
        assertEquals(listOf(true, false), phone.folders.value.map { it.included })
    }

    @Test
    fun thePhonesCopyAndAServersTakeTheirOwnGenerations() = runBlocking {
        val merge = db.testMerge("server:home.test")
        val phone = db.phoneLibrary(PhoneFiles.albums(files = 20, perAlbum = 10), merge)
        var phoneGeneration = 0L
        var serverGeneration = 0L
        phone.scan.reached = {
            if (it == ScanPoint.Started) {
                phoneGeneration = db.longOf("SELECT generation FROM source_meta WHERE sourceId = 'device'")
                serverGeneration = StagedCopy(db, merge) { Long.MAX_VALUE }.start("account", "server:home.test")
            }
        }
        phone.rescan()
        assertTrue(phoneGeneration > 0)
        assertNotEquals(phoneGeneration, serverGeneration)
        assertTrue(serverGeneration > phoneGeneration)
    }

    @Test
    fun anAlbumCostsTheSameWithTenTimesAsManyAlbums() = runBlocking {
        val small = biggestStatements(PhoneFiles.albums(files = 2_000, perAlbum = 1))
        val big = biggestStatements(PhoneFiles.albums(files = 20_000, perAlbum = 1))
        assertEquals(small, big)
    }

    // The most rows one statement read, and the most values one bound, in
    // a first scan of `files`.
    private suspend fun biggestStatements(files: PhoneFiles): Pair<Int, Int> {
        val library = openTestCatalog()
        try {
            val phone = library.phoneLibrary(files)
            library.counted.reset()
            phone.rescan()
            return (library.counted.maxRead?.rowsRead ?: 0) to (library.counted.maxBound?.boundArgs ?: 0)
        } finally {
            library.close()
        }
    }
}

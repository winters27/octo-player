package app.winters.octo.device

import app.winters.octo.catalog.TestHooks
import app.winters.octo.catalog.longOf
import app.winters.octo.catalog.stringOf
import app.winters.octo.testing.FakeMediaStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Everything a build reads besides the saved tags is in the digest, so a
// change to any of it builds again: one field of one file, a time the clock
// moves past, the folders switched off, the tags version and the app's
// version. A build that fails leaves no digest, so the next scan builds.
// A change that leaves the library as it was is built and merges nothing.
@RunWith(RobolectricTestRunner::class)
// A phone new enough that the media library gives every field read.
@Config(sdk = [35])
class DigestSoundnessTest {
    private val db = openTestCatalog()
    private val rules = TestFolderRules()
    private val files = PhoneFiles.albums(files = 40, perAlbum = 10, unknownAlbum = 5)

    @After
    fun close() = db.close()

    // Scans again and answers whether it built (true) or found nothing
    // changed (false).
    private suspend fun DeviceLibrary.builds(): Boolean {
        var built = false
        scan.reached = { if (it == ScanPoint.Started) built = true }
        rescan()
        return built
    }

    private fun digest() = db.stringOf("SELECT digest FROM phone_scan_state WHERE id = 0")

    @Test
    fun anUnchangedPhoneBuildsNothing() = runBlocking {
        val phone = db.phoneLibrary(files)
        assertTrue(phone.builds())
        assertNotNull(digest())
        assertFalse(phone.builds())
        assertEquals("listed once to build, once to look", 2, FakeMediaStore.current!!.queries.get())
    }

    @Test
    fun oneFieldOfOneFileBuildsAgain() = runBlocking {
        val phone = db.phoneLibrary(files)
        phone.builds()
        val fields = listOf<(PhoneFile) -> PhoneFile>(
            { it.copy(displayName = "renamed.flac") },
            { it.copy(relativePath = "Music/Elsewhere/") },
            { it.copy(volume = "1a2b-3c4d") },
            { it.copy(modifiedSeconds = it.modifiedSeconds + 1) },
            { it.copy(sizeBytes = it.sizeBytes + 1) },
            { it.copy(durationMs = it.durationMs + 1) },
            { it.copy(mimeType = "audio/ogg") },
            { it.copy(title = "Other") },
            { it.copy(artist = "Other") },
            { it.copy(album = "Other") },
            { it.copy(albumArtist = "Other") },
            { it.copy(track = 77) },
            { it.copy(disc = "3") },
            { it.copy(year = 1999) },
            { it.copy(genre = "Jazz") },
        )
        for ((n, change) in fields.withIndex()) {
            FakeMediaStore.current!!.files = PhoneFiles(files.count) { i -> files.file(i).let { if (i == 17) change(it) else it } }
            assertTrue("field $n", phone.builds())
            FakeMediaStore.current!!.files = files
            assertTrue("field $n back", phone.builds())
            assertFalse("field $n settled", phone.builds())
        }
    }

    @Test
    fun aTimeTheClockMovesPastBuildsAgain() = runBlocking {
        // Changed a day and a minute from now: no real time yet, so the
        // phone's added time stands; once the clock moves on, it is real.
        val later = PHONE_NOW + 86_400 + 60
        val odd = PhoneFiles(files.count) { i -> files.file(i).let { if (i == 3) it.copy(modifiedSeconds = later, addedSeconds = later + 120) else it } }
        val phone = db.phoneLibrary(odd, now = PHONE_NOW)
        phone.builds()
        assertEquals(later + 120, db.longOf("SELECT addedAt FROM source_track WHERE id = 'device:4'"))
        phone.scan.scanner.nowSeconds = { PHONE_NOW + 100 }
        assertTrue(phone.builds())
        assertEquals(later, db.longOf("SELECT addedAt FROM source_track WHERE id = 'device:4'"))
    }

    @Test
    fun theFoldersSwitchedOffAreInTheDigest() = runBlocking {
        val phone = db.phoneLibrary(files, rules = rules)
        phone.builds()
        rules.switchedOff.value = setOf("Download")
        assertTrue(phone.builds())
        assertEquals(40L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = 'device'"))
        assertFalse(phone.builds())
    }

    @Test
    fun theTagsVersionAndTheAppsVersionAreInTheDigest() = runBlocking {
        val phone = db.phoneLibrary(files)
        phone.builds()
        phone.scan.tuning = phone.scan.tuning.copy(tagsVersion = TAGS_VERSION + 1)
        assertTrue(phone.builds())
        assertEquals(45L, db.longOf("SELECT COUNT(*) FROM file_tags WHERE tagsVersion = ?", TAGS_VERSION + 1))
        assertFalse(phone.builds())
        phone.scan.tuning = phone.scan.tuning.copy(versionCode = phone.scan.tuning.versionCode + 1)
        assertTrue(phone.builds())
        assertFalse(phone.builds())
    }

    @Test
    fun aBuildThatFailsLeavesNoDigest() = runBlocking {
        val phone = db.phoneLibrary(files)
        phone.builds()
        FakeMediaStore.current!!.files = PhoneFiles(files.count + 1) { i -> if (i < files.count) files.file(i) else files.file(0).copy(id = 500, displayName = "late.flac") }
        db.counted.fullDisk = { "stage_track" in it && it.trimStart().startsWith("INSERT") }
        assertTrue(phone.builds())
        db.counted.fullDisk = null
        assertNull(digest())
        assertTrue(phone.builds())
        assertEquals(46L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = 'device'"))
    }

    // A file added to a switched-off folder changes what is listed but not
    // the library: the scan builds, finds the stage the same, and merges
    // nothing.
    @Test
    fun aFileInASwitchedOffFolderMergesNothing() = runBlocking {
        rules.switchedOff.value = setOf("WhatsApp")
        val hooks = TestHooks()
        val phone = db.phoneLibrary(files, db.testMerge(null, hooks), rules = rules)
        phone.builds()
        val swaps = hooks.events.size
        FakeMediaStore.current!!.files = PhoneFiles(files.count + 1) { i ->
            if (i < files.count) files.file(i) else PhoneFile(id = 600, displayName = "voice.opus", relativePath = "WhatsApp/Audio/", title = "Voice")
        }
        assertTrue(phone.builds())
        assertEquals("no swap", swaps, hooks.events.size)
        assertEquals("the first scan's merge only", 1, hooks.merges.get())
        assertNotNull(digest())
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM stage_track"))
        assertFalse(phone.builds())
    }
}

package app.winters.octo.device

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.longOf
import app.winters.octo.testing.PhoneFile
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

// The room a scan asks for holds within ten percent of what it takes, on a
// phone of 6,000 files with tags as full as a tagged library's: the
// working rows per listed file, at their most (filed into albums, before
// the albums are written), and the saved tags per file read for the first
// time.
@RunWith(RobolectricTestRunner::class)
class PhoneDiskConstantsTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun CatalogDatabase.usedBytes() =
        (longOf("PRAGMA page_count") - longOf("PRAGMA freelist_count")) * longOf("PRAGMA page_size")

    @Test
    fun diskConstantsHoldWithinTenPercent() = runBlocking {
        val count = 6_000
        val files = (0 until count).map { i ->
            val album = i / 12
            PhoneFile(
                id = i + 1L,
                displayName = "%02d %s.flac".format(1 + i % 12, "Song title number $i"),
                relativePath = "Music/Artist name ${album % 80}/Album title number $album/",
            )
        }
        val tagged = TestPhoneFiles(
            files,
            files.associate { f ->
                val album = (f.id - 1) / 12
                f.id to FileTags(
                    title = "Song title number ${f.id}", artist = "Artist name ${album % 80}", albumArtist = "Artist name ${album % 80}",
                    album = "Album title number $album", trackNo = (1 + (f.id - 1) % 12).toInt(), year = 1990 + (album % 30).toInt(),
                    genres = listOf("Genre ${album % 15}"), mbAlbumId = "0b2e6f1c-5a4d-4c3b-9e8f-%012d".format(album),
                    mbRecordingId = "7d1a2b3c-4e5f-4a6b-8c9d-%012d".format(f.id), artists = listOf("Artist name ${album % 80}"),
                    trackGain = -7.5f, trackPeak = 0.98f,
                )
            },
        )
        val phone = db.phoneLibrary(tagged.listed())
        val start = db.usedBytes()
        // The tags as a first scan saves them.
        db.saveTags(tagged.saved())
        val tags = (db.usedBytes() - start) / count
        var scan = 0L
        val listed = db.usedBytes()
        phone.scan.reached = { if (it == ScanPoint.Grouped) scan = (db.usedBytes() - listed) / count }
        phone.rescan()
        println("MEASURED phone scan files=$count perFile=$scan perTags=$tags")
        assertTrue("scan $scan against $SCAN_BYTES_PER_FILE", abs(scan - SCAN_BYTES_PER_FILE) <= SCAN_BYTES_PER_FILE / 10)
        assertTrue("tags $tags against $TAG_BYTES_PER_FILE", abs(tags - TAG_BYTES_PER_FILE) <= TAG_BYTES_PER_FILE / 10)
    }
}

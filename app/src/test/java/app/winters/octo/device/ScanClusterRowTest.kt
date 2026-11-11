package app.winters.octo.device

import app.winters.octo.catalog.ScanClusterEntity
import app.winters.octo.testing.PhoneFile
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Filing files under their album names writes one row per name, counted:
// an insert that leaves a column without a default out would be skipped
// without a word, and the name's files would be filed under no album.
@RunWith(RobolectricTestRunner::class)
// A phone new enough that the media library gives album artists.
@Config(sdk = [35])
class ScanClusterRowTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aNamesFilesAreCountedInOneRow() = runBlocking {
        val files = listOf(
            PhoneFile(1, "a.flac", "Music/X/", album = "Same", albumArtist = "Queen"),
            PhoneFile(2, "b.flac", "Music/Y/", album = "same", albumArtist = "Queen"),
            PhoneFile(3, "c.flac", "Music/Z/", album = "Same"),
        )
        val phone = db.phoneLibrary(PhoneFiles(files.size) { files[it] })
        var clusters = emptyList<ScanClusterEntity>()
        phone.scan.reached = { if (it == ScanPoint.Grouped) clusters = db.phoneScanDao().firstClusters(10) }
        phone.rescan()
        assertEquals(1, clusters.size)
        val cluster = clusters.single()
        assertEquals("same", cluster.nameKey)
        assertEquals(3, cluster.files)
        assertEquals(2, cluster.byFiles)
        assertEquals(0, cluster.mbFiles)
        assertEquals("by:queen", cluster.byFirst)
        assertEquals(false, cluster.byVaries)
        assertEquals(0L, cluster.firstSeq)
    }
}

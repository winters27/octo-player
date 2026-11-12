package app.winters.octo.proof

import app.winters.octo.catalog.FreeSpace
import app.winters.octo.data.Session
import app.winters.octo.server.StagedCopy
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

// The most heap one more album (and its share of artists) may take while
// a copy and merge run: its slim row in the merge, its artist entry, its
// counts. Measured costs sit far below; the bound is what the plan allows.
private const val BYTES_PER_ALBUM = 4_096L

@RunWith(RobolectricTestRunner::class)
class AlbumScaleTest {
    // Peak heap of a copy and merge of 40,000 songs over this many albums.
    private fun peakWith(albums: Int): Long = runBlocking {
        val fake = LazySubsonic(LazyLibrary(songs = 40_000, albums = albums, artists = albums / 4), starredSongs = 0)
        val session = Session(fake.client(), serverType = null, serverVersion = null, isOcto = false, adminReachable = false, extensions = emptySet(), id = "scale")
        val db = openTestCatalog()
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        System.gc()
        pools.forEach { it.resetPeakUsage() }
        StagedCopy(db, db.testMerge(session.sourceId), FreeSpace { Long.MAX_VALUE }).copy("scale", session.sourceId, session.client)
        val peak = pools.sumOf { it.peakUsage.used }
        db.close()
        fake.close()
        peak
    }

    @Test
    fun moreAlbumsCostAtMostTheirStatedShare() {
        assumeTrue(System.getProperty("octo.heapProof") == "true")
        // A first run warms the code, so neither measured run pays for loading it.
        peakWith(1_000)
        val small = peakWith(2_000)
        val large = peakWith(20_000)
        val perAlbum = (large - small) / 18_000
        println("RESULT album-scale peak 2k=${small / 1_000_000} MB 20k=${large / 1_000_000} MB, $perAlbum bytes per album")
        assertTrue("$perAlbum bytes per album, bound $BYTES_PER_ALBUM", perAlbum <= BYTES_PER_ALBUM)
    }
}

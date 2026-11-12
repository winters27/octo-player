package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.io.path.createTempDirectory

// The disk a big play's queue rows take, by the file's pages, matches the
// constant a build's space check asks for: never above it, and the
// constant at most a tenth above what is measured. Measured on a play of
// 20,000 songs whose ids are as long as a real server's.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class QueueBytesPerEntryTest {
    private val at = "server:music.home-server.example.net:4533:"

    private fun CatalogDatabase.serverSongs(n: Int) {
        val tracks = (0 until n).map { i ->
            val id = at + "%032x".format(i)
            val key = "untitled song number %05d".format(i)
            TrackEntity(
                id = id, sourceId = "server:music.home-server.example.net:4533", nativeId = "%032x".format(i), title = key, searchKey = key, sortKey = key,
                artist = "ar", artistId = at + "b1", album = "al", albumId = at + "a1", trackNo = i, discNo = 1, year = null,
                durationMs = 213_000, addedAt = i.toLong(), mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = i, genre = "",
            )
        }
        runBlocking { tracks.chunked(500).forEach { dao().insertTracks(it) } }
    }

    // Bytes the file's pages in use take, every write checkpointed in.
    private fun used(db: CatalogDatabase): Long {
        val w = db.openHelper.writableDatabase
        w.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        fun pragma(name: String) = w.query("PRAGMA $name").use { it.moveToFirst(); it.getLong(0) }
        return (pragma("page_count") - pragma("freelist_count")) * pragma("page_size")
    }

    @Test
    fun aBigPlaysRowsTakeWhatTheConstantSays() {
        val dir = createTempDirectory("catalog").toFile()
        val db = openTestCatalog(dir)
        val rig = QueueRig(db, page = QUEUE_PAGE)
        try {
            val n = 20_000
            db.serverSongs(n)
            val before = used(db)
            rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), null, shownOverWindow = true))
            rig.joined()
            rig.settle()
            val rows = rig.rows().size
            val perEntry = (used(db) - before) / rows
            println("MEASURED queue entry rows=$rows perEntry=$perEntry constant=$QUEUE_BYTES_PER_ENTRY")
            assertTrue("$rows rows", rows == n)
            assertTrue("measured $perEntry, constant $QUEUE_BYTES_PER_ENTRY", perEntry <= QUEUE_BYTES_PER_ENTRY)
            assertTrue("measured $perEntry, constant $QUEUE_BYTES_PER_ENTRY", QUEUE_BYTES_PER_ENTRY <= perEntry * 11 / 10 + 10)
        } finally {
            rig.close()
            dir.deleteRecursively()
        }
    }
}

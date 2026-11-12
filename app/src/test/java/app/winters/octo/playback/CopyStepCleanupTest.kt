package app.winters.octo.playback

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.serverReads
import app.winters.octo.listening.timed
import app.winters.octo.playlists.PieceBody
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// A play still downloading its list's answer is stopped by a newer tap:
// the server finds the connection closed within a buffer or two of the tap,
// far short of the whole answer, and the stopped play leaves no rows and no
// drained file.
@RunWith(RobolectricTestRunner::class)
class CopyStepCleanupTest {
    private val db = openTestCatalog()
    private val server = ListServer((0 until 20_000).associate { "srv$it" to Song("srv$it", title = "A long enough title for song $it", artist = "Artist $it") })
    private val drains = Files.createTempDirectory("drain").toFile()
    private val owners = WorkOwners()
    private val starts = QueueStarts(db, { error("no loaders") }, { error("no live") }, { error("no health") }, owners, { db.serverReads(server, drains = drains, owners = owners) })

    @After
    fun close() {
        server.close()
        db.close()
    }

    @Test
    fun aNewerTapStopsTheDownloadWithinABuffer() = timed(60) {
        server.lists["L"] = (0 until 20_000).map { "srv$it" }
        server.lists["S"] = (0 until 10).map { "srv$it" }
        val piece = 64 * 1024
        val permits = Semaphore(0)
        val written = AtomicInteger()
        val finished = CountDownLatch(1)
        var pieces = 0
        server.listBody = { text ->
            val bytes = text.toByteArray()
            pieces = (bytes.size + piece - 1) / piece
            PieceBody(bytes.size.toLong()) { sink ->
                try {
                    for (from in bytes.indices step piece) {
                        permits.acquire()
                        sink.write(bytes, from, minOf(piece, bytes.size - from))
                        sink.flush()
                        written.incrementAndGet()
                    }
                } catch (e: IOException) {
                    // The client closed the connection.
                } finally {
                    finished.countDown()
                }
            }
        }
        runBlocking {
            val first = async(Dispatchers.IO) { starts.read(ListSpec.Online(OnlineKind.List, "L"), null, false, false, null, 7L) }
            permits.release(3)
            withTimeout(10_000) { while (written.get() < 3) yield() }
            server.listBody = null
            val second = async(Dispatchers.IO) { starts.read(ListSpec.Online(OnlineKind.List, "S"), null, false, false, null, 8L) }
            assertTrue(second.await() is StartRead.Whole)
            assertTrue(runCatching { first.await() }.isFailure)
            permits.release(pieces)
            assertTrue("the server went on writing", finished.await(10, TimeUnit.SECONDS))
        }
        assertTrue("$pieces pieces in the answer", pieces > 15)
        assertTrue("${written.get()} pieces sent after the tap", written.get() <= 6)
        assertEquals(0, db.query("SELECT COUNT(*) FROM server_entry WHERE owner = ?", arrayOf(owners.queue(7))).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(emptyList<String>(), drains.listFiles().orEmpty().map { it.name })
    }
}

package app.winters.octo.playback

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.serverReads
import app.winters.octo.discovery.shownIds
import app.winters.octo.discovery.today.todayResolve
import app.winters.octo.listening.timed
import app.winters.octo.server.RemoteQueue
import app.winters.octo.server.remoteQueueOf
import app.winters.octo.subsonic.PlayQueue
import app.winters.octo.subsonic.PlayQueueByIndex
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A labeled pin. The play queue saved on the server starts from its answer,
// drained and worked out with reads alone, and its build's copy is made
// from the same answer: it starts at the song and place today's take
// starts at (server/QueueSync.kt take at c2208eaa: the queue resolved
// whole, the current song resolved alone and found among it, the position
// kept only when it is), with the current song found, not found and named
// twice, by id and by place, and the copy shows today's songs. A queue of
// at most 500 plays whole; an empty one is refused.
@RunWith(RobolectricTestRunner::class)
class ServerQueueCopyTest {
    private val db = openTestCatalog()
    private val songs = (0 until 3_000).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist ${it % 5}") }
    private val server = ListServer(songs)

    @After
    fun close() {
        server.close()
        db.close()
    }

    private fun library() = db.putLibrary((0 until 3_000 step 3).map { libraryTrack("t$it", "Title $it", "Artist ${it % 5}") }) { "srv${it.id.removePrefix("t")}" }

    // Today's take: the songs, the start's place and position.
    private fun todayTake(remote: RemoteQueue): Triple<List<String>, Int, Long>? {
        val tracks = todayResolve(db, SERVER_SOURCE, remote.songs)
        if (tracks.isEmpty()) return null
        val current = remote.current?.let { todayResolve(db, SERVER_SOURCE, listOf(it)).firstOrNull() }
        val index = current?.let { song -> tracks.indexOfFirst { it.id == song.id } }?.takeIf { it >= 0 }
        return Triple(tracks.map { it.id }, index ?: 0, if (index != null) remote.positionMs else 0)
    }

    // The reads the last play's start used: its build's copy is made there.
    private lateinit var reads: app.winters.octo.discovery.ServerReads

    private fun play(indexed: Boolean, listId: Long): StartRead {
        reads = db.serverReads(server, indexedQueue = indexed)
        val starts = QueueStarts(db, { error("no loaders") }, { error("no live") }, { error("no health") }, WorkOwners(), { reads })
        return runBlocking { starts.read(ListSpec.ServerQueue, null, false, false, null, listId) }
    }

    private fun checkBig(entries: List<String>, current: String?, index: Int?, indexed: Boolean) {
        server.queue = entries
        server.current = current
        server.currentIndex = index
        server.position = 42_000
        val queue = entries.map { songs.getValue(it) }
        val remote = if (indexed) remoteQueueOf(PlayQueueByIndex(queue, index, 42_000)) else remoteQueueOf(PlayQueue(queue, current, 42_000))
        val (ids, at, ms) = todayTake(remote)!!
        val read = play(indexed, 7).expect<StartRead.Big>()
        assertEquals("start of $current/$index", ids[at], read.trackId)
        assertEquals(ms, read.positionMs)
        // The build's copy, made from the answer the start drained.
        val owner = WorkOwners.marked("queue:7")
        runBlocking { reads.fill(ListSpec.ServerQueue, owner) }
        assertEquals(ids, runBlocking { reads.shownIds(owner) })
        runBlocking { reads.clear(owner) }
    }

    @Test
    fun aBigQueueStartsWhereTodaysTakeStarts() = timed(60) {
        library()
        val entries = (0 until 3_000).map { "srv$it" } + listOf("srv1203", "srv5")
        checkBig(entries, "srv1203", null, indexed = false)
        // Not in the queue: its first song from the start.
        checkBig(entries, "srv-none", null, indexed = false)
        // Named twice: its first place.
        checkBig(entries, "srv5", null, indexed = false)
        // By place: the last entry, a repeat of srv5.
        checkBig(entries, null, entries.size - 1, indexed = true)
        checkBig(entries, null, 1_206, indexed = true)
    }

    @Test
    fun aSmallQueuePlaysWholeWhereTodaysTakeStarts() = timed(60) {
        library()
        val entries = (0 until 300).map { "srv$it" }
        server.queue = entries
        server.current = "srv201"
        server.position = 9_000
        val (ids, at, ms) = todayTake(remoteQueueOf(PlayQueue(entries.map { songs.getValue(it) }, "srv201", 9_000)))!!
        val read = play(false, 7).expect<StartRead.Whole>()
        assertEquals(ids, read.ids)
        assertEquals(at, read.start)
        assertEquals(ms, read.positionMs)
        assertEquals(0, db.query("SELECT COUNT(*) FROM server_entry", null).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun anEmptyQueueIsRefused() {
        library()
        server.queue = emptyList()
        assertEquals(StartRead.Refused(NOTHING_TO_PLAY), play(false, 7))
    }
}

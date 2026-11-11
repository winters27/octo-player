package app.winters.octo.playback

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.ServerHooks
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.serverReads
import app.winters.octo.discovery.shownIds
import app.winters.octo.discovery.today.todayResolve
import app.winters.octo.listening.timed
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

// A start read of the kind a test expects, or that test's failure.
internal inline fun <reified T : StartRead> StartRead.expect(): T {
    assertTrue("expected ${T::class.simpleName}, was $this", this is T)
    return this as T
}

// A play of a list the server answers for, with no read a page holds,
// drains the answer to a file and works its start out of it with reads
// alone: at most 500 songs play whole at once, with no copy written; more
// start with their first song (or a Shuffle's pick), and the file goes to
// the build, whose copy is made from it with no second request. A small,
// refused or canceled play leaves no rows and no drained file.
@RunWith(RobolectricTestRunner::class)
class NetworkCopyStartTest {
    private val db = openTestCatalog()
    private val songs = (0 until 3_000).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist ${it % 7}") }
    private val server = ListServer(songs)
    private val drains = Files.createTempDirectory("drain").toFile()
    private val owners = WorkOwners()
    private val reads = db.serverReads(server, drains = drains, owners = owners)
    private val starts = QueueStarts(db, { error("no loaders") }, { error("no live") }, { error("no health") }, owners, { reads })

    @After
    fun close() {
        server.close()
        db.close()
    }

    private fun library() = db.putLibrary((0 until 3_000 step 2).map { libraryTrack("t$it", "Title $it", "Artist ${it % 7}") }) { "srv${it.id.removePrefix("t")}" }

    private fun rows(owner: String) = db.query("SELECT COUNT(*) FROM server_entry WHERE owner = ?", arrayOf(owner)).use { it.moveToFirst(); it.getInt(0) }

    private fun heads(owner: String) = db.query("SELECT COUNT(*) FROM server_head WHERE owner = ?", arrayOf(owner)).use { it.moveToFirst(); it.getInt(0) }

    private fun files() = drains.listFiles().orEmpty().map { it.name }

    @Test
    fun aBigListStartsFromItsAnswerAndItsBuildCopiesTheSameFile() = timed(60) {
        library()
        val entries = (0 until 3_000).map { "srv${(it * 7) % 3_000}" } + (0 until 200).map { "srv$it" }
        server.lists["L"] = entries
        db.counted.reset()
        val read = runBlocking { starts.read(ListSpec.Online(OnlineKind.List, "L"), null, false, false, null, 7L) }.expect<StartRead.Big>()
        // A handful of rows, never a page of the list.
        assertTrue("${db.counted.maxRead}", (db.counted.maxRead?.rowsRead ?: 0) < 50)
        val today = todayResolve(db, SERVER_SOURCE, entries.map { songs.getValue(it) }).map { it.id }
        assertEquals(today.first(), read.trackId)
        assertTrue(!read.firstShuffled)
        // Nothing is written at the start; the answer waits for the build.
        assertEquals(0, rows(owners.queue(7)) + heads(owners.queue(7)))
        assertEquals(1, files().size)
        assertEquals(1, server.calls.count { it == "getPlaylist" })
        // The build's copy is made from that file: each song once, in order,
        // with no second request, and the file goes.
        runBlocking { reads.fill(ListSpec.Online(OnlineKind.List, "L"), owners.queue(7)) }
        assertEquals(today, runBlocking { reads.shownIds(owners.queue(7)) })
        assertEquals(1, server.calls.count { it == "getPlaylist" })
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun aSmallListPlaysWholeAndLeavesNothing() = timed(60) {
        library()
        val entries = (0 until 300).map { "srv$it" }
        server.lists["S"] = entries
        val read = runBlocking { starts.read(ListSpec.Online(OnlineKind.Station, "S"), ListStart.At(4, 0), false, false, null, 7L) }.expect<StartRead.Whole>()
        assertEquals(todayResolve(db, SERVER_SOURCE, entries.map { songs.getValue(it) }).map { it.id }, read.ids)
        assertEquals(4, read.start)
        assertEquals(0, rows(owners.queue(7)))
        assertEquals(0, heads(owners.queue(7)))
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun aRefusedPlayLeavesNothing() {
        library()
        server.gone += "G"
        server.lists["E"] = emptyList()
        assertEquals(StartRead.Refused(LIST_MADE_ANEW), runBlocking { starts.read(ListSpec.Online(OnlineKind.List, "G"), null, false, false, null, 7L) })
        assertEquals(StartRead.Refused(NOTHING_TO_PLAY), runBlocking { starts.read(ListSpec.Online(OnlineKind.List, "E"), null, false, false, null, 8L) })
        val signedOut = QueueStarts(db, { error("no loaders") }, { error("no live") }, { error("no health") }, owners, { db.serverReads(null) })
        assertEquals(StartRead.Refused(SIGN_IN_TO_PLAY), runBlocking { signedOut.read(ListSpec.Online(OnlineKind.List, "E"), null, false, false, null, 9L) })
        listOf(owners.queue(7), owners.queue(8), owners.queue(9)).forEach { assertEquals(it, 0, rows(it) + heads(it)) }
        assertEquals(emptyList<String>(), files())
    }

    // A newer play stops one still reading the server; the stopped one
    // leaves no rows and no file.
    @Test
    fun aNewerPlayStopsAnEarlierOneStillReading() = timed(60) {
        library()
        server.lists["L"] = (0 until 3_000).map { "srv$it" }
        server.lists["S"] = (0 until 20).map { "srv$it" }
        val hold = CountDownLatch(1)
        server.hold = "getPlaylist" to hold
        runBlocking {
            val first = async(Dispatchers.IO) { starts.read(ListSpec.Online(OnlineKind.List, "L"), null, false, false, null, 7L) }
            assertNotNull("the play never asked the server", withTimeoutOrNull(10_000) { while (server.calls.isEmpty()) yield() })
            server.hold = null
            val second = async(Dispatchers.IO) { starts.read(ListSpec.Online(OnlineKind.List, "S"), null, false, false, null, 8L) }
            assertTrue(second.await() is StartRead.Whole)
            hold.countDown()
            val stopped = runCatching { first.await() }
            assertTrue("the earlier play went on: $stopped", stopped.isFailure)
        }
        assertEquals(0, rows(owners.queue(7)) + heads(owners.queue(7)))
        assertEquals(emptyList<String>(), files())
    }

    // A play of a list a page holds a whole read of decides from that read:
    // no second request and no copy at the tap.
    @Test
    fun aPlayFromAPagesReadWritesNothing() = timed(60) {
        library()
        server.lists["S"] = (0 until 300).map { "srv$it" }
        runBlocking { reads.read(ServerListKind.List, "S", WorkOwners.marked("vm:1")) }
        var wrote = false
        reads.hooks = object : ServerHooks() {
            override suspend fun filled(owner: String, pages: Int) {
                wrote = true
            }
        }
        val read = runBlocking { starts.read(ListSpec.Online(OnlineKind.List, "S"), null, false, false, null, 7L) }
        assertTrue(read is StartRead.Whole)
        assertTrue(!wrote)
        assertEquals(1, server.calls.count { it == "getPlaylist" })
        assertEquals(0, rows(owners.queue(7)))
    }
}

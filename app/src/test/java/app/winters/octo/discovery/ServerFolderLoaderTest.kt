package app.winters.octo.discovery

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.discovery.today.todayResolve
import app.winters.octo.folders.FolderContents
import app.winters.octo.folders.SERVER_FOLDER_LIMIT
import app.winters.octo.folders.SERVER_SONG_LIMIT
import app.winters.octo.folders.gatherSongs
import app.winters.octo.listening.timed
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.NOTHING_TO_PLAY
import app.winters.octo.playback.QueueStarts
import app.winters.octo.playback.StartRead
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

// A labeled pin. A server folder's Play gathers its songs into the play's
// own copy in the order and with the limits today's gatherSongs keeps (each
// folder inside it in turn, then its own songs; at most 2,000 songs and 400
// folders opened; a folder seen twice opened once), resolved as today's
// resolveFromServer resolves the gathered list, and says when it stopped at
// a limit. Each folder's answer is drained on its own and none is left; a
// big play's gathered answer waits for its build, and goes with it.
@RunWith(RobolectricTestRunner::class)
class ServerFolderLoaderTest {
    private val db = openTestCatalog()
    private val songs = (0 until 6_000).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist") }
    private val server = ListServer(songs)
    private val drains = Files.createTempDirectory("drain").toFile()

    @After
    fun close() {
        server.close()
        db.close()
    }

    private fun library() = db.putLibrary((0 until 6_000 step 4).map { libraryTrack("t$it", "Title $it") }) { "srv${it.id.removePrefix("t")}" }

    // Today's gather over the same tree, then today's resolve.
    private fun today(id: String?): Pair<List<String>, Boolean> = runBlocking {
        fun contents(key: String) = server.folders.getValue(key).let { (subs, own) -> FolderContents(subs, own.map { songs.getValue(it) }) }
        val gathered = gatherSongs(contents(id.orEmpty()), SERVER_SONG_LIMIT, SERVER_FOLDER_LIMIT) { contents(it) }
        todayResolve(db, SERVER_SOURCE, gathered.songs).map { it.id } to gathered.limitReached
    }

    private var next = 0

    private fun songsOf(n: Int) = List(n) { "srv${next++ % 6_000}" }

    @Test
    fun aFoldersSongsComeInTodaysOrderUpToTheLimit() = timed(60) {
        library()
        // The top: two folders, one seen twice under another, songs of its own.
        server.folders[""] = listOf("a", "b") to songsOf(30)
        server.folders["a"] = listOf("a1", "a2") to songsOf(400)
        server.folders["a1"] = emptyList<String>() to songsOf(700)
        server.folders["a2"] = listOf("a1") to songsOf(600)
        server.folders["b"] = listOf("b1") to songsOf(500)
        server.folders["b1"] = emptyList<String>() to songsOf(300)
        val reads = db.serverReads(server, drains = drains)
        val head = runBlocking { reads.read(ServerListKind.Folder, null, WorkOwners.marked("vm:1")) }!!
        val (ids, cut) = today(null)
        assertTrue(cut)
        assertTrue(head.cut)
        assertEquals(ids, runBlocking { reads.shownIds(WorkOwners.marked("vm:1")) })
        // a1 was opened once.
        assertEquals(1, server.calls.count { it == "getMusicDirectory" } - 4)
        assertEquals(emptyList<String>(), drains.listFiles().orEmpty().map { it.name })
        // A folder under the limit: all of it, not cut.
        val inner = runBlocking { reads.read(ServerListKind.Folder, "b", WorkOwners.marked("vm:2")) }!!
        assertEquals(today("b"), runBlocking { reads.shownIds(WorkOwners.marked("vm:2")) } to inner.cut)
        assertFalse(inner.cut)
    }

    @Test
    fun atMostFourHundredFoldersAreOpened() = timed(60) {
        library()
        val many = (0 until 450).map { "f$it" }
        server.folders[""] = many to emptyList()
        many.forEach { server.folders[it] = emptyList<String>() to songsOf(1) }
        val reads = db.serverReads(server, drains = drains)
        val head = runBlocking { reads.read(ServerListKind.Folder, null, WorkOwners.marked("vm:1")) }!!
        assertTrue(head.cut)
        assertEquals(SERVER_FOLDER_LIMIT, server.calls.count { it == "getMusicDirectory" })
        assertEquals(today(null).first, runBlocking { reads.shownIds(WorkOwners.marked("vm:1")) })
    }

    // Played, a folder's play answers with the cut, and an empty folder is
    // refused with today's words' cause.
    @Test
    fun aPlayCarriesTheCutAndRefusesAnEmptyFolder() = timed(60) {
        library()
        server.folders["big"] = emptyList<String>() to songsOf(2_400)
        server.folders["empty"] = emptyList<String>() to emptyList()
        val owners = WorkOwners()
        val reads = db.serverReads(server, drains = drains, owners = owners)
        val starts = QueueStarts(db, { error("no loaders") }, { error("no live") }, { error("no health") }, owners, { reads })
        val big = runBlocking { starts.read(ListSpec.ServerFolder("big"), null, false, false, null, 7L) } as StartRead.Big
        assertTrue(big.cut)
        assertEquals(today("big").first.first(), big.trackId)
        assertEquals(StartRead.Refused(NOTHING_TO_PLAY), runBlocking { starts.read(ListSpec.ServerFolder("empty"), null, false, false, null, 8L) })
        // The big play's gathered answer is its build's; with no build, it goes.
        assertEquals(1, drains.listFiles().orEmpty().size)
        starts.dropCopy(7L)
        assertEquals(emptyList<String>(), drains.listFiles().orEmpty().map { it.name })
    }
}

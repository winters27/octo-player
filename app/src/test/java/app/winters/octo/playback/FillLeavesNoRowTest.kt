package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.OWNED_WORK_TABLES
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.health.HealthCheck
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.downloadRow
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryOp
import app.winters.octo.query.QueryRule
import app.winters.octo.sort.DownloadSort
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.PhoneFile
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.phoneLibrary
import app.winters.octo.ui.library.health.HealthShown
import app.winters.octo.ui.library.health.healthLibrary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A fill leaves no row behind: every work table row its copy wrote under
// queue:<listId> goes once the list joins, and once a new play stops it
// part way, for every kind of list a fill copies. A start's own walk lets
// every row it held go too, refused or abandoned.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class FillLeavesNoRowTest {
    private var rig: QueueRig? = null

    // A queue list's fill owner as the app mints it, after this launch's mark.
    private fun queueOwner(listId: Any) = WorkOwners.marked("queue:$listId")

    @After
    fun close() {
        rig?.close()
    }

    // The rows of every owned work table that `owner` still holds, by table.
    private fun left(db: CatalogDatabase, owner: String): Map<String, Int> = OWNED_WORK_TABLES.associateWith { t ->
        db.query("SELECT COUNT(*) FROM `$t` WHERE owner = ?", arrayOf(owner)).use { it.moveToFirst(); it.getInt(0) }
    }.filterValues { it > 0 }

    // The rows the copy wrote while it was walked, so a check of none left
    // checks something.
    private fun held(db: CatalogDatabase, owner: String): Int = left(db, owner).values.sum()

    // Plays `spec` big: once to its join, and once stopped at its second
    // page by a play of another list. Answers what each left.
    private fun playBoth(rig: QueueRig, spec: ListSpec) {
        // A song of no list here, for the play that stops the fill.
        rig.db.songs(1, prefix = "x", album = "other")
        // To the join.
        var seen = 0
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page == 1) seen = held(rig.db, queueOwner(listId))
            }
        }
        rig.play(PlayRequest(spec, null, shownOverWindow = true))
        rig.joined()
        val joined = rig.lists().first { it.spec != null }.listId
        assertTrue("${spec.javaClass.simpleName}: its copy held rows while it was walked", seen > 0)
        assertEquals("${spec.javaClass.simpleName} joined", emptyMap<String, Int>(), left(rig.db, queueOwner(joined)))
        // Stopped part way.
        val reached = CompletableDeferred<Long>()
        val gate = CompletableDeferred<Unit>()
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page == 1) {
                    reached.complete(listId)
                    gate.await()
                }
            }
        }
        rig.play(PlayRequest(spec, null, shownOverWindow = true))
        rig.until("the second build is part way") { reached.isCompleted }
        val stopped = rig.run { reached.await() }
        assertTrue("${spec.javaClass.simpleName}: its copy holds rows", held(rig.db, queueOwner(stopped)) > 0)
        rig.play(PlayRequest(ListSpec.Ids(listOf("x0")), null))
        rig.until("${spec.javaClass.simpleName}: the stopped fill let its rows go") { left(rig.db, queueOwner(stopped)).isEmpty() }
        rig.builds.buildHooks = BuildHooks()
    }

    @Test
    fun aSortCopy() {
        val rig = QueueRig(page = 20).also { this.rig = it }
        rig.db.songs(120)
        playBoth(rig, ListSpec.Songs(SongScope.All, SortOrder(SongSort.Rating, true)))
    }

    @Test
    fun anAlbumWithFinds() {
        val rig = QueueRig(page = 100).also { this.rig = it }
        rig.db.songs(1_200, album = "big")
        playBoth(rig, ListSpec.Album("big", withFinds = true))
    }

    @Test
    fun aFolder() {
        val rig = QueueRig(page = 20).also { this.rig = it }
        val files = PhoneFiles(120) { i ->
            PhoneFile(
                id = i + 1L, displayName = "%04d Song.flac".format(i), relativePath = "Music/Z/",
                title = "Song $i", artist = "Band", album = "Many", albumArtist = "Band", track = i, year = 2001,
            )
        }
        val phone = rig.db.phoneLibrary(files, rig.merge)
        rig.run { phone.rescan() }
        playBoth(rig, ListSpec.Folder("", recursive = true))
    }

    @Test
    fun aSmartList() {
        val rig = QueueRig(page = 20).also { this.rig = it }
        val rock = LibraryQuery(rules = listOf(QueryRule(QueryField.Genre, QueryOp.Is, text = "Rock")))
        rig.db.songs(240, genre = { if (it % 2 == 0) "Rock" else "Jazz" })
        rig.run {
            rig.merge.start()
            rig.live.watch(WorkOwners.marked("draft:1"), null, kotlinx.coroutines.flow.flowOf(rock)).first { it is app.winters.octo.livelists.LiveShown.Shown }
        }
        playBoth(rig, ListSpec.Live(null, rock, "Rock"))
    }

    @Test
    fun aHealthList() {
        val rig = QueueRig(page = 5).also { this.rig = it }
        rig.db.healthLibrary(albums = 60)
        val shown = rig.run {
            rig.merge.start()
            rig.health.buildNow()
        } as HealthShown.Shown
        val check = shown.counts.filterKeys { it != HealthCheck.NoLength }.maxBy { it.value }.key
        playBoth(rig, ListSpec.Health(shown.gen, check))
    }

    @Test
    fun downloads() {
        val rig = QueueRig(page = 20, serverInUse = "server:h").also { this.rig = it }
        rig.db.songs(300)
        rig.run {
            rig.db.downloadDao().upsert((0 until 300).map { i -> downloadRow("s$i", serverId = "n$i", state = DownloadStatus.Done, path = "/m/$i.flac") })
        }
        playBoth(rig, ListSpec.Downloads(SortOrder(DownloadSort.Size, true)))
    }

    // A start's own walk lets every row it held go, the start refused or
    // abandoned: here a playlist's, whose start reads the set the playlist
    // shows in each statement and holds no pin while it reads.
    @Test
    fun aRefusedStartAndAnAbandonedOneLeaveNoRow() {
        val rig = QueueRig().also { this.rig = it }
        rig.db.songs(30)
        rig.run {
            // Every song of this one is gone from the library.
            rig.db.playlist("gone", listOf("gone1", "gone2"))
            rig.db.playlist("p", (0 until 30).map { "s$it" })
        }
        fun pins(owner: String) = rig.db.query("SELECT COUNT(*) FROM playlist_set_pin WHERE owner = ?", arrayOf(owner)).use { it.moveToFirst(); it.getInt(0) }
        val heldWhileRead = HashMap<String, Int>()
        var park: CompletableDeferred<Unit>? = null
        val reached = CompletableDeferred<String>()
        rig.starts.hooks = object : StartHooks() {
            override suspend fun read(owner: String) {
                heldWhileRead[owner] = pins(owner)
                park?.let { gate ->
                    reached.complete(owner)
                    gate.await()
                }
            }
        }
        // Refused: a playlist whose songs are gone plays nothing.
        val refused = rig.play(PlayRequest(ListSpec.Playlist("gone"), null, shownOverWindow = true))
        assertTrue(!refused.started)
        val first = heldWhileRead.keys.single()
        assertEquals("the refused start held no pin while it read", 0, heldWhileRead[first])
        rig.until("the refused start let go") { left(rig.db, first).isEmpty() }
        // Abandoned: the play is dropped while its start reads.
        park = CompletableDeferred()
        val job = rig.scope.launch { rig.window.play(PlayRequest(ListSpec.Playlist("p"), null, shownOverWindow = true)) }
        rig.until("the start reads") { reached.isCompleted }
        val second = rig.run { reached.await() }
        assertEquals("the abandoned start held no pin while it read", 0, heldWhileRead[second])
        job.cancel()
        rig.until("the abandoned start let go") { left(rig.db, second).isEmpty() }
        assertTrue(rig.held().isEmpty())
    }

    // A list the server answers for, read into the fill's own copy.
    @Test
    fun anOnlineList() {
        val songs = (0 until 800).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist ${it % 5}") }
        NetworkQueueRig(songs).use { net ->
            net.db.putLibrary((0 until 800 step 2).map { libraryTrack("t$it", "Title $it", "Artist ${it % 5}") }) { "srv${it.id.removePrefix("t")}" }
            net.server.lists["L"] = (0 until 800).map { "srv$it" }
            val spec = ListSpec.Online(OnlineKind.List, "L")
            fun owned(owner: String) = OWNED_WORK_TABLES.sumOf { t -> net.count("SELECT COUNT(*) FROM `$t` WHERE owner = '$owner'") }
            var seen = 0
            net.builds.buildHooks = object : BuildHooks() {
                override suspend fun beforePage(listId: Long, page: Int) {
                    if (page == 1) seen = owned(queueOwner(listId))
                }
            }
            assertTrue(net.play(PlayRequest(spec, null, shownOverWindow = true)).started)
            net.joined()
            val joined = kotlinx.coroutines.runBlocking { net.store.inTurn { net.store.dao.listsOf(net.window.queueId) } }.first { it.spec != null }.listId
            assertTrue("the copy held rows while it was walked", seen > 0)
            assertEquals(0, owned(queueOwner(joined)))
            val reached = CompletableDeferred<Long>()
            val gate = CompletableDeferred<Unit>()
            net.builds.buildHooks = object : BuildHooks() {
                override suspend fun beforePage(listId: Long, page: Int) {
                    if (page == 1) {
                        reached.complete(listId)
                        gate.await()
                    }
                }
            }
            assertTrue(net.play(PlayRequest(spec, null, shownOverWindow = true)).started)
            net.until("the second build is part way") { reached.isCompleted }
            val stopped = kotlinx.coroutines.runBlocking { reached.await() }
            assertTrue(owned(queueOwner(stopped)) > 0)
            assertTrue(net.play(PlayRequest(ListSpec.Ids(listOf("t0")), null)).started)
            net.until("the stopped fill let its rows go") { owned(queueOwner(stopped)) == 0 }
        }
    }
}

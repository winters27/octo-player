package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.deleteOwned
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.today.todayResolve
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A build never reads a screen's work table: it makes its own copy under
// its own owner. The album page's copy cleared and built again mid-build
// leaves the queue whole, in order; so does a server list's page clearing
// its read and reading anew. Labeled guard for the server list: the base
// held the whole list of ids.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class FillOverWorkTableTest {
    private val rig = QueueRig(page = 100)

    @After
    fun close() = rig.close()

    @Test
    fun theScreensCopyGoingMidBuildCutsNothing() {
        val songs = rig.db.songs(3_000, album = "big").map { it.id }
        val page = WorkOwners.marked("vm:77")
        rig.run { rig.loaders.buildAlbum(page, "big") }
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page == 7) {
                    rig.db.deleteOwned("lists_album_entry", WorkOwners.marked("vm:77"))
                    rig.loaders.buildAlbum(WorkOwners.marked("vm:77"), "big")
                    rig.db.deleteOwned("lists_album_entry", WorkOwners.marked("vm:77"))
                }
            }
        }
        rig.play(PlayRequest(ListSpec.Album("big", withFinds = true), null, pageOwner = page, shownOverWindow = true))
        rig.joined()
        assertEquals(songs, rig.songs())
    }

    // A list the server answers for: the build copies the page's whole read
    // into its own owner before its first read, so the page clearing its
    // rows and reading the list anew mid-build cuts nothing.
    @Test
    fun aServerListsPageLeavingMidBuildCutsNothing() {
        val songs = (0 until 3_000).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist ${it % 5}") }
        NetworkQueueRig(songs).use { net ->
            net.db.putLibrary((0 until 3_000 step 2).map { libraryTrack("t$it", "Title $it", "Artist ${it % 5}") }) { "srv${it.id.removePrefix("t")}" }
            val entries = (0 until 3_000).map { "srv${(it * 7) % 3_000}" }
            net.server.lists["L"] = entries
            val today = todayResolve(net.db, SERVER_SOURCE, entries.map { songs.getValue(it) }).map { it.id }
            runBlocking { net.reads.read(ServerListKind.List, "L", WorkOwners.marked("vm:1")) }
            net.builds.buildHooks = object : BuildHooks() {
                override suspend fun beforePage(listId: Long, page: Int) {
                    if (page == 7) {
                        net.reads.clear(WorkOwners.marked("vm:1"))
                        net.reads.read(ServerListKind.List, "L", WorkOwners.marked("vm:2"))
                    }
                }
            }
            assertTrue(net.play(PlayRequest(ListSpec.Online(OnlineKind.List, "L"), null, shownOverWindow = true)).started)
            net.joined()
            assertEquals(today, net.songs())
            assertEquals(0, net.count("SELECT COUNT(*) FROM server_entry WHERE owner = ?", WorkOwners.marked("vm:1")))
        }
    }
}

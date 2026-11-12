package app.winters.octo.discovery

import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.listening.timed
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A server list is resolved once, outside every lock: a swap that renames
// songs between two resolution pages starts nothing again; the relinks
// move the ids already written in the page's owner and in a fill's copy,
// the read completes with the count it began at, and the read never holds
// the merge lock while it fills or resolves.
@RunWith(RobolectricTestRunner::class)
class ResolveOnceSwapTest {
    private val db = openTestCatalog()
    private val songs = (0 until 600).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist") }
    private val server = ListServer(songs)

    @After
    fun close() {
        server.close()
        db.close()
    }

    @Test
    fun aSwapBetweenResolutionPagesIsFollowed() = timed(60) {
        // Half the songs come by server id, half by title only.
        db.putLibrary((0 until 600).map { libraryTrack("t$it", "Title $it", "Artist", 0) }) { t ->
            t.id.removePrefix("t").toInt().takeIf { it % 2 == 0 }?.let { "srv$it" }
        }
        server.lists["L"] = (0 until 600).map { "srv$it" }
        val merge: CatalogMerge = db.testMerge(SERVER_SOURCE)
        val reads = db.serverReads(server, merge = merge)
        // A copy a fill made before the swap.
        runBlocking { reads.read(ServerListKind.List, "L", WorkOwners.marked("queue:3")) }
        val before = runBlocking { db.workDao().swapCount() }
        var renamed = false
        var heldDuring = false
        reads.hooks = object : ServerHooks() {
            override suspend fun filled(owner: String, pages: Int) {
                heldDuring = heldDuring || merge.holdsMergeLock()
            }

            override suspend fun resolved(owner: String, pages: Int) {
                heldDuring = heldDuring || merge.holdsMergeLock()
                // Linked songs early in the list were resolved by now.
                if (owner == WorkOwners.marked("vm:1") && pages == 2 && !renamed) {
                    db.renameSong("t0", "t0-new")
                    db.renameSong("t2", "t2-new")
                    renamed = true
                }
            }
        }
        val head = runBlocking { reads.read(ServerListKind.List, "L", WorkOwners.marked("vm:1")) }!!
        assertTrue(renamed)
        assertFalse("the read held the merge lock", heldDuring)
        assertEquals(2, server.calls.count { it == "getPlaylist" })
        // Dated by the count it began at.
        assertEquals(before, head.swapCount)
        for (owner in listOf(WorkOwners.marked("vm:1"), WorkOwners.marked("queue:3"))) {
            val shown = runBlocking { reads.shownIds(owner) }
            assertEquals(owner, 600, shown.size)
            assertTrue(owner, "t0-new" in shown && "t2-new" in shown)
            assertFalse(owner, "t0" in shown || "t2" in shown)
        }
    }
}

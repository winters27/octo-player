package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.room.withTransaction
import app.winters.octo.catalog.LaunchMark
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.read
import app.winters.octo.catalog.readLong
import app.winters.octo.listening.timed
import app.winters.octo.livelists.LiveHooks
import app.winters.octo.livelists.liveLibrary
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

// A car's live list is changed in place after each change while no answer
// holds it, and walked into a new generation while one does: a generation
// an answer holds (from the car's pin until it lets go) never changes, an
// answer that takes hold part way through a walk in place stops the walk
// changing it, and a generation goes with its last pin, or at the next open
// when the app was stopped first.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class CarLiveGenerationTest {
    private val rig = CarRig(budget = 8 * 1024)

    @After
    fun close() = rig.close()

    // Waits until every write so far has reached Room's observers.
    private suspend fun settled(rig: CarRig = this.rig) = coroutineScope {
        val seen = MutableStateFlow(0)
        val watching = launch { rig.db.invalidationTracker.createFlow("playlist").collect { seen.value += 1 } }
        seen.first { it >= 1 }
        rig.db.userDao().insertPlaylist(PlaylistEntity("settle", "Settle", 1, 1))
        seen.first { it >= 2 }
        watching.cancel()
    }

    // Every song the car shows for the list, part by part down to its songs.
    private suspend fun shown(node: CarNode, rig: CarRig = this.rig): List<String> {
        val songs = ArrayList<String>()
        for (item in rig.answer(node)) {
            val part = parseCarId(item.mediaId) as? CarNode.Part
            if (part != null) songs += shown(part, rig) else songs += item.mediaId
        }
        return songs
    }

    // A generation's songs in the list's order.
    private fun songsOf(rig: CarRig, owner: String, gen: Long): List<String> =
        rig.db.read("SELECT trackId FROM live_song WHERE owner = ? AND gen = ? ORDER BY orderKey", owner, gen) { it.getString(0) }

    // Songs move in the list's order, its count and length the same.
    private suspend fun moveSongs(rig: CarRig) = rig.db.withTransaction {
        rig.db.openHelper.writableDatabase.execSQL("UPDATE track SET title = 'Aaa ' || title, sortKey = 'aaa ' || sortKey WHERE rowid % 3 = 0")
    }

    // The car's walks of its list: each published generation, the car's
    // owner, and points to hold the second walk after its first page at.
    private class Walks(val rig: CarRig, holdSecond: Boolean) {
        val gens: MutableList<Long> = Collections.synchronizedList(ArrayList())
        @Volatile var owner = ""
        val second = CompletableDeferred<Unit>()
        val partWay = CompletableDeferred<Unit>()
        val go = CompletableDeferred<Unit>()
        val most = AtomicLong()

        init {
            if (!holdSecond) go.complete(Unit)
            rig.live.hooks = object : LiveHooks() {
                override suspend fun page(owner: String, n: Int) {
                    if (WorkOwners.kindOf(owner) != "car" || gens.size != 1) return
                    most.accumulateAndGet(rig.db.readLong("SELECT COUNT(*) FROM live_song WHERE owner = ?", owner), ::maxOf)
                    if (n == 1) {
                        partWay.complete(Unit)
                        go.await()
                    }
                }

                override suspend fun published(owner: String, gen: Long) {
                    if (WorkOwners.kindOf(owner) != "car") return
                    this@Walks.owner = owner
                    gens += gen
                    if (gens.size == 2) second.complete(Unit)
                }
            }
        }
    }

    private fun showList(rig: CarRig): CarNode.LiveList {
        rig.liveLists.value = mapOf("x" to LibraryQuery(sort = QuerySort(SongSort.Title.id)))
        return CarNode.LiveList("x")
    }

    @Test
    fun aChangeWithNoAnswerHoldingTheListChangesItInPlace() = timed(120) {
        rig.db.liveLibrary(600)
        settled()
        val walks = Walks(rig, holdSecond = false)
        val node = showList(rig)
        assertTrue("the list is sent in parts", rig.answer(node).all { parseCarId(it.mediaId) is CarNode.Part })
        val before = shown(node)
        assertTrue("${before.size} songs", before.size > 500)
        moveSongs(rig)
        walks.second.await()
        assertEquals("the walk changed the shown generation in place", walks.gens[0], walks.gens[1])
        assertTrue("the walk's pages were counted", walks.most.get() > 0)
        assertTrue("no second generation is held: ${walks.most.get()} rows", walks.most.get() <= before.size)
        val after = shown(node)
        assertNotEquals("the answer after the walk reads the new list", before, after)
        assertEquals(before.toSet(), after.toSet())
        assertEquals(before.size.toLong(), rig.db.readLong("SELECT COUNT(*) FROM live_song WHERE owner = ?", walks.owner))
    }

    @Test
    fun anAnswerTakingHoldPartWayKeepsItsGenerationAsItWas() = timed(120) {
        rig.db.liveLibrary(600)
        settled()
        val walks = Walks(rig, holdSecond = true)
        val node = showList(rig)
        val before = shown(node)
        moveSongs(rig)
        // The walk has changed its first page in place and waits: an answer
        // takes hold of the list now.
        walks.partWay.await()
        val pin = checkNotNull(rig.liveSongs.pinShown(walks.owner))
        assertEquals(walks.gens[0], pin.gen)
        val held = songsOf(rig, walks.owner, pin.gen)
        assertEquals(before.size, held.size)
        walks.go.complete(Unit)
        walks.second.await()
        assertNotEquals("the walk went on in a generation of its own", pin.gen, walks.gens[1])
        assertEquals("the held generation never changed", held, songsOf(rig, walks.owner, pin.gen))
        rig.liveSongs.unpinShown(walks.owner, pin.gen)
        assertEquals("the old generation goes with its last pin", 0L, rig.db.readLong("SELECT COUNT(*) FROM live_song WHERE owner = ? AND gen = ?", walks.owner, pin.gen))
        val after = shown(node)
        assertNotEquals("the answer after the walk reads the new list", before, after)
        assertEquals(before.toSet(), after.toSet())
    }

    @Test
    fun aWalkWhileAnAnswerHoldsTheListBuildsANewGeneration() = timed(120) {
        rig.db.liveLibrary(600)
        settled()
        val walks = Walks(rig, holdSecond = false)
        val node = showList(rig)
        val before = shown(node)
        val pin = checkNotNull(rig.liveSongs.pinShown(walks.owner))
        val second = checkNotNull(rig.liveSongs.pinShown(walks.owner))
        val held = songsOf(rig, walks.owner, pin.gen)
        moveSongs(rig)
        walks.second.await()
        assertNotEquals("the walk built a generation of its own", pin.gen, walks.gens[1])
        assertEquals("the held generation never changed", held, songsOf(rig, walks.owner, pin.gen))
        assertEquals("both generations are kept while one is held", before.size * 2L, rig.db.readLong("SELECT COUNT(*) FROM live_song WHERE owner = ?", walks.owner))
        rig.liveSongs.unpinShown(walks.owner, pin.gen)
        assertEquals("a generation still pinned stays", held, songsOf(rig, walks.owner, pin.gen))
        rig.liveSongs.unpinShown(walks.owner, second.gen)
        assertEquals("the old generation goes with its last pin", 0L, rig.db.readLong("SELECT COUNT(*) FROM live_song WHERE owner = ? AND gen = ?", walks.owner, pin.gen))
        assertEquals(before.size.toLong(), rig.db.readLong("SELECT COUNT(*) FROM live_song WHERE owner = ?", walks.owner))
    }

    // The app stops while an answer holds the old generation: nothing lets
    // it go, the next launch's open keeps it, and that launch's sweep takes
    // it.
    @Test
    fun aGenerationLeftWhenTheAppStopsGoesAtTheNextOpen() = timed(120) {
        val dir = Files.createTempDirectory("carkill").toFile()
        val stopped = CarRig(db = openTestCatalog(dir), budget = 8 * 1024)
        stopped.db.liveLibrary(600)
        settled(stopped)
        val walks = Walks(stopped, holdSecond = false)
        val node = showList(stopped)
        val before = shown(node, stopped)
        checkNotNull(stopped.liveSongs.pinShown(walks.owner))
        moveSongs(stopped)
        walks.second.await()
        stopped.scope.coroutineContext[Job]!!.cancelAndJoin()
        assertEquals("both generations are in the file", before.size * 2L, stopped.db.readLong("SELECT COUNT(*) FROM live_song WHERE owner = ?", walks.owner))
        stopped.db.close()
        stopped.drains.deleteRecursively()
        LaunchMark.next()
        val reopened = openTestCatalog(dir)
        try {
            assertEquals("the open kept both generations", before.size * 2L, reopened.readLong("SELECT COUNT(*) FROM live_song WHERE owner = ?", walks.owner))
            PlaylistSets(reopened).let { it.sweep(); it.idle() }
            assertEquals("the launch's sweep took every generation", 0L, reopened.readLong("SELECT COUNT(*) FROM live_song"))
        } finally {
            reopened.close()
            dir.deleteRecursively()
        }
    }
}

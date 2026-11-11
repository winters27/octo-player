package app.winters.octo.ui.paging

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.Gate
import app.winters.octo.listening.timed
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.Presented
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The count a list shows is the one its rows were read with: a like that
// lands between a refresh's count and its rows makes it count again (or,
// past its tries, show the rows' own count), so every generation on screen
// has as many places as its count says, before and after the like's own
// generation.
@RunWith(RobolectricTestRunner::class)
class CountGenerationTest {
    private val db = openTestCatalog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        // The list's shared pages stop before the database closes.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    // The Liked songs, held at a gate inside a page read once armed.
    private class Held(private val inner: ListLoader<TrackEntity>) : ListLoader<TrackEntity> by inner {
        @Volatile var gate: Gate? = null

        override suspend fun after(key: RowKey?, limit: Int): List<Keyed<TrackEntity>> {
            gate?.let {
                gate = null
                it.hit()
            }
            return inner.after(key, limit)
        }
    }

    private suspend fun likeDuringARefresh(songs: Int) {
        val tracks = db.edgeSongs(songs)
        val unliked = tracks.filterIndexed { i, _ -> i % 3 != 0 }
        val held = Held(SongLists(db.listsDao()).loader(SongScope.Liked, SortOrder(SongSort.Title, false)))
        val handle = ListHandle<Int, TrackEntity>(scope, 0, { held }, ListReads.Plain, { _, loader -> db.tableChanges(loader.tables) })
        Presented(handle.pages, scope).use { shown ->
            shown.settle()
            val liked = (songs + 2) / 3
            assertEquals(liked, shown.size())
            assertEquals(liked, handle.metaFor(handle.presented)!!.count)

            val gate = Gate()
            held.gate = gate
            val before = handle.generationNow
            handle.refresh()
            gate.reached.await()
            // A like between the refresh's count and its rows.
            db.write("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES (?, '', 99)", unliked.first().id)
            gate.open()
            shown.settle(until = { handle.presented > before })
            assertEquals("the generation's count is its places", handle.metaFor(handle.presented)!!.count, shown.size())
            shown.settle(until = { handle.presented == handle.generationNow })
            assertEquals(liked + 1, shown.size())
            assertEquals(liked + 1, handle.metaFor(handle.presented)!!.count)
        }
    }

    @Test
    fun aListReadToItsEndCountsAgain() = timed(30) { likeDuringARefresh(200) }

    @Test
    fun aLongListShowsTheCountItsRowsWereReadWith() = timed(30) { likeDuringARefresh(1_500) }
}

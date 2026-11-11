package app.winters.octo.ui.favourites

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.Gate
import app.winters.octo.listening.timed
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.Presented
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.HiddenRows
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.ListReads
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SongLists
import app.winters.octo.ui.paging.tableChanges
import app.winters.octo.ui.paging.write
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A song swiped off Liked songs stays hidden until a generation of the list
// begun after its unlike committed is on screen: an unlike that lands while
// a refresh reads leaves that generation holding the song, and it stays
// hidden there; the next generation has it gone, and nothing is hidden.
@RunWith(RobolectricTestRunner::class)
class LikedHiddenRaceTest {
    private val db = openTestCatalog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        // The list's shared pages stop before the database closes.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    // The Liked songs, held at a gate after a page read once armed: that
    // generation's rows are read before whatever the gate lets in.
    private class Held(private val inner: ListLoader<TrackEntity>) : ListLoader<TrackEntity> by inner {
        @Volatile var gate: Gate? = null

        override suspend fun after(key: RowKey?, limit: Int): List<Keyed<TrackEntity>> {
            val rows = inner.after(key, limit)
            gate?.let {
                gate = null
                it.hit()
            }
            return rows
        }
    }

    @Test
    fun anUnlikeDuringARefreshStaysHiddenUntilTheNextGeneration() = timed(30) {
        val tracks = (0 until 30).map { i ->
            TrackEntity(
                id = "device:$i", sourceId = "device", nativeId = "$i", title = "Song $i", searchKey = "song $i", sortKey = "song %02d".format(i),
                artist = "Band", artistId = "ar", album = "", albumId = "al", trackNo = null, discNo = null, year = null, durationMs = 0,
                addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
            )
        }
        db.dao().insertTracks(tracks)
        tracks.forEach { db.openHelper.writableDatabase.execSQL("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES (?, '', 1)", arrayOf<Any?>(it.id)) }
        val held = Held(SongLists(db.listsDao()).loader(SongScope.Liked, SortOrder(SongSort.Title, false)))
        val handle = ListHandle<Int, TrackEntity>(scope, 0, { held }, ListReads.Plain, { _, loader -> db.tableChanges(loader.tables) })
        val rows = HiddenRows()
        val hidden = rows.among(handle.shown).stateIn(scope, SharingStarted.Eagerly, emptySet())
        Presented(handle.pages, scope).use { shown ->
            shown.settle()
            assertEquals(30, shown.size())

            // A refresh reads its rows, then the unlike commits.
            val gate = Gate()
            held.gate = gate
            val before = handle.generationNow
            handle.refresh()
            gate.reached.await()
            val racing = handle.generationNow
            assertTrue(racing > before)
            rows.hide("device:7")
            db.write("DELETE FROM liked_track WHERE trackId = 'device:7'")
            rows.committed("device:7", handle.generationNow)
            // The unlike's own generation waits at the gate too, so the
            // racing one stays on screen to look at.
            val next = Gate()
            held.gate = next
            gate.open()

            withTimeout(10_000) { while (handle.presented < racing) delay(10) }
            next.reached.await()
            assertEquals(racing, handle.presented)
            assertTrue("the racing generation still holds the song", shown.loaded().any { it.row.id == "device:7" && it.generation == racing })
            assertEquals("so it stays hidden", setOf("device:7"), withTimeout(5_000) { hidden.first { it.isNotEmpty() } })

            next.open()
            shown.settle(until = { handle.presented > racing && handle.presented == handle.generationNow })
            assertEquals(29, shown.size())
            assertTrue((0 until 29).none { shown.get(it)?.row?.id == "device:7" })
            assertEquals("nothing hidden once it is gone", emptySet<String>(), hidden.first { it.isEmpty() })
        }
    }
}

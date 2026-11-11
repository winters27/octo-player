package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.timed
import app.winters.octo.livelists.LiveHooks
import app.winters.octo.livelists.liveLibrary
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

// A live list the car reads is kept under the car's own owner and brought
// up to date by walks of its own, as a page's is: the first answer waits
// for one walk, a second answer walks nothing, and a change to the library
// walks it once. Letting the car's list go stops its walks and its rows go.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class CarLiveListTest {
    private val rig = CarRig()

    @After
    fun close() = rig.close()

    private fun song(id: String, title: String) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = "Band", artistId = "ar", album = "A", albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = 1_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    private fun rows(owner: String) = rig.db.query("SELECT COUNT(*) FROM live_song WHERE owner = ?", arrayOf(owner)).use { it.moveToFirst(); it.getInt(0) }

    // Waits until every write so far has reached Room's observers: one more
    // write, to a table no live list follows, seen by an observer, comes
    // after them all.
    private suspend fun settled() = coroutineScope {
        val seen = MutableStateFlow(0)
        val watching = launch { rig.db.invalidationTracker.createFlow("playlist").collect { seen.value += 1 } }
        seen.first { it >= 1 }
        rig.db.userDao().insertPlaylist(PlaylistEntity("settle", "Settle", 1, 1))
        seen.first { it >= 2 }
        watching.cancel()
    }

    @Test
    fun twoAnswersOfALiveListWalkItOnceAndAChangeWalksItOnceMore() = timed(60) {
        runBlocking {
            rig.db.liveLibrary(80)
            settled()
        }
        val walks = AtomicInteger()
        rig.live.hooks = object : LiveHooks() {
            override suspend fun published(owner: String, gen: Long) {
                if (WorkOwners.kindOf(owner) == "car") walks.incrementAndGet()
            }
        }
        rig.liveLists.value = mapOf("x" to LibraryQuery(sort = QuerySort(SongSort.Title.id)))
        val node = CarNode.LiveList("x")
        val first = runBlocking { rig.answer(node) }
        assertEquals(80, first.size)
        assertEquals(1, walks.get())
        val second = runBlocking { rig.answer(node) }
        assertEquals("a second answer walks nothing", 1, walks.get())
        assertEquals(first.map { it.mediaId }, second.map { it.mediaId })
        // A song joins the library: the car's copy walks once more.
        runBlocking { rig.db.dao().insertTracks(listOf(song("new", "Aaa first"))) }
        runBlocking { withTimeout(10_000) { while (walks.get() < 2) delay(20) } }
        val third = runBlocking { rig.answer(node) }
        assertEquals(81, third.size)
        assertTrue(third.any { it.mediaId.contains("new") })
        assertEquals("one walk for one change, none for the answer", 2, walks.get())
        // Let go: its walks stop and its rows go.
        val owner = runBlocking { rig.lists.keptFor<CarOwned<CarLive>>(node) }!!.owner
        assertTrue(rows(owner) > 0)
        runBlocking {
            rig.lists.clear()
            withTimeout(10_000) { while (rows(owner) > 0) delay(20) }
            rig.db.dao().insertTracks(listOf(song("later", "Aab")))
        }
        assertEquals(0, rows(owner))
        assertEquals(2, walks.get())
    }
}

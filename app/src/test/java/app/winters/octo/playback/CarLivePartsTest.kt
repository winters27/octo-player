package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.timed
import app.winters.octo.livelists.LiveHooks
import app.winters.octo.livelists.PINNED_COUNT_SQL
import app.winters.octo.livelists.liveLibrary
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.testing.Counted
import app.winters.octo.testing.counted
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

// A live list too long for one car answer is sent as parts, which one pass
// over the car's copy of it cuts. The parts are kept while the copy stands
// as it is, so a second answer of the list reads only what fits one answer
// before it knows the list does not fit, and a part's answer reads only its
// own rows, counted at the database. A change that walks the copy again
// has the next answer cut its parts again.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class CarLivePartsTest {
    private val rig = CarRig(budget = 8 * 1024)

    @After
    fun close() = rig.close()

    private fun song(id: String, title: String) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = "Band", artistId = "ar", album = "A", albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = 1_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    // Rows the car's copy of the list gave its readers (the count of the
    // generation an answer holds is one row of the list's state).
    private fun copyRows(ran: List<Counted>): Int = ran.filter { "FROM live_song" in it.sql && it.sql.trim() != PINNED_COUNT_SQL }.sumOf { it.rowsRead }

    private fun answered(node: CarNode) = rig.db.counted.record { runBlocking { rig.answer(node) } }

    // Waits until every write so far has reached Room's observers.
    private suspend fun settled() = coroutineScope {
        val seen = MutableStateFlow(0)
        val watching = launch { rig.db.invalidationTracker.createFlow("playlist").collect { seen.value += 1 } }
        seen.first { it >= 1 }
        rig.db.userDao().insertPlaylist(PlaylistEntity("settle", "Settle", 1, 1))
        seen.first { it >= 2 }
        watching.cancel()
    }

    @Test
    fun aSecondAnswerOfALongLiveListCutsNothingAgain() = timed(120) {
        rig.db.liveLibrary(600)
        settled()
        val walks = AtomicInteger()
        rig.live.hooks = object : LiveHooks() {
            override suspend fun published(owner: String, gen: Long) {
                if (WorkOwners.kindOf(owner) == "car") walks.incrementAndGet()
            }
        }
        rig.liveLists.value = mapOf("x" to LibraryQuery(sort = QuerySort(SongSort.Title.id)))
        val node = CarNode.LiveList("x")
        val (first, firstRan) = answered(node)
        assertTrue("$first", first.isNotEmpty() && first.all { parseCarId(it.mediaId) is CarNode.Part })
        val songs = rig.db.query("SELECT COUNT(*) FROM live_song WHERE owner LIKE ?", arrayOf(WorkOwners.marked("car:%"))).use { it.moveToFirst(); it.getInt(0) }
        assertTrue("the first answer read ${copyRows(firstRan)} rows of $songs", songs > 2 * CAR_PAGE && copyRows(firstRan) >= songs)
        val (second, secondRan) = answered(node)
        assertEquals(first.map { it.mediaId }, second.map { it.mediaId })
        assertTrue("the second answer read nothing of the copy", copyRows(secondRan) > 0)
        assertTrue("the second answer read ${copyRows(secondRan)} rows of $songs", copyRows(secondRan) <= CAR_PAGE)
        // A part's answer, down to a part of songs: its own rows at most,
        // read from where it starts.
        var part = parseCarId(second.last().mediaId) as CarNode.Part
        while (true) {
            val (items, partRan) = answered(part)
            assertTrue("$part answered nothing", items.isNotEmpty())
            assertTrue("$part read ${copyRows(partRan)} rows of $songs", copyRows(partRan) <= 2 * CAR_PAGE)
            part = parseCarId(items.last().mediaId) as? CarNode.Part ?: break
        }
        assertEquals(1, walks.get())
        // A song joins the library: the copy walks once more, and the next
        // answer cuts its parts from the copy as it is now.
        rig.db.dao().insertTracks(listOf(song("new", "Aaa first")))
        withTimeout(10_000) { while (walks.get() < 2) delay(20) }
        val (third, thirdRan) = answered(node)
        assertTrue("the answer after a walk read ${copyRows(thirdRan)} rows of ${songs + 1}", copyRows(thirdRan) >= songs + 1)
        assertTrue(third.all { parseCarId(it.mediaId) is CarNode.Part })
    }
}

package app.winters.octo.catalog

import app.winters.octo.listening.timed
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.paging.SongLists
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A server edit that changes only a genre's case is written: the genre
// column ignores case for lists, but the swap compares every column byte
// for byte, so "Rock" becoming "ROCK" reaches the library, the Genres list
// shows the new spelling, and the genre's page still holds every song.
@RunWith(RobolectricTestRunner::class)
class GenreCaseOnlySwapTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private val server = "server:home"

    private fun source(genre: String) = SourceCatalog(
        server,
        false,
        (0 until 40).map { song(server, "s$it", "Song $it", "album${it / 10}", no = it % 10 + 1, order = it % 10).copy(genre = if (it % 2 == 0) genre else "Jazz") },
        (0 until 4).map { albumRow(server, "album$it", "Album $it") },
        listOf(artistRow(server)),
    )

    private fun genres() = db.query("SELECT DISTINCT genre FROM track ORDER BY genre COLLATE BINARY", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    @Test
    fun aCaseOnlyEditReachesTheLibraryAndTheGenres() = timed(60) {
        val merge = db.testMerge(server)
        db.put(source("Rock"))
        merge.rebuild()
        assertEquals(listOf("Jazz", "Rock"), genres())

        db.put(source("ROCK"))
        merge.rebuild()
        assertEquals(listOf("Jazz", "ROCK"), genres())
        assertEquals(listOf("Jazz", "ROCK"), db.dao().genres().first().map { it.name })
        assertEquals(20, db.dao().genres().first().first { it.name == "ROCK" }.songCount)
        assertEquals(20, SongLists(db.listsDao()).loader(SongScope.Genre("rock"), SortOrder(SongSort.Title, false)).count())
    }
}

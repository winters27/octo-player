package app.winters.octo.livelists

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.listening.timed
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryMatch
import app.winters.octo.query.QueryOp
import app.winters.octo.query.QueryRule
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A labeled pin. A live list kept in the database shows today's songs, in
// today's order, with today's count and length: every song order both
// ways, limits of 25 and 500 and none, rules on plays, last plays, likes,
// dates, genres, ratings and typed words, all or any of them. Read a page
// of 7 at a time, built in pages of 11; no statement reads more than 900
// rows.
@RunWith(RobolectricTestRunner::class)
class LiveListParityTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private val rules = listOf(
        listOf(QueryRule(QueryField.Plays, QueryOp.AtLeast, number = 2)),
        listOf(QueryRule(QueryField.LastPlayed, QueryOp.InTheLast, days = 400)),
        listOf(QueryRule(QueryField.Favourite, QueryOp.Is, flag = true)),
        listOf(QueryRule(QueryField.Added, QueryOp.After, at = 1_705_000_000_000L)),
        listOf(QueryRule(QueryField.Genre, QueryOp.Contains, text = "ROCK"), QueryRule(QueryField.Rating, QueryOp.AtLeast, number = 3)),
        listOf(QueryRule(QueryField.Year, QueryOp.Between, number = 1980, to = 2000)),
        listOf(QueryRule(QueryField.LastPlayed, QueryOp.Never)),
    )

    private fun queries(): List<LibraryQuery> {
        val sorted = SongSort.entries.flatMap { sort ->
            listOf(false, true).map { LibraryQuery(sort = QuerySort(sort.id, it)) }
        }
        val limited = listOf(SongSort.Title, SongSort.MostPlayed, SongSort.FolderOrder, SongSort.Year).flatMap { sort ->
            listOf(25, 500).map { LibraryQuery(sort = QuerySort(sort.id, sort == SongSort.MostPlayed || sort == SongSort.FolderOrder), limit = it) }
        }
        val ruled = rules.map { LibraryQuery(rules = it) } +
            LibraryQuery(rules = rules[0] + rules[2], match = QueryMatch.Any, sort = QuerySort(SongSort.RecentlyPlayed.id, true)) +
            LibraryQuery(text = "song band", sort = QuerySort(SongSort.Artist.id)) +
            LibraryQuery(rules = rules[1], limit = 25)
        return listOf(LibraryQuery(), LibraryQuery(limit = 25)) + sorted + limited + ruled
    }

    @Test
    fun everyListIsTodays() = timed(240) {
        db.liveLibrary(400)
        val rig = LiveRig(db, page = 11).started()
        db.counted.reset()
        coroutineScope {
            queries().forEachIndexed { n, query ->
                val watching = Watching(this, rig.picks, WorkOwners.marked("live:$n"), "$n", MutableStateFlow(query))
                val shown = watching.after(0) as LiveShown.Shown
                val today = rig.today(query)
                assertEquals("$query", today.map { it.id }, rig.rows(WorkOwners.marked("live:$n")))
                assertEquals("$query", today.size, shown.count)
                assertEquals("$query", today.sumOf { it.durationMs }, shown.durationMs)
                watching.job.cancel()
            }
        }
        assertTrue("${db.counted.maxRead}", db.counted.maxRead!!.rowsRead <= 900)
    }
}

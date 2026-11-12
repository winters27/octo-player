package app.winters.octo.livelists

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.listening.NOW
import app.winters.octo.listening.playOn
import app.winters.octo.listening.timed
import app.winters.octo.playback.likeAll
import app.winters.octo.playback.putTracks
import app.winters.octo.playback.radioTrack
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A change of the library, a like or a play changes a shown list in place:
// the same generation, its songs, count and length today's after each
// change, the walk merging the library's ids with the list's in the order
// the database keeps them (ids with a replacement character and an emoji,
// where Kotlin orders them otherwise). A list with a limit and one with
// none. Guard: the base worked the whole list out again each time.
@RunWith(RobolectricTestRunner::class)
class LiveListDiffTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun changesAreDiffedIntoTheShownList() = timed(120) {
        val tracks = db.liveLibrary(200)
        val rig = LiveRig(db, page = 13).started()
        val queries = mapOf(
            WorkOwners.marked("live:a") to LibraryQuery(sort = QuerySort(SongSort.MostPlayed.id, true)),
            WorkOwners.marked("live:b") to LibraryQuery(sort = QuerySort(SongSort.Title.id), limit = 25),
        )
        coroutineScope {
            val watching = queries.mapValues { (owner, query) -> Watching(this, rig.picks, owner, owner.substringAfter("live:"), MutableStateFlow(query)) }
            val gens = watching.mapValues { (_, w) -> (w.after(0) as LiveShown.Shown).gen }

            suspend fun settled(step: String) {
                for ((owner, w) in watching) {
                    val today = rig.today(queries.getValue(owner))
                    val shown = w.until { s ->
                        s is LiveShown.Shown && s.durationMs == today.sumOf { it.durationMs } && rig.rows(owner) == today.map { it.id }
                    } as LiveShown.Shown
                    assertEquals("$step: $owner stays in its generation", gens.getValue(owner), shown.gen)
                    assertEquals("$step: $owner", today.size, shown.count)
                    assertEquals("$step: $owner", today.sumOf { it.durationMs }, shown.durationMs)
                }
            }

            db.putTracks(listOf(radioTrack("t�new", sortKey = "k0001", title = "A new one"), radioTrack("t😀new", sortKey = "k0002", title = "Aa new")))
            settled("songs added")
            db.change("DELETE FROM track WHERE id IN (?, ?)", tracks[7].id, tracks[8].id)
            settled("songs gone")
            db.change("UPDATE track SET title = 'Aaa moved', durationMs = 1234 WHERE id = ?", tracks[30].id)
            settled("a song renamed and shortened")
            repeat(9) { db.playOn(tracks[40].id, NOW - it * 1_000L) }
            rig.totals.totals.refresh(listOf(tracks[40].id))
            settled("a song played")
            db.likeAll(listOf(tracks[41].id))
            settled("a song liked")
            watching.values.forEach { it.job.cancel() }
        }
    }
}

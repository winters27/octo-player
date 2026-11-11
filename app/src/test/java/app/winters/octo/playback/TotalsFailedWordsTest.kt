package app.winters.octo.playback

import android.database.sqlite.SQLiteFullException
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.listening.NOW
import app.winters.octo.listening.TotalsFailed
import app.winters.octo.listening.TotalsHooks
import app.winters.octo.listening.playOn
import app.winters.octo.listening.timed
import app.winters.octo.livelists.liveLibrary
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// While the play counts cannot be brought up to date, nothing that reads
// them waits: a car list sorted by plays and a car live list whose rules
// read plays each answer the counts' worded problem at once, which the car
// is told with the counts asked to build again; Autoplay adds nothing and
// says the same words. Once a rebuild finishes, the lists read again.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class TotalsFailedWordsTest {
    private val rig = CarRig()

    @After
    fun close() = rig.close()

    // Starts the counts, then makes their next rebuild fail in its apply (a
    // full disk), which leaves them unreadable; answers its words.
    private fun failed(totals: app.winters.octo.listening.TotalsRig, song: String): String = runBlocking {
        totals.started()
        totals.totals.hooks = object : TotalsHooks() {
            override suspend fun applied() {
                throw SQLiteFullException("database or disk is full")
            }
        }
        totals.db.playOn(song, NOW)
        totals.totals.rebuild()
        totals.totals.failed.first { it }
        assertEquals(1L, totals.totals.applying.value % 2)
        totals.totals.problem.value!!
    }

    private suspend fun <T> thrown(block: suspend () -> T): Throwable? = try {
        block()
        null
    } catch (e: TotalsFailed) {
        e
    } catch (e: CarWords) {
        e
    }

    @Test
    fun carListsThatReadPlaysAnswerTheWordsAtOnce() = timed(60) {
        val library = runBlocking { rig.db.liveLibrary(60) }
        val words = failed(rig.totals, library.first().id)
        rig.orders = mapOf(SortList.Songs to SortOrder(SongSort.MostPlayed, true))
        rig.liveLists.value = mapOf("x" to LibraryQuery(sort = QuerySort(SongSort.MostPlayed.id)))
        // Bounded by the work, not by the counts coming back: each answers
        // with no rebuild run meanwhile.
        val songs = runBlocking { withTimeout(10_000) { thrown { rig.answer(CarNode.Songs) } } }
        assertTrue("$songs", songs is TotalsFailed)
        val live = runBlocking { withTimeout(10_000) { thrown { rig.answer(CarNode.LiveList("x")) } } }
        assertTrue("$live", live is CarWords)
        assertEquals(true, rig.totals.totals.failed.value)
        // The car is told the words, and the counts are asked to build again.
        var asked = 0
        assertEquals(words, carTotalsWords(songs!!) { asked++ })
        assertEquals(words, carTotalsWords(live!!) { asked++ })
        assertEquals(2, asked)
        assertEquals(null, carTotalsWords(IllegalStateException("other")) { asked++ })
        // Once a rebuild finishes, both read.
        rig.totals.totals.hooks = TotalsHooks()
        runBlocking {
            rig.totals.totals.tryAgain()
            rig.totals.totals.failed.first { !it }
        }
        assertEquals(60, runBlocking { rig.answer(CarNode.Songs) }.size)
        assertEquals(60, runBlocking { rig.answer(CarNode.LiveList("x")) }.size)
    }

    @Test
    fun autoplayAddsNothingAndTellsTheWords() = timed(60) {
        val db = openTestCatalog()
        try {
            runBlocking { db.dao().insertTracks((0 until 30).map { radioTrack("s$it") }) }
            val radio = RadioRig(db)
            val words = failed(radio.totals, "s3")
            val picker = AutoplayPicker(radio.radio, db, radio.totals.merge, radio.totals.history, QueueStore(db))
            val got = runBlocking { withTimeout(10_000) { runCatching { picker.picksAfter("s1", listOf("s0", "s1"), emptyList(), setOf("s0", "s1")) } } }
            val failure = got.exceptionOrNull()
            assertTrue("$got", failure is TotalsFailed)
            assertEquals(words, (failure as TotalsFailed).words)
        } finally {
            db.close()
        }
    }
}

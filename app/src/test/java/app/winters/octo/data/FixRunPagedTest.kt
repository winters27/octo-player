package app.winters.octo.data

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.health.FixStep
import app.winters.octo.listening.longOf
import app.winters.octo.listening.timed
import app.winters.octo.subsonic.LIBRARY_ACTION_RESTORE
import app.winters.octo.subsonic.LibraryActionResult
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A fix run of 3,000 steps reads them a page at a time and writes what
// puts each back to the database as it goes, so nothing holds the steps or
// their undo whole: no step is read more than a page before it is sent,
// and the undo is a handle to 3,000 rows. Undoing it reads those rows a
// page at a time, the last done first. A run's rows go once it is put
// back, replaced by a later run with nothing offering it any more, cut
// short, or ends on another server.
// The base held the whole list of steps and the whole list that put them
// back.
@RunWith(RobolectricTestRunner::class)
class FixRunPagedTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private val page = 100

    // `n` steps, counting how many it has handed out.
    private class Counting(n: Int, private val page: Int, private val name: String = "s") : StepSource {
        override val total = n
        @Volatile var handed = 0

        override suspend fun next(after: Long?): List<NumberedStep> {
            val from = after?.let { it.toInt() + 1 } ?: 0
            val steps = (from until minOf(total, from + page)).map { NumberedStep(it.toLong(), FixStep.Remove("$name$it", "Song $it", copy = true)) }
            handed += steps.size
            return steps
        }
    }

    private fun applied(step: FixStep) = LibraryActionResult(step.id, step.action, "applied")

    private fun rows(owner: String) = db.longOf("SELECT COUNT(*) FROM fix_undo WHERE owner = ?", owner)

    @Test
    fun aRunAndItsUndoGoAPageAtATime() = timed(60) {
        val runs = FixRuns(db, WorkOwners(), page)
        val source = Counting(3_000, page)
        var sent = 0
        var mostAhead = 0
        val run = runs.run(source, send = { step ->
            mostAhead = maxOf(mostAhead, source.handed - sent)
            sent++
            applied(step)
        })
        assertEquals(3_000, run.tally.removed)
        assertEquals("Removed 3,000 songs.", run.summary())
        assertTrue("$mostAhead steps were read before they were sent", mostAhead <= page)
        val undo = runs.last.value!!
        assertEquals(run.undo, undo)
        assertEquals(3_000, undo.count)
        assertEquals(setOf(LIBRARY_ACTION_RESTORE), undo.actions)
        assertEquals(3_000L, rows(undo.owner))

        // Put back: each page of the undo read in one statement of at most
        // a page, the last done first.
        db.counted.reset()
        db.counted.recording = true
        var expected = 2_999
        val put = runs.run(runs.steps(undo), send = { step ->
            assertEquals(FixStep.Restore("s$expected", "Song $expected"), step)
            expected--
            applied(step)
        }, keepUndo = false, putsBack = undo)
        db.counted.recording = false
        assertEquals(-1, expected)
        assertEquals(3_000, put.tally.restored)
        val reads = db.counted.own.filter { "FROM fix_undo" in it.sql && it.sql.trimStart().startsWith("SELECT") }
        // Thirty full pages and the empty read that ends the run.
        assertEquals(31, reads.size)
        assertTrue("$reads", reads.all { it.rowsRead <= page })
        // Put back, it is no longer the last change, and its rows went; the
        // undo's own undo is offered by its outcome only.
        assertNull(runs.last.value)
        assertEquals(0L, rows(undo.owner))
        assertEquals(3_000, put.undo!!.count)
        assertEquals(3_000L, rows(put.undo!!.owner))
    }

    @Test
    fun aRunsRowsGoWhenReplacedCutShortOrOnAnotherServer() = timed(60) {
        val runs = FixRuns(db, WorkOwners(), page)
        val first = runs.run(Counting(250, page, "a"), send = ::applied)
        val firstUndo = first.undo!!
        assertEquals(firstUndo, runs.last.value)

        // A later kept run replaces it as the last change; its outcome
        // keeps it until that lets it go.
        val second = runs.run(Counting(5, page, "b"), send = ::applied)
        assertEquals(second.undo, runs.last.value)
        assertEquals(250L, rows(firstUndo.owner))
        runs.release(firstUndo.owner)
        assertEquals(0L, rows(firstUndo.owner))
        assertEquals(5L, rows(second.undo!!.owner))

        // A run that ends on another server keeps nothing.
        val elsewhere = runs.run(Counting(7, page, "c"), send = ::applied, still = { false })
        assertNull(elsewhere.undo)
        assertEquals(second.undo, runs.last.value)
        assertEquals(5L, db.longOf("SELECT COUNT(*) FROM fix_undo"))

        // A run cut short partway keeps nothing either.
        try {
            runs.run(Counting(250, page, "d"), send = { step ->
                if (step.id == "d150") throw CancellationException("left")
                applied(step)
            })
            fail("the run was not cut short")
        } catch (e: CancellationException) {
            assertEquals("left", e.message)
        }
        assertEquals(5L, db.longOf("SELECT COUNT(*) FROM fix_undo"))

        // Stopped between steps, what was done is kept.
        var sent = 0
        val stopped = runs.run(Counting(250, page, "e"), send = { step -> sent++; applied(step) }, stop = { sent == 120 })
        assertTrue(stopped.tally.stopped)
        assertEquals(120, stopped.undo!!.count)
        runs.release(second.undo!!.owner)
        assertEquals(0L, rows(second.undo!!.owner))

        // Another server signed in to: every kept undo goes.
        runs.forget()
        assertNull(runs.last.value)
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM fix_undo"))
    }
}

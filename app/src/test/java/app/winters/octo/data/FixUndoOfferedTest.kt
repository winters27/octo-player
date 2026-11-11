package app.winters.octo.data

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.health.FixStep
import app.winters.octo.listening.longOf
import app.winters.octo.listening.timed
import app.winters.octo.subsonic.LibraryActionResult
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.common.Feedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// What puts a run back is kept while something offers it: the outcome's
// window while it is open, its line while that very line shows, or the
// last change. A window open while another run finishes still puts its own
// change back; a line replaced by another, or put away, lets its run's
// undo go unless it is the last change; and a put-back whose change is no
// longer kept says so instead of "Nothing was done." The base kept only the
// newest outcome's undo, so a later run's outcome cleared an open window's.
@RunWith(RobolectricTestRunner::class)
class FixUndoOfferedTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private val runs = FixRuns(db, WorkOwners(), 50)

    private fun source(n: Int, name: String) = stepsOf((0 until n).map { FixStep.Remove("$name$it", "Song $it", copy = true) })

    private fun applied(step: FixStep) = LibraryActionResult(step.id, step.action, "applied")

    private fun rows(owner: String) = db.longOf("SELECT COUNT(*) FROM fix_undo WHERE owner = ?", owner)

    @Test
    fun aWindowOpenWhileAnotherRunFinishesStillPutsItsOwnChangeBack() = timed(60) {
        // A run's outcome shows in its window; meanwhile a run launched
        // elsewhere finishes and becomes the last change.
        val first = runs.run(source(120, "a"), send = ::applied)
        val second = runs.run(source(7, "b"), send = ::applied)
        assertEquals(second.undo, runs.last.value)
        assertEquals("the open window's change went", 120L, rows(first.undo!!.owner))

        // Its Undo puts back its own change, the last done first.
        val put = ArrayList<String>()
        val back = runs.run(runs.steps(first.undo!!), send = { step -> put += step.id; applied(step) }, keepUndo = false, putsBack = first.undo)
        assertEquals(120, back.tally.restored)
        assertEquals((119 downTo 0).map { "a$it" }, put)
        assertEquals(0L, rows(first.undo!!.owner))
        // The other run is still the last change.
        assertEquals(second.undo, runs.last.value)
        assertEquals(7L, rows(second.undo!!.owner))
    }

    @Test
    fun aLineReplacedOrPutAwayLetsItsRunGo() = timed(60) {
        val feedback = Feedback()
        // The lines' watches, joined once each line has gone.
        val lines = CoroutineScope(coroutineContext + Job())
        suspend fun gone() = lines.coroutineContext.job.children.toList().joinAll()

        // A line offers the first run's Undo while a later run is the last
        // change: kept while the line shows.
        val first = runs.run(source(30, "a"), send = ::applied)
        val second = runs.run(source(5, "b"), send = ::applied)
        feedback.offerUndo("Removed 30 songs.", {}, first.undo!!.owner, runs, lines)
        yield()
        assertEquals("kept while its line shows", 30L, rows(first.undo!!.owner))

        // Another line replaces it: its run goes.
        feedback.show("Something else")
        gone()
        assertEquals("a replaced line's run is kept", 0L, rows(first.undo!!.owner))
        assertEquals(5L, rows(second.undo!!.owner))

        // A line put away (its time up, or tapped) lets its run go too.
        val third = runs.run(source(4, "c"), send = ::applied)
        val fourth = runs.run(source(3, "d"), send = ::applied)
        feedback.offerUndo("Removed 4 songs.", {}, third.undo!!.owner, runs, lines)
        yield()
        assertEquals(4L, rows(third.undo!!.owner))
        feedback.dismiss(feedback.message.value!!)
        gone()
        assertEquals("a line put away kept its run", 0L, rows(third.undo!!.owner))

        // The last change's own line going keeps it: it is still the last
        // change, for Undo last change.
        feedback.offerUndo("Removed 3 songs.", {}, fourth.undo!!.owner, runs, lines)
        feedback.show("Another")
        gone()
        assertEquals(fourth.undo, runs.last.value)
        assertEquals(3L, rows(fourth.undo!!.owner))
    }

    @Test
    fun aPutBackWhoseChangeIsGoneSaysSo() = timed(60) {
        val run = runs.run(source(10, "a"), send = ::applied)
        // Another server signed in to: every kept undo goes.
        runs.forget()
        assertEquals(0L, rows(run.undo!!.owner))
        var sent = 0
        val back = runs.run(runs.steps(run.undo!!), send = { step -> sent++; applied(step) }, keepUndo = false, putsBack = run.undo)
        assertEquals(0, sent)
        assertEquals("That change can no longer be put back.", back.summary())
    }
}

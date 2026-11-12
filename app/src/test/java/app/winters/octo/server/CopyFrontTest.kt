package app.winters.octo.server

import android.app.Activity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

// Where the app is decides when the copy's notice starts: on leaving during
// a whole copy (even while a service is still stopping), on a whole copy
// planned while away, never on a turn of the screen and never in front.
@RunWith(RobolectricTestRunner::class)
class CopyFrontTest {
    // A screen that can say it is only turning.
    class Turning : Activity() {
        var turning = false

        override fun isChangingConfigurations() = turning
    }

    private var running: CopyRunState? = whole()
    private var starts = 0
    private val front = CopyFront({ running }) { starts++ }
    private val callbacks = ForegroundCallbacks(front::came, front::left)

    private fun whole(step: CopyStep = CopyStep.Read, whole: Boolean = true) =
        CopyRunState(1, 1, "acc", "Home", true, whole, step, 0, null, null, null, 0, 0, false)

    private fun screen() = Robolectric.buildActivity(Turning::class.java).get()

    @Test
    fun leavingDuringAWholeCopyStartsItsNotice() {
        val a = screen()
        callbacks.onActivityStarted(a)
        assertTrue(front.inFront.value)
        callbacks.onActivityStopped(a)
        assertFalse(front.inFront.value)
        assertTrue(front.away)
        assertEquals(1, starts)
    }

    @Test
    fun aTurnOfTheScreenIsNotLeaving() {
        val a = screen()
        callbacks.onActivityStarted(a)
        a.turning = true
        callbacks.onActivityStopped(a)
        assertTrue(front.inFront.value)
        assertFalse(front.away)
        assertEquals(0, starts)
    }

    @Test
    fun anotherScreenStillShowingIsNotLeaving() {
        val a = screen()
        val b = screen()
        callbacks.onActivityStarted(a)
        callbacks.onActivityStarted(b)
        callbacks.onActivityStopped(a)
        assertTrue(front.inFront.value)
        assertEquals(0, starts)
    }

    // The app came back, the service was told to stop and is still up, and
    // the listener leaves again: it is started again all the same.
    @Test
    fun leavingAgainWhileTheServiceStopsStartsItAgain() {
        val a = screen()
        callbacks.onActivityStarted(a)
        callbacks.onActivityStopped(a)
        callbacks.onActivityStarted(a)
        callbacks.onActivityStopped(a)
        assertEquals(2, starts)
    }

    @Test
    fun aCopyOfOnlyTheStepsStartsNothing() {
        running = whole(CopyStep.Settle, whole = false)
        val a = screen()
        callbacks.onActivityStarted(a)
        callbacks.onActivityStopped(a)
        assertEquals(0, starts)
    }

    // A whole copy planned while away starts the notice, even with a service
    // still up (it may be stopping; a start of one that runs on only keeps it).
    @Test
    fun aWholeCopyPlannedWhileAwayStartsItsNotice() {
        // A process that never showed a screen (started for the car) is never away.
        front.planned(whole = true)
        assertEquals(0, starts)
        val a = screen()
        callbacks.onActivityStarted(a)
        front.planned(whole = true)
        assertEquals(0, starts)
        running = null
        callbacks.onActivityStopped(a)
        front.planned(whole = true)
        assertEquals(1, starts)
        front.planned(whole = true)
        assertEquals(2, starts)
        front.planned(whole = false)
        assertEquals(2, starts)
    }

    @Test
    fun aPullDuringAWholeCopyWaitsForIt() {
        assertTrue(pullWaits(whole(), "acc"))
        assertFalse(pullWaits(whole(CopyStep.Settle, whole = false), "acc"))
        assertFalse(pullWaits(whole(), "other"))
        assertFalse(pullWaits(null, "acc"))
    }

    // The music is still to come until a whole copy of the address is in,
    // whichever record says so.
    @Test
    fun theMusicIsComingUntilEitherRecordSaysACopyIsIn() {
        assertTrue(arrivingFrom(null, null))
        assertFalse(arrivingFrom("acc", null))
        assertFalse(arrivingFrom(null, "acc"))
    }

    @Test
    fun whetherMusicIsComingIsNullOnlyUntilRead() = runBlocking {
        val source = MutableStateFlow<String?>(null)
        val wait = copyWaitOf(source, flowOf(Unit), state = { flowOf(CopyStateRow(null, phoneMusic = true)) }, older = { flowOf(null) })
        assertNull(wait.first())
        source.value = ""
        assertEquals(CopyWait(awaiting = false, phoneMusic = false), wait.first())
        source.value = "server:h"
        assertEquals(CopyWait(awaiting = true, phoneMusic = true, sourceId = "server:h"), wait.first())
        val moved = copyWaitOf(flowOf("server:h"), flowOf(Unit), state = { flowOf(CopyStateRow(null, phoneMusic = false)) }, older = { flowOf("acc") })
        assertEquals(CopyWait(awaiting = false, phoneMusic = false, sourceId = "server:h"), moved.first())
    }

    // An older version's record moving into the database: the record leaves
    // the preference before the database says so, and for that moment both
    // reads say none. The music never shows as still to come.
    @Test
    fun aRecordMovingIntoTheDatabaseNeverShowsTheMusicAsComing() = runBlocking {
        val room = MutableStateFlow(CopyStateRow(null, phoneMusic = false))
        val pref = MutableStateFlow<String?>("acc")
        val wait = copyWaitOf(flowOf("server:h"), flowOf(Unit), state = { room }, older = { pref })
        val seen = Channel<CopyWait?>(Channel.UNLIMITED)
        val watching = launch { wait.collect { seen.send(it) } }
        assertEquals(false, withTimeout(5_000) { seen.receive() }?.awaiting)
        // The moment both say none is read before the database catches up.
        pref.value = null
        repeat(20) { yield() }
        room.value = CopyStateRow("acc", phoneMusic = false)
        repeat(20) { yield() }
        // The next thing said is a real change, and the music is still in.
        room.value = CopyStateRow("acc", phoneMusic = true)
        assertEquals(CopyWait(awaiting = false, phoneMusic = true, sourceId = "server:h"), withTimeout(5_000) { seen.receive() })
        watching.cancel()
    }

    // A read that fails counts as nothing to come, and the next server is
    // read again.
    @Test
    fun aFailedReadCountsAsNothingToComeAndTheNextServerIsReadAgain() = runBlocking {
        val source = MutableStateFlow<String?>("server:broken")
        val wait = copyWaitOf(
            source,
            flowOf(Unit),
            state = { id -> if (id == "server:broken") flow { throw IllegalStateException("unreadable") } else flowOf(CopyStateRow(null, false)) },
            older = { flowOf(null) },
        )
        val seen = Channel<CopyWait?>(Channel.UNLIMITED)
        val watching = launch { wait.collect { seen.send(it) } }
        assertEquals(CopyWait(awaiting = false, phoneMusic = false, sourceId = "server:broken"), withTimeout(5_000) { seen.receive() })
        source.value = "server:h"
        assertEquals(CopyWait(awaiting = true, phoneMusic = false, sourceId = "server:h"), withTimeout(5_000) { seen.receive() })
        watching.cancel()
    }
}

package app.winters.octo.server

import android.database.sqlite.SQLiteFullException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A server change survives a database error in its steps: it is noted, the
// change finishes, and the switch is merged again until it works.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ServerChangeTest {
    // The library merged for a server, as ServerSync keeps it, and the
    // server in use.
    private var mergedFor: String? = "server:old"
    private var wanted = "server:new"
    private val problem = MutableStateFlow<String?>(null)

    // A merge answers the server it put in: the one in use as it starts.
    private fun switch(merge: suspend () -> String = { wanted }) = LibrarySwitch({ mergedFor }, merge, { mergedFor = it }, problem) { wanted }

    @Test
    fun aSwitchWhoseMergeFillsTheDiskIsMarkedOnlyOnceAMergeWorks() = runBlocking {
        var merges = 0
        var full = true
        val switch = switch {
            merges++
            if (full) throw SQLiteFullException("database or disk is full")
            wanted
        }
        assertFalse(switch.finish("server:new"))
        assertEquals("server:old", mergedFor)
        assertEquals(SWITCH_PROBLEM, problem.value)
        // A later try (the copy, a freshen, signed out too) that works ends it.
        full = false
        assertTrue(switch.finish("server:new"))
        assertEquals("server:new", mergedFor)
        assertNull(problem.value)
        assertEquals(2, merges)
        // Nothing to do once it is the wanted one's.
        assertTrue(switch.finish("server:new"))
        assertEquals(2, merges)
    }

    @Test
    fun signedOutTheSwitchIsToNoServer() = runBlocking {
        wanted = ""
        problem.value = SWITCH_PROBLEM
        assertTrue(switch().finish(""))
        assertEquals("", mergedFor)
        assertNull(problem.value)
    }

    // The switch's words go once the library is the wanted one's, but a
    // copy's own problem is not the switch's to clear.
    @Test
    fun onlyTheSwitchsOwnWordsAreCleared() = runBlocking {
        mergedFor = "server:new"
        problem.value = SWITCH_PROBLEM
        assertTrue(switch { throw AssertionError("no merge needed") }.finish("server:new"))
        assertNull(problem.value)
        problem.value = "Couldn't reach the server."
        assertTrue(switch().finish("server:new"))
        assertEquals("Couldn't reach the server.", problem.value)
    }

    // A try whose server stopped being the one in use while it merged never
    // marks the library for it, so the newer server's mark stands.
    @Test
    fun anOlderTryNeverNamesTheLibraryForItsServer() = runBlocking {
        val switch = switch {
            val inUse = wanted
            if (inUse == "server:b") wanted = "server:c"
            inUse
        }
        wanted = "server:b"
        assertFalse(switch.finish("server:b"))
        assertEquals("server:old", mergedFor)
        // Not the switch in use any more, so not its words either.
        assertNull(problem.value)
        assertTrue(switch.finish("server:c"))
        assertEquals("server:c", mergedFor)
    }

    // A switch away and back during one merge: the merge put in the other
    // server, so the library is not named for the one in use again.
    @Test
    fun aMergeOfAnotherServerNeverNamesTheLibrary() = runBlocking {
        wanted = "server:a"
        assertFalse(switch { "server:b" }.finish("server:a"))
        assertEquals("server:old", mergedFor)
        assertTrue(switch().finish("server:a"))
        assertEquals("server:a", mergedFor)
    }

    // Tries run one at a time: a second waits for the first, so their marks
    // never cross.
    @Test
    fun triesRunOneAtATime() = runTest {
        val letGo = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val switch = switch {
            order += "merge"
            letGo.await()
            wanted
        }
        val first = async { switch.finish("server:new") }
        runCurrent()
        val second = async { order += "second asks"; switch.finish("server:new") }
        // Everything that can run has run: a second try that did not wait
        // would be merging now.
        runCurrent()
        assertEquals(listOf("merge", "second asks"), order)
        letGo.complete(Unit)
        advanceUntilIdle()
        assertTrue(first.await())
        assertTrue(second.await())
        // The second found the library already the wanted one's: one merge.
        assertEquals(listOf("merge", "second asks"), order)
    }

    // A try stopped once its merge finished (the app went to the back)
    // still marks the library; the mark, like the real one, can be stopped.
    @Test
    fun aTryStoppedAfterItsMergeStillMarks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val switch = LibrarySwitch({ mergedFor }, { scope.cancel(); wanted }, { yield(); mergedFor = it }, problem) { wanted }
        scope.launch { switch.finish("server:new") }.join()
        assertEquals("server:new", mergedFor)
    }

    // On arrival a fresh copy starts nothing but a look, unless its switch
    // failed: then the copy (which finishes the switch first) starts anyway.
    @Test
    fun aFailedSwitchStartsACopyEvenWhenTheCopyIsFresh() = runBlocking {
        val did = mutableListOf<String>()
        suspend fun arriveWith(switched: Boolean, needed: CopyNeed) =
            arrive("home", "server:new", { switched }, { did += "ready $it" }, { needed }, { did += "copy" }, { did += "look" })
        arriveWith(switched = true, CopyNeed.Fresh)
        arriveWith(switched = false, CopyNeed.Fresh)
        arriveWith(switched = true, CopyNeed.AfterSync)
        assertEquals(listOf("ready home", "look", "ready home", "copy", "ready home", "copy"), did)
    }

    @Test
    fun signedOutAnArrivalSwitchesToNoServerAndCopiesNothing() = runBlocking {
        val asked = mutableListOf<String>()
        var readyFor: String? = null
        arrive(null, null, { asked += it; true }, { readyFor = it }, { fail("no copy to judge"); CopyNeed.Copy }, { fail("no copy") }, { fail("no look") })
        assertEquals(listOf(""), asked)
        assertEquals(PHONE_LIBRARY, readyFor)
    }

    // Every freshen tries a failed switch again, signed out too, but only
    // once arrive() put a library in place.
    @Test
    fun aFreshenTriesTheSwitchSignedOutToo() = runBlocking {
        val asked = mutableListOf<String>()
        val finish: suspend (String) -> Boolean = { asked += it; true }
        assertFalse(freshenSwitch(PHONE_LIBRARY, null, null, finish))
        assertEquals(listOf(""), asked)
        assertTrue(freshenSwitch("home", "home", "server:new", finish))
        assertEquals(listOf("", "server:new"), asked)
        // Before arrive(), or while it is still switching, nothing.
        assertFalse(freshenSwitch(null, "home", "server:new", finish))
        assertFalse(freshenSwitch(PHONE_LIBRARY, "home", "server:new", finish))
        assertEquals(listOf("", "server:new"), asked)
        // A switch that still fails stops the freshen.
        assertFalse(freshenSwitch("home", "home", "server:new") { false })
    }

    // A copy finishes a failed switch first and copies nothing while it
    // fails; asked to copy afresh, it copies the whole library even when only
    // the steps after a copy are owed.
    @Test
    fun aCopyFinishesTheSwitchFirst() = runBlocking {
        assertNull(copyPlan("server:new", copyAllAnyway = true, { false }) { fail("nothing judged"); CopyNeed.Copy })
        assertEquals(false, copyPlan("server:new", copyAllAnyway = false, { true }) { CopyNeed.AfterSync })
        assertEquals(true, copyPlan("server:new", copyAllAnyway = true, { true }) { CopyNeed.AfterSync })
        assertEquals(true, copyPlan("server:new", copyAllAnyway = false, { true }) { CopyNeed.Copy })
    }

    // Copying afresh replaces the running copy in one step: a start asked
    // meanwhile joins the fresh whole copy instead of running a part one.
    @Test
    fun copyingAfreshIsNeverOvertakenByAStart() = runTest {
        val runs = mutableListOf<Boolean>()
        val jobs = CopyJobs(this) { copyAll ->
            runs += copyAll
            if (runs.size == 1) awaitCancellation()
        }
        jobs.start()
        runCurrent()
        jobs.restart()
        jobs.start()
        advanceUntilIdle()
        assertEquals(listOf(false, true), runs)
    }

    // A copy stopped by copying afresh still cleans up; stopping the fresh
    // one waits for that cleanup, and no copy starts beside it meanwhile.
    @Test
    fun stoppingAFreshCopyWaitsForTheOldOneToCleanUp() = runTest {
        val runs = mutableListOf<Boolean>()
        val order = mutableListOf<String>()
        val cleanup = CompletableDeferred<Unit>()
        val jobs = CopyJobs(this) { copyAll ->
            runs += copyAll
            if (!copyAll) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanup.await()
                        order += "cleaned"
                    }
                }
            }
        }
        jobs.start()
        runCurrent()
        jobs.restart()
        // Stopped at once, before it ever ran.
        val fresh = jobs.current()!!
        fresh.cancel()
        val stopper = launch {
            fresh.join()
            order += "stopped"
        }
        runCurrent()
        // The cleanup is let go even when a check fails, so a failure never
        // leaves the test waiting on it.
        try {
            assertFalse(stopper.isCompleted)
            jobs.start()
            runCurrent()
            assertEquals(listOf(false), runs)
        } finally {
            cleanup.complete(Unit)
        }
        advanceUntilIdle()
        assertEquals(listOf("cleaned", "stopped"), order)
        // The fresh copy was stopped, so it never ran.
        assertEquals(listOf(false), runs)
    }

    @Test
    fun aFullDiskDuringAServerChangeIsNotedNotThrown() = runBlocking {
        assertNull(orNoted<Unit> { throw SQLiteFullException("database or disk is full") })
        assertEquals(3, orNoted { 3 })
    }

    @Test
    fun stoppingIsNeverSwallowed() = runBlocking {
        try {
            orNoted<Unit> { throw CancellationException("stopped") }
            fail("a stop was swallowed")
        } catch (e: CancellationException) {
            // passed on, as it must be
        }
    }
}

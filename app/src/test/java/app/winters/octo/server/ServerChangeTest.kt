package app.winters.octo.server

import android.database.sqlite.SQLiteFullException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

// A server change survives a database error in its steps: it is noted, the
// change finishes, and the switch is merged again until it works.
@RunWith(RobolectricTestRunner::class)
class ServerChangeTest {
    // The library merged for a server, as ServerSync keeps it, and the
    // server in use.
    private var mergedFor: String? = "server:old"
    private var wanted = "server:new"
    private val problem = MutableStateFlow<String?>(null)

    private fun switch(merge: suspend () -> Unit = {}) = LibrarySwitch({ mergedFor }, merge, { mergedFor = it }, problem) { wanted }

    @Test
    fun aSwitchWhoseMergeFillsTheDiskIsMarkedOnlyOnceAMergeWorks() = runBlocking {
        var merges = 0
        var full = true
        val switch = switch {
            merges++
            if (full) throw SQLiteFullException("database or disk is full")
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
        assertTrue(switch { fail("no merge needed") }.finish("server:new"))
        assertNull(problem.value)
        problem.value = "Couldn't reach the server."
        assertTrue(switch().finish("server:new"))
        assertEquals("Couldn't reach the server.", problem.value)
    }

    // A try whose server stopped being the one in use while it merged never
    // marks the library for it, so the newer server's mark stands.
    @Test
    fun anOlderTryNeverNamesTheLibraryForItsServer() = runBlocking {
        val switch = switch { if (wanted == "server:b") wanted = "server:c" }
        wanted = "server:b"
        assertFalse(switch.finish("server:b"))
        assertEquals("server:old", mergedFor)
        // Not the switch in use any more, so not its words either.
        assertNull(problem.value)
        assertTrue(switch.finish("server:c"))
        assertEquals("server:c", mergedFor)
    }

    // Tries run one at a time: a second waits for the first, so their marks
    // never cross.
    @Test
    fun triesRunOneAtATime() = runBlocking {
        val merging = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val switch = switch {
            order += "merge"
            merging.complete(Unit)
            letGo.await()
        }
        val first = async(Dispatchers.Default) { switch.finish("server:new") }
        merging.await()
        val second = async(Dispatchers.Default) { order += "second asks"; switch.finish("server:new") }
        // Long enough for a second try that did not wait to start merging.
        delay(200)
        letGo.complete(Unit)
        assertTrue(withTimeout(5_000) { first.await() })
        assertTrue(withTimeout(5_000) { second.await() })
        // The second found the library already the wanted one's: one merge.
        assertEquals(listOf("merge", "second asks"), order)
    }

    // A try stopped once its merge finished (the app went to the back)
    // still marks the library; the mark, like the real one, can be stopped.
    @Test
    fun aTryStoppedAfterItsMergeStillMarks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val switch = LibrarySwitch({ mergedFor }, { scope.cancel() }, { yield(); mergedFor = it }, problem) { wanted }
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
    fun copyingAfreshIsNeverOvertakenByAStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runs = Collections.synchronizedList(mutableListOf<Boolean>())
        val firstRunning = CompletableDeferred<Unit>()
        val jobs = CopyJobs(scope) { copyAll ->
            runs += copyAll
            if (runs.size == 1) {
                firstRunning.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        jobs.start()
        firstRunning.await()
        jobs.restart()
        jobs.start()
        withTimeout(5_000) { jobs.current()!!.join() }
        assertEquals(listOf(false, true), runs)
        scope.cancel()
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

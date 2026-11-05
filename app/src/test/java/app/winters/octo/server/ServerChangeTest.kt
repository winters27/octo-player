package app.winters.octo.server

import android.database.sqlite.SQLiteFullException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    // The server the library was last merged for, as the merge records it,
    // and the server in use.
    private var mergedFor: String? = "server:old"
    private var wanted = "server:new"
    private val problem = MutableStateFlow<String?>(null)

    // A merge records the server it put in: the one in use as it starts.
    private fun switch(merge: suspend () -> Unit = { mergedFor = wanted }) = LibrarySwitch({ mergedFor }, merge, problem) { wanted }

    @Test
    fun aSwitchWhoseMergeFillsTheDiskIsMarkedOnlyOnceAMergeWorks() = runBlocking {
        var merges = 0
        var full = true
        val switch = switch {
            merges++
            if (full) throw SQLiteFullException("database or disk is full")
            mergedFor = wanted
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

    // Switched to b and back to a while b's merge ran: the library holds b
    // and says so, so the switch back to a merges again.
    @Test
    fun aSwitchAwayAndBackMergesAgain() = runBlocking {
        mergedFor = "server:a"
        wanted = "server:b"
        var merges = 0
        val switch = switch {
            merges++
            val inUse = wanted
            if (inUse == "server:b") wanted = "server:a"
            mergedFor = inUse
        }
        assertTrue(switch.finish("server:b"))
        assertEquals("server:b", mergedFor)
        assertTrue(switch.finish("server:a"))
        assertEquals("server:a", mergedFor)
        assertEquals(2, merges)
    }

    // A merge that put in another server than the one asked for leaves the
    // switch unfinished and said, and the next try merges again.
    @Test
    fun aMergeOfAnotherServerLeavesTheSwitchUnfinished() = runBlocking {
        wanted = "server:a"
        assertFalse(switch { mergedFor = "server:b" }.finish("server:a"))
        assertEquals(SWITCH_PROBLEM, problem.value)
        assertTrue(switch().finish("server:a"))
        assertEquals("server:a", mergedFor)
        assertNull(problem.value)
    }

    // Tries run one at a time: a second waits for the first, so it finds
    // the first one's merge in place instead of merging again.
    @Test
    fun triesRunOneAtATime() = runTest {
        val letGo = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val switch = switch {
            order += "merge"
            letGo.await()
            mergedFor = wanted
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

    // A copy as CopyRun runs it: a whole one says when it is in the library,
    // then the steps after it run; it answers whether it copied whole.
    private suspend fun copied(whole: Boolean, swapped: suspend () -> Unit, steps: () -> Unit = {}): Boolean {
        if (whole) swapped()
        steps()
        return whole
    }

    // A whole copy asked for (a folder change, Sync now) is made whole and
    // then settled.
    @Test
    fun anAskedWholeCopyIsMadeWholeThenSettled() = runBlocking {
        val owed = WholeCopyOwed()
        val copies = mutableListOf<Boolean>()
        owed.ask("home")
        copyUntilWhole(owed, "home") { whole, swapped -> copies += whole; copied(whole, swapped) }
        assertEquals(listOf(true), copies)
        assertNull(owed.owed("home"))
        // Nothing owed: a start only resumes.
        copyUntilWhole(owed, "home") { whole, swapped -> copies += whole; copied(whole, swapped) }
        assertEquals(listOf(true, false), copies)
    }

    // A lighter copy already running when the whole one is asked for (it
    // slipped in while the last copy stopped) copies whole before it ends,
    // so whoever waits on it gets what was asked.
    @Test
    fun aLighterCopyRunningWhenAWholeOneIsAskedCopiesWholeToo() = runBlocking {
        val owed = WholeCopyOwed()
        val copies = mutableListOf<Boolean>()
        copyUntilWhole(owed, "home") { whole, swapped ->
            copies += whole
            copied(whole, swapped) { if (copies.size == 1) owed.ask("home") }
        }
        assertEquals(listOf(false, true), copies)
        assertNull(owed.owed("home"))
    }

    // A whole copy that fails before it is in the library leaves it owed,
    // so the next start (a freshen) copies whole instead of only resuming.
    @Test
    fun aWholeCopyThatFailsStaysOwed() = runBlocking {
        val owed = WholeCopyOwed()
        owed.ask("home")
        try {
            copyUntilWhole(owed, "home") { _, _ -> throw IllegalStateException("unreachable") }
            fail("the failure was swallowed")
        } catch (e: IllegalStateException) {
            // passed on to sync(), which says it
        }
        assertEquals(1, owed.owed("home"))
        val copies = mutableListOf<Boolean>()
        copyUntilWhole(owed, "home") { whole, swapped -> copies += whole; copied(whole, swapped) }
        assertEquals(listOf(true), copies)
        assertNull(owed.owed("home"))
    }

    // A whole copy in the library whose steps after it fail has made what
    // was asked: the next start re-runs only the steps, never the whole copy.
    @Test
    fun stepsThatFailAfterAWholeCopyAreTriedAgainAlone() = runBlocking {
        val owed = WholeCopyOwed()
        owed.ask("home")
        try {
            copyUntilWhole(owed, "home") { whole, swapped -> copied(whole, swapped) { throw IllegalStateException("playlists") } }
            fail("the failure was swallowed")
        } catch (e: IllegalStateException) {
            // passed on to sync(), which says it
        }
        assertNull(owed.owed("home"))
        val copies = mutableListOf<Boolean>()
        copyUntilWhole(owed, "home") { whole, swapped -> copies += whole; copied(whole, swapped) }
        assertEquals(listOf(false), copies)
    }

    // A whole copy asked for again while one runs stays owed; one asked for
    // another account, or a copy that copied nothing (a failed switch),
    // settles nothing.
    @Test
    fun onlyTheAskAWholeCopyAnsweredIsSettled() = runBlocking {
        val owed = WholeCopyOwed()
        owed.ask("home")
        copyUntilWhole(owed, "home") { whole, swapped -> copied(whole, swapped) { owed.ask("home") } }
        assertEquals(2, owed.owed("home"))
        owed.ask("other")
        val copies = mutableListOf<Boolean>()
        copyUntilWhole(owed, "home") { whole, swapped -> copies += whole; copied(whole, swapped) }
        assertEquals(listOf(false), copies)
        assertEquals(3, owed.owed("other"))
        copyUntilWhole(owed, "other") { _, _ -> null }
        assertEquals(3, owed.owed("other"))
    }

    // A start notes a whole ask before it looks at the copy running, so the
    // running copy copies whole too; a lighter start or one signed out notes
    // none.
    @Test
    fun aWholeAskIsNotedEvenWhileACopyRuns() {
        val owed = WholeCopyOwed()
        assertFalse(startWanted(owed, whole = true, account = "home", running = true))
        assertEquals(1, owed.owed("home"))
        val none = WholeCopyOwed()
        assertTrue(startWanted(none, whole = false, account = "home", running = false))
        assertTrue(startWanted(none, whole = true, account = null, running = false))
        assertNull(none.owed("home"))
    }

    // A freshen of a fresh copy only looks, unless a whole copy is owed.
    @Test
    fun aFreshenCopiesWhileAWholeCopyIsOwed() {
        assertTrue(freshenLooks(CopyNeed.Fresh, owed = null))
        assertFalse(freshenLooks(CopyNeed.Fresh, owed = 1))
        assertFalse(freshenLooks(CopyNeed.AfterSync, owed = null))
    }

    // Every ask (Sync now, a folder change, downloads, upgrades) copies
    // whole; only the automatic starts (arrival, freshen) resume.
    @Test
    fun onlyAutomaticStartsResume() {
        val text = java.io.File(System.getProperty("octo.mainSources")!!, "app/winters/octo/server/ServerSync.kt").readText()
        assertTrue(text.contains("fun syncNow() = startSync(whole = true)"))
        assertTrue(text.contains("private fun resume() = startSync(whole = false)"))
        assertEquals(listOf("copy = ::resume", "resume()"), Regex("""copy = ::resume|(?<!fun )resume\(\)""").findAll(text).map { it.value }.toList())
        assertEquals(1, Regex("""startSync\(whole = false\)""").findAll(text).count())
        // The copy settles what it was asked for with the run's own swap.
        assertTrue(text.contains("session.client, copyAll, swapped, report)"))
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

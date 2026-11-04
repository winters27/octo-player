package app.winters.octo.server

import android.database.sqlite.SQLiteFullException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
@RunWith(RobolectricTestRunner::class)
class ServerChangeTest {
    // The library merged for a server, as ServerSync keeps it.
    private var mergedFor: String? = "server:old"

    @Test
    fun aSwitchWhoseMergeFillsTheDiskIsMarkedOnlyOnceAMergeWorks() = runBlocking {
        var merges = 0
        val fails: suspend () -> Unit = {
            merges++
            throw SQLiteFullException("database or disk is full")
        }
        assertFalse(switchLibrary("server:new", { mergedFor }, fails) { mergedFor = it })
        assertEquals("server:old", mergedFor)
        // The next try (the next copy, or the next change) merges again.
        assertTrue(switchLibrary("server:new", { mergedFor }, { merges++ }) { mergedFor = it })
        assertEquals("server:new", mergedFor)
        assertEquals(2, merges)
        // Nothing to do once it is the wanted one's.
        assertTrue(switchLibrary("server:new", { mergedFor }, { merges++ }) { mergedFor = it })
        assertEquals(2, merges)
    }

    @Test
    fun aFailedSwitchIsSaidUntilATryWorks() = runBlocking {
        val problem = MutableStateFlow<String?>(null)
        var full = true
        val switch = LibrarySwitch({ mergedFor }, { if (full) throw SQLiteFullException("database or disk is full") }, { mergedFor = it }, problem)
        assertFalse(switch.finish("server:new"))
        assertEquals(SWITCH_PROBLEM, problem.value)
        // A later try (the copy, a freshen, signed out too) that works ends it.
        full = false
        assertTrue(switch.finish("server:new"))
        assertNull(problem.value)
        assertEquals("server:new", mergedFor)
    }

    @Test
    fun signedOutTheSwitchIsToNoServer() = runBlocking {
        val problem = MutableStateFlow<String?>(SWITCH_PROBLEM)
        val switch = LibrarySwitch({ mergedFor }, {}, { mergedFor = it }, problem)
        assertTrue(switch.finish(""))
        assertEquals("", mergedFor)
        assertNull(problem.value)
    }

    // The switch's words go once the library is the wanted one's, but a
    // copy's own problem is not the switch's to clear.
    @Test
    fun onlyTheSwitchsOwnWordsAreCleared() = runBlocking {
        mergedFor = "server:new"
        val stale = MutableStateFlow<String?>(SWITCH_PROBLEM)
        assertTrue(LibrarySwitch({ mergedFor }, { fail("no merge needed") }, { mergedFor = it }, stale).finish("server:new"))
        assertNull(stale.value)
        val other = MutableStateFlow<String?>("Couldn't reach the server.")
        assertTrue(LibrarySwitch({ mergedFor }, {}, { mergedFor = it }, other).finish("server:new"))
        assertEquals("Couldn't reach the server.", other.value)
    }

    // A fresh copy starts nothing, unless its switch failed: then the copy
    // (which finishes the switch first) starts anyway.
    @Test
    fun aFailedSwitchStartsACopyEvenWhenTheCopyIsFresh() {
        assertFalse(copyOnArrival(CopyNeed.Fresh, switched = true))
        assertTrue(copyOnArrival(CopyNeed.Fresh, switched = false))
        assertTrue(copyOnArrival(CopyNeed.Copy, switched = true))
        assertTrue(copyOnArrival(CopyNeed.AfterSync, switched = true))
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

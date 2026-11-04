package app.winters.octo.server

import android.database.sqlite.SQLiteFullException
import kotlinx.coroutines.CancellationException
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

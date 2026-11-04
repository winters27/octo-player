package app.winters.octo.server

import android.database.sqlite.SQLiteFullException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A server change survives a database error in its steps: it is noted, the
// change finishes, and the listener for the next change stays alive.
@RunWith(RobolectricTestRunner::class)
class ServerChangeTest {
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

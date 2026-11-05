package app.winters.octo.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// A pull ends with the phone's rescan; the server's copy goes on behind it
// and says its failure when it ends.
class PullTest {
    @Test
    fun aPullEndsWithTheRescanWhileTheCopyRunsOn() = runBlocking {
        val behind = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val copyEnds = CompletableDeferred<String?>()
        val said = CompletableDeferred<String>()
        var rescanned = false
        pull({ rescanned = true }, { copyEnds.await() }, behind) { said.complete(it) }
        assertTrue(rescanned)
        assertTrue(!said.isCompleted)
        copyEnds.complete("Couldn't reach the server.")
        assertEquals("Couldn't reach the server.", withTimeout(5_000) { said.await() })
        behind.cancel()
    }

    @Test
    fun withNoServerOnlyThePhoneIsRescanned() = runBlocking {
        val behind = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var rescanned = false
        pull({ rescanned = true }, null, behind) { error("nothing to say") }
        assertTrue(rescanned)
        behind.cancel()
    }
}

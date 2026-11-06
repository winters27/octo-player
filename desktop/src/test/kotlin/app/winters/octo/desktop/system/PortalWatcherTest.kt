package app.winters.octo.desktop.system

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

// A portal that answers as told: with `read`, after `gate` opens when given,
// and keeping the function the watcher gave it to call with changes.
class FakePortal(private val read: Int?, private val gate: CountDownLatch? = null) : PortalSource {
    @Volatile
    var changed: ((Int) -> Unit)? = null

    override fun read(): Int? {
        gate?.await()
        return read
    }

    override fun watch(changed: (Int) -> Unit): AutoCloseable? {
        this.changed = changed
        return AutoCloseable { this.changed = null }
    }
}

class PortalWatcherTest {
    private fun <T> waiting(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun anAnswerInTimeIsTheFirstAnswer() {
        val watcher = PortalWatcher(5_000, FakePortal(read = 2))
        watcher.start()
        assertFalse(watcher.awaitFirst())
        assertTrue(watcher.answered.value)
        watcher.close()
    }

    // No answer by the timeout is dark; the answer that comes later is taken.
    @Test
    fun aLateReplyIsAppliedAsOneSwitch() {
        val gate = CountDownLatch(1)
        val watcher = PortalWatcher(50, FakePortal(read = 2, gate = gate))
        watcher.start()
        assertTrue(watcher.awaitFirst())
        assertFalse(watcher.answered.value)
        gate.countDown()
        waiting { watcher.dark.first { !it } }
        assertTrue(watcher.answered.value)
        watcher.close()
    }

    @Test
    fun noPortalStaysDarkAndUnanswered() {
        val watcher = PortalWatcher(50, FakePortal(read = null))
        watcher.start()
        assertTrue(watcher.awaitFirst())
        assertFalse(watcher.answered.value)
        watcher.close()
    }

    // Changes after the first answer: 1 dark, 2 light, 0 counts as dark.
    @Test
    fun changesAreFollowed() {
        val portal = FakePortal(read = 1)
        val watcher = PortalWatcher(5_000, portal)
        watcher.start()
        assertTrue(watcher.awaitFirst())
        portal.changed!!(2)
        assertEquals(false, watcher.dark.value)
        portal.changed!!(0)
        assertEquals(true, watcher.dark.value)
        portal.changed!!(2)
        portal.changed!!(1)
        assertEquals(true, watcher.dark.value)
        watcher.close()
    }
}
